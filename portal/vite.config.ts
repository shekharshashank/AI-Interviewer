import { defineConfig, type Plugin, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import type { IncomingMessage } from "http";

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

function bedrockProxy(): Plugin {
  return {
    name: "bedrock-proxy",
    configureServer(server) {
      server.middlewares.use("/api/chat", async (req, res) => {
        if (req.method !== "POST") {
          res.statusCode = 405;
          res.end("Method not allowed");
          return;
        }

        try {
          const {
            BedrockRuntimeClient,
            ConverseStreamCommand,
          } = await import("@aws-sdk/client-bedrock-runtime");

          const rawBody = await readBody(req);
          const { messages, systemPrompt } = JSON.parse(rawBody);

          const region = process.env.AWS_REGION || "us-west-2";
          const bearerToken = process.env.AWS_BEARER_TOKEN_BEDROCK;
          const accessKeyId = process.env.AWS_ACCESS_KEY_ID;
          const secretAccessKey = process.env.AWS_SECRET_ACCESS_KEY;
          const sessionToken = process.env.AWS_SESSION_TOKEN;

          const authMethod = bearerToken
            ? "bearer-token"
            : accessKeyId
              ? "sts-credentials"
              : "default-chain";
          console.log(
            `[bedrock-proxy] region=${region}, auth=${authMethod}`
          );

          // Build client config
          const clientConfig: {
            region: string;
            credentials?: {
              accessKeyId: string;
              secretAccessKey: string;
              sessionToken?: string;
            };
          } = { region };

          // If no bearer token, use STS credentials if available
          if (!bearerToken && accessKeyId && secretAccessKey) {
            clientConfig.credentials = {
              accessKeyId,
              secretAccessKey,
              ...(sessionToken ? { sessionToken } : {}),
            };
          }

          const client = new BedrockRuntimeClient(clientConfig);

          // If bearer token is set, override SigV4 auth with bearer token
          if (bearerToken) {
            client.middlewareStack.addRelativeTo(
              (next: (args: unknown) => Promise<unknown>) =>
                async (args: {
                  request: {
                    headers: Record<string, string>;
                  };
                }) => {
                  delete args.request.headers["authorization"];
                  delete args.request.headers["Authorization"];
                  delete args.request.headers["x-amz-security-token"];
                  args.request.headers["authorization"] =
                    `Bearer ${bearerToken}`;
                  return next(args);
                },
              {
                relation: "after",
                toMiddleware: "awsAuthMiddleware",
                name: "bearerTokenAuth",
              }
            );
          }

          // Convert frontend messages to Bedrock Converse format
          const bedrockMessages = messages.map(
            (msg: {
              role: string;
              content: {
                type: string;
                text?: string;
                base64?: string;
              }[];
            }) => ({
              role: msg.role,
              content: msg.content.map(
                (block: {
                  type: string;
                  text?: string;
                  base64?: string;
                }) => {
                  if (block.type === "image" && block.base64) {
                    return {
                      image: {
                        format: "png" as const,
                        source: {
                          bytes: Buffer.from(block.base64, "base64"),
                        },
                      },
                    };
                  }
                  return { text: block.text || "" };
                }
              ),
            })
          );

          const modelId =
            process.env.BEDROCK_MODEL_ID ||
            "us.anthropic.claude-sonnet-4-20250514-v1:0";

          console.log(`[bedrock-proxy] Calling model: ${modelId}`);

          const command = new ConverseStreamCommand({
            modelId,
            system: [{ text: systemPrompt }],
            messages: bedrockMessages,
            inferenceConfig: { maxTokens: 4096 },
          });

          const response = await client.send(command);

          // Stream SSE back to the browser
          res.setHeader("Content-Type", "text/event-stream");
          res.setHeader("Cache-Control", "no-cache");
          res.setHeader("Connection", "keep-alive");

          if (response.stream) {
            for await (const event of response.stream) {
              if (event.contentBlockDelta?.delta?.text) {
                const data = JSON.stringify({
                  text: event.contentBlockDelta.delta.text,
                });
                res.write(`data: ${data}\n\n`);
              }
            }
          }

          res.write("data: [DONE]\n\n");
          res.end();
        } catch (err) {
          console.error("Bedrock API error:", err);
          res.statusCode = 500;
          res.setHeader("Content-Type", "application/json");
          res.end(
            JSON.stringify({
              error:
                err instanceof Error ? err.message : "Bedrock API failed",
            })
          );
        }
      });
    },
  };
}

export default defineConfig(({ mode }) => {
  // Load .env file vars into process.env
  // prefix "" loads ALL env vars from .env, not just VITE_ ones
  const env = loadEnv(mode, process.cwd(), "");
  for (const [key, value] of Object.entries(env)) {
    // Shell env takes precedence over .env file
    if (!process.env[key]) {
      process.env[key] = value;
    }
  }

  return {
    plugins: [react(), bedrockProxy()],
    define: {
      "process.env.IS_PREACT": JSON.stringify("false"),
    },
    build: {
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        output: {
          manualChunks: {
            excalidraw: ["@excalidraw/excalidraw"],
          },
        },
      },
    },
  };
});
