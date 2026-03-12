export type QuestionTag =
  | "SCALE"
  | "CONSISTENCY"
  | "REAL-TIME"
  | "DATA"
  | "RELIABILITY"
  | "PLATFORM"
  | "SECURITY"
  | "ML";

export interface Question {
  id: string;
  title: string;
  category: string;
  categoryName: string;
  tags: QuestionTag[];
  description: string;
  isCustom?: boolean;
}

export interface DiagramData {
  elements: readonly Record<string, unknown>[];
  appState: Record<string, unknown>;
  files: Record<string, unknown>;
  lastModified: number;
}

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
  timestamp: number;
  /** Base64 PNG attached when user initiates review */
  imageBase64?: string;
}
