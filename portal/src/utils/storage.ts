import type { ChatMessage, DiagramData, Question } from "../types";

const DIAGRAM_PREFIX = "sdp:diagram:";
const REVIEW_PREFIX = "sdp:review:";
const CHAT_PREFIX = "sdp:chat:";
const LAST_QUESTION_KEY = "sdp:lastQuestion";
const CUSTOM_QUESTIONS_KEY = "sdp:customQuestions";

export function saveDiagram(questionId: string, data: DiagramData): void {
  try {
    localStorage.setItem(
      `${DIAGRAM_PREFIX}${questionId}`,
      JSON.stringify(data)
    );
  } catch (e) {
    console.error(`Failed to save diagram for ${questionId}:`, e);
  }
}

export function loadDiagram(questionId: string): DiagramData | null {
  try {
    const raw = localStorage.getItem(`${DIAGRAM_PREFIX}${questionId}`);
    if (!raw) return null;
    return JSON.parse(raw) as DiagramData;
  } catch {
    return null;
  }
}

export function hasSavedDiagram(questionId: string): boolean {
  return localStorage.getItem(`${DIAGRAM_PREFIX}${questionId}`) !== null;
}

export function saveReviewNote(questionId: string, text: string): void {
  localStorage.setItem(`${REVIEW_PREFIX}${questionId}`, text);
}

export function loadReviewNote(questionId: string): string | null {
  return localStorage.getItem(`${REVIEW_PREFIX}${questionId}`);
}

export function saveLastQuestion(questionId: string): void {
  localStorage.setItem(LAST_QUESTION_KEY, questionId);
}

export function loadLastQuestion(): string | null {
  return localStorage.getItem(LAST_QUESTION_KEY);
}

export function saveChatHistory(
  questionId: string,
  messages: ChatMessage[]
): void {
  try {
    // Strip imageBase64 from saved messages to keep localStorage small
    const stripped = messages.map((m) => ({
      role: m.role,
      content: m.content,
      timestamp: m.timestamp,
    }));
    localStorage.setItem(`${CHAT_PREFIX}${questionId}`, JSON.stringify(stripped));
  } catch (e) {
    console.error(`Failed to save chat for ${questionId}:`, e);
  }
}

export function loadChatHistory(questionId: string): ChatMessage[] {
  try {
    const raw = localStorage.getItem(`${CHAT_PREFIX}${questionId}`);
    if (!raw) return [];
    return JSON.parse(raw) as ChatMessage[];
  } catch {
    return [];
  }
}

export function clearChatHistory(questionId: string): void {
  localStorage.removeItem(`${CHAT_PREFIX}${questionId}`);
}

// --- Custom Questions ---

export function saveCustomQuestions(questions: Question[]): void {
  try {
    localStorage.setItem(CUSTOM_QUESTIONS_KEY, JSON.stringify(questions));
  } catch (e) {
    console.error("Failed to save custom questions:", e);
  }
}

export function loadCustomQuestions(): Question[] {
  try {
    const raw = localStorage.getItem(CUSTOM_QUESTIONS_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as Question[];
  } catch {
    return [];
  }
}

export function deleteCustomQuestion(questionId: string): void {
  const questions = loadCustomQuestions().filter((q) => q.id !== questionId);
  saveCustomQuestions(questions);
  // Clean up associated data
  localStorage.removeItem(`${DIAGRAM_PREFIX}${questionId}`);
  localStorage.removeItem(`${REVIEW_PREFIX}${questionId}`);
  localStorage.removeItem(`${CHAT_PREFIX}${questionId}`);
}
