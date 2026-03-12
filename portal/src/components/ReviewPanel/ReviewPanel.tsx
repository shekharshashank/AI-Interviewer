import { useState, useCallback, useEffect, useRef } from "react";
import type { ExcalidrawImperativeAPI } from "@excalidraw/excalidraw/types";
import type { Question, ChatMessage } from "../../types";
import {
  exportDiagramAsBlob,
  exportDiagramAsJSON,
} from "../../utils/excalidrawHelpers";
import { downloadFile, importFile } from "../../utils/fileIO";
import {
  loadChatHistory,
  saveChatHistory,
  clearChatHistory,
} from "../../utils/storage";
import { sendMessage, summarizeDiagramJSON } from "../../services/claude";
import "./ReviewPanel.css";

interface Props {
  isOpen: boolean;
  onToggle: () => void;
  excalidrawAPI: ExcalidrawImperativeAPI | null;
  questionId: string;
  question: Question;
}

export function ReviewPanel({
  isOpen,
  onToggle,
  excalidrawAPI,
  questionId,
  question,
}: Props) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [inputText, setInputText] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [streamingText, setStreamingText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"chat" | "export">("chat");
  const chatEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Load chat history on question change
  useEffect(() => {
    const history = loadChatHistory(questionId);
    setMessages(history);
    setStreamingText("");
    setError(null);
  }, [questionId]);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streamingText]);

  // Capture diagram as base64 PNG
  const captureDiagramImage = useCallback(async (): Promise<string | null> => {
    if (!excalidrawAPI) return null;
    try {
      const blob = await exportDiagramAsBlob(excalidrawAPI);
      return new Promise((resolve) => {
        const reader = new FileReader();
        reader.onloadend = () => {
          const base64 = (reader.result as string).split(",")[1];
          resolve(base64);
        };
        reader.readAsDataURL(blob);
      });
    } catch {
      return null;
    }
  }, [excalidrawAPI]);

  // Get diagram JSON summary
  const getDiagramSummary = useCallback((): string | null => {
    if (!excalidrawAPI) return null;
    try {
      const json = exportDiagramAsJSON(excalidrawAPI);
      return summarizeDiagramJSON(json);
    } catch {
      return null;
    }
  }, [excalidrawAPI]);

  // Start a new review session — sends diagram to Claude
  const handleStartReview = useCallback(async () => {
    if (!excalidrawAPI) return;
    setIsLoading(true);
    setError(null);

    try {
      const imageBase64 = await captureDiagramImage();
      const diagramSummary = getDiagramSummary();

      const userMessage: ChatMessage = {
        role: "user",
        content:
          "Here is my system design diagram. Please review it and start the deep-dive interview.",
        timestamp: Date.now(),
        imageBase64: imageBase64 ?? undefined,
      };

      const newMessages = [userMessage];
      setMessages(newMessages);

      const assistantText = await sendMessage(
        newMessages,
        question,
        imageBase64,
        diagramSummary,
        (chunk) => setStreamingText(chunk)
      );

      const assistantMessage: ChatMessage = {
        role: "assistant",
        content: assistantText,
        timestamp: Date.now(),
      };

      const updatedMessages = [...newMessages, assistantMessage];
      setMessages(updatedMessages);
      setStreamingText("");
      saveChatHistory(questionId, updatedMessages);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to connect to Claude");
    } finally {
      setIsLoading(false);
    }
  }, [
    excalidrawAPI,
    captureDiagramImage,
    getDiagramSummary,
    question,
    questionId,
  ]);

  // Send a follow-up message in the conversation
  const handleSendMessage = useCallback(async () => {
    const text = inputText.trim();
    if (!text || isLoading) return;

    setInputText("");
    setIsLoading(true);
    setError(null);

    const userMessage: ChatMessage = {
      role: "user",
      content: text,
      timestamp: Date.now(),
    };

    const newMessages = [...messages, userMessage];
    setMessages(newMessages);

    try {
      // On follow-ups, re-capture diagram in case it changed
      const imageBase64 = await captureDiagramImage();
      const diagramSummary = getDiagramSummary();

      const assistantText = await sendMessage(
        newMessages,
        question,
        imageBase64,
        diagramSummary,
        (chunk) => setStreamingText(chunk)
      );

      const assistantMessage: ChatMessage = {
        role: "assistant",
        content: assistantText,
        timestamp: Date.now(),
      };

      const updatedMessages = [...newMessages, assistantMessage];
      setMessages(updatedMessages);
      setStreamingText("");
      saveChatHistory(questionId, updatedMessages);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to send message");
    } finally {
      setIsLoading(false);
    }
  }, [
    inputText,
    isLoading,
    messages,
    captureDiagramImage,
    getDiagramSummary,
    question,
    questionId,
  ]);

  // Handle Enter key (Shift+Enter for newline)
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleSendMessage();
      }
    },
    [handleSendMessage]
  );

  const handleClearChat = useCallback(() => {
    clearChatHistory(questionId);
    setMessages([]);
    setStreamingText("");
    setError(null);
  }, [questionId]);

  // Export handlers
  const handleExportPNG = useCallback(async () => {
    if (!excalidrawAPI) return;
    const blob = await exportDiagramAsBlob(excalidrawAPI);
    downloadFile(blob, `${questionId}-diagram.png`);
  }, [excalidrawAPI, questionId]);

  const handleExportJSON = useCallback(() => {
    if (!excalidrawAPI) return;
    const json = exportDiagramAsJSON(excalidrawAPI);
    const blob = new Blob([json], { type: "application/json" });
    downloadFile(blob, `${questionId}-diagram.excalidraw`);
  }, [excalidrawAPI, questionId]);

  const handleImportJSON = useCallback(async () => {
    if (!excalidrawAPI) return;
    try {
      const json = await importFile();
      const parsed = JSON.parse(json);
      excalidrawAPI.updateScene({
        elements: parsed.elements,
        appState: parsed.appState,
      });
      if (parsed.files) {
        excalidrawAPI.addFiles(
          Object.entries(parsed.files).map(([id, file]) => ({
            id,
            ...(file as Record<string, unknown>),
          })) as never
        );
      }
      excalidrawAPI.scrollToContent();
    } catch (e) {
      console.error("Failed to import file:", e);
    }
  }, [excalidrawAPI]);

  if (!isOpen) {
    return (
      <button
        className="review-toggle-btn"
        onClick={onToggle}
        title="Open AI Review"
      >
        A<br />I<br />
        <br />R<br />e<br />v<br />i<br />e<br />w
      </button>
    );
  }

  return (
    <aside className="review-panel">
      <div className="review-header">
        <h3>AI Interviewer</h3>
        <div className="header-actions">
          <button
            className={`tab-btn ${activeTab === "chat" ? "active" : ""}`}
            onClick={() => setActiveTab("chat")}
          >
            Chat
          </button>
          <button
            className={`tab-btn ${activeTab === "export" ? "active" : ""}`}
            onClick={() => setActiveTab("export")}
          >
            Export
          </button>
          <button className="close-btn" onClick={onToggle}>
            &times;
          </button>
        </div>
      </div>

      {activeTab === "export" && (
        <div className="export-tab">
          <div className="export-section">
            <h4 className="section-label">Export Diagram</h4>
            <div className="export-actions">
              <button onClick={handleExportPNG} disabled={!excalidrawAPI}>
                PNG
              </button>
              <button onClick={handleExportJSON} disabled={!excalidrawAPI}>
                JSON
              </button>
            </div>
          </div>
          <div className="export-section">
            <h4 className="section-label">Import</h4>
            <div className="export-actions">
              <button onClick={handleImportJSON} disabled={!excalidrawAPI}>
                Load JSON File
              </button>
            </div>
          </div>
        </div>
      )}

      {activeTab === "chat" && (
        <div className="chat-tab">
          {/* Chat messages */}
          <div className="chat-messages">
            {messages.length === 0 && !isLoading && (
              <div className="chat-empty">
                <p className="empty-title">Ready to review your design</p>
                <p className="empty-hint">
                  Draw your system design on the canvas, then click "Start
                  Review" below. The AI interviewer will analyze your diagram and
                  ask deep-dive questions like a Principal-level bar raiser.
                </p>
                <button
                  className="start-review-btn"
                  onClick={handleStartReview}
                  disabled={!excalidrawAPI}
                >
                  Start Review
                </button>
              </div>
            )}

            {messages.map((msg, idx) => (
              <div key={idx} className={`chat-message ${msg.role}`}>
                <div className="message-header">
                  <span className="message-role">
                    {msg.role === "assistant" ? "Interviewer" : "You"}
                  </span>
                </div>
                <div className="message-content">{msg.content}</div>
              </div>
            ))}

            {/* Streaming indicator */}
            {isLoading && streamingText && (
              <div className="chat-message assistant">
                <div className="message-header">
                  <span className="message-role">Interviewer</span>
                  <span className="streaming-indicator">typing...</span>
                </div>
                <div className="message-content">{streamingText}</div>
              </div>
            )}

            {isLoading && !streamingText && (
              <div className="chat-message assistant">
                <div className="message-header">
                  <span className="message-role">Interviewer</span>
                </div>
                <div className="message-content thinking">
                  Analyzing your design...
                </div>
              </div>
            )}

            {error && (
              <div className="chat-error">
                <strong>Error:</strong> {error}
              </div>
            )}

            <div ref={chatEndRef} />
          </div>

          {/* Input area */}
          {messages.length > 0 && (
            <div className="chat-input-area">
              <div className="input-row">
                <textarea
                  ref={textareaRef}
                  className="chat-input"
                  value={inputText}
                  onChange={(e) => setInputText(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="Answer the interviewer..."
                  rows={2}
                  disabled={isLoading}
                />
                <button
                  className="send-btn"
                  onClick={handleSendMessage}
                  disabled={!inputText.trim() || isLoading}
                >
                  Send
                </button>
              </div>
              <div className="input-actions">
                <button
                  className="action-link"
                  onClick={handleStartReview}
                  disabled={isLoading}
                  title="Re-capture your current diagram and start a fresh review"
                >
                  Re-capture Diagram
                </button>
                <button
                  className="action-link danger"
                  onClick={handleClearChat}
                  disabled={isLoading}
                >
                  Clear Chat
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </aside>
  );
}
