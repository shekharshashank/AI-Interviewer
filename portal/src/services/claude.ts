import type { ChatMessage, Question } from "../types";

function buildSystemPrompt(question: Question): string {
  return `You are a ruthless System Design interview coach targeting Principal / Sr. Staff Engineer (L7/L8) level.

## Your Role
You are reviewing the candidate's system design diagram for the following problem:

**${question.id}: ${question.title}**
Tags: ${question.tags.join(", ")}

**Problem Brief:**
${question.description}

## How to Behave

1. **Analyze the diagram thoroughly** — identify components, data flows, connections, and labels.
2. **Start with a brief assessment** (2-3 sentences) of what you see — acknowledge what's there before critiquing.
3. **Ask 2-3 pointed follow-up questions** that a Principal-level interviewer would ask. Focus on:
   - Missing components or flows
   - Consistency/availability trade-offs not addressed
   - Failure modes not covered
   - Capacity/scaling gaps
   - "Why this technology and not X?"
4. **Go deep** — don't accept surface-level answers. If the candidate says "use a cache," ask: what cache, what eviction policy, what's the hit ratio assumption, how do you handle invalidation?
5. **Inject chaos** — after a few exchanges, change requirements: "Now it needs to work across 3 regions" or "Traffic just 10x'd" or "The primary database just went down."
6. **Demand numbers** — if the candidate says "it's fast," ask "how fast? prove it."
7. **Be concise** — keep responses focused. No fluff. Each message should have clear, actionable content.
8. **Use markdown formatting** — use bold, bullet points, and code blocks when helpful.

## Scoring Dimensions (assess throughout the conversation)
- Scoping & Requirements
- API Design
- Capacity Estimation
- Architecture Depth
- Trade-off Articulation
- Failure Handling
- Operational Maturity
- Communication Clarity

When the candidate asks for a score or after ~10 exchanges, provide a scorecard rating each dimension 1-5 with specific feedback.

## Important
- Never hand-hold. Challenge every decision.
- If the diagram is empty or has very few components, point that out and ask them to walk you through their high-level design first.
- Reference the specific problem requirements from the brief above.`;
}

/** Content block sent to the server API */
interface ContentBlock {
  type: "text" | "image";
  text?: string;
  base64?: string;
  mediaType?: string;
}

interface ApiMessage {
  role: "user" | "assistant";
  content: ContentBlock[];
}

function buildApiMessages(
  chatHistory: ChatMessage[],
  diagramImageBase64: string | null,
  diagramJSON: string | null
): ApiMessage[] {
  const messages: ApiMessage[] = [];

  for (let i = 0; i < chatHistory.length; i++) {
    const msg = chatHistory[i];

    if (i === 0 && msg.role === "user") {
      // First message: attach diagram image + JSON context
      const content: ContentBlock[] = [];

      const imgData = diagramImageBase64 || msg.imageBase64;
      if (imgData) {
        content.push({
          type: "image",
          base64: imgData,
          mediaType: "image/png",
        });
      }

      if (diagramJSON) {
        content.push({
          type: "text",
          text: `## Diagram Structure (Excalidraw JSON summary)\n\`\`\`\n${diagramJSON}\n\`\`\``,
        });
      }

      content.push({ type: "text", text: msg.content });
      messages.push({ role: "user", content });
    } else {
      messages.push({
        role: msg.role,
        content: [{ type: "text", text: msg.content }],
      });
    }
  }

  return messages;
}

/** Extract a compact summary of diagram elements */
export function summarizeDiagramJSON(rawJSON: string): string {
  try {
    const parsed = JSON.parse(rawJSON);
    const elements = parsed.elements || [];

    const textElements = elements
      .filter(
        (el: Record<string, unknown>) =>
          el.type === "text" && el.text && !el.isDeleted
      )
      .map(
        (el: Record<string, unknown>) =>
          `- Text: "${(el.text as string).trim()}"`
      );

    const shapes = elements
      .filter(
        (el: Record<string, unknown>) =>
          ["rectangle", "ellipse", "diamond"].includes(el.type as string) &&
          !el.isDeleted
      )
      .map((el: Record<string, unknown>) => {
        const label = el.text || el.label || "(unlabeled)";
        return `- ${el.type}: "${label}"`;
      });

    const arrows = elements.filter(
      (el: Record<string, unknown>) => el.type === "arrow" && !el.isDeleted
    );

    return [
      `**Components (${shapes.length}):**`,
      ...shapes.slice(0, 30),
      "",
      `**Labels (${textElements.length}):**`,
      ...textElements.slice(0, 30),
      "",
      `**Connections:** ${arrows.length} arrows`,
    ].join("\n");
  } catch {
    return "(Could not parse diagram JSON)";
  }
}

export async function sendMessage(
  chatHistory: ChatMessage[],
  question: Question,
  diagramImageBase64: string | null,
  diagramJSON: string | null,
  onStreamChunk?: (chunk: string) => void
): Promise<string> {
  const systemPrompt = buildSystemPrompt(question);
  const apiMessages = buildApiMessages(
    chatHistory,
    diagramImageBase64,
    diagramJSON
  );

  const response = await fetch("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      messages: apiMessages,
      systemPrompt,
    }),
  });

  if (!response.ok) {
    const errorBody = await response.text();
    let errorMsg: string;
    try {
      errorMsg = JSON.parse(errorBody).error;
    } catch {
      errorMsg = errorBody;
    }
    throw new Error(`Bedrock API error (${response.status}): ${errorMsg}`);
  }

  // Read SSE stream
  if (response.body && onStreamChunk) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let fullText = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      const chunk = decoder.decode(value, { stream: true });
      const lines = chunk.split("\n");

      for (const line of lines) {
        if (line.startsWith("data: ")) {
          const data = line.slice(6).trim();
          if (data === "[DONE]") continue;
          try {
            const parsed = JSON.parse(data);
            if (parsed.text) {
              fullText += parsed.text;
              onStreamChunk(fullText);
            }
          } catch {
            // skip
          }
        }
      }
    }

    return fullText;
  }

  // Non-streaming fallback
  const text = await response.text();
  let fullText = "";
  const lines = text.split("\n");
  for (const line of lines) {
    if (line.startsWith("data: ")) {
      const data = line.slice(6).trim();
      if (data === "[DONE]") continue;
      try {
        const parsed = JSON.parse(data);
        if (parsed.text) fullText += parsed.text;
      } catch {
        // skip
      }
    }
  }
  return fullText;
}
