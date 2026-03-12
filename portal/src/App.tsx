import { useState, useCallback, useEffect } from "react";
import type { ExcalidrawImperativeAPI } from "@excalidraw/excalidraw/types";
import type { Question } from "./types";
import { questions } from "./data/questions";
import {
  loadDiagram,
  saveDiagram,
  saveLastQuestion,
  loadLastQuestion,
  loadCustomQuestions,
  saveCustomQuestions,
  deleteCustomQuestion,
} from "./utils/storage";
import { QuestionSidebar } from "./components/QuestionSidebar/QuestionSidebar";
import { ExcalidrawCanvas } from "./components/ExcalidrawCanvas/ExcalidrawCanvas";
import { ReviewPanel } from "./components/ReviewPanel/ReviewPanel";
import { QuestionDetail } from "./components/QuestionDetail/QuestionDetail";
import "./App.css";

export default function App() {
  const [customQuestions, setCustomQuestions] = useState<Question[]>(
    () => loadCustomQuestions()
  );
  const [selectedQuestionId, setSelectedQuestionId] = useState<string>(
    () => loadLastQuestion() ?? "Q01"
  );
  const [excalidrawAPI, setExcalidrawAPI] =
    useState<ExcalidrawImperativeAPI | null>(null);
  const [isReviewPanelOpen, setIsReviewPanelOpen] = useState(false);
  const [canvasKey, setCanvasKey] = useState(0);

  const allQuestions = [...customQuestions, ...questions];

  const selectedQuestion = allQuestions.find(
    (q) => q.id === selectedQuestionId
  ) ?? questions[0];

  const getInitialData = useCallback(() => {
    return loadDiagram(selectedQuestionId);
  }, [selectedQuestionId]);

  const handleSelectQuestion = useCallback(
    (questionId: string) => {
      if (excalidrawAPI) {
        const appState = excalidrawAPI.getAppState() as Record<string, unknown>;
        saveDiagram(selectedQuestionId, {
          elements: excalidrawAPI.getSceneElements() as never,
          appState: {
            viewBackgroundColor: appState.viewBackgroundColor,
            currentItemFontFamily: appState.currentItemFontFamily,
          },
          files: excalidrawAPI.getFiles() as never,
          lastModified: Date.now(),
        });
      }
      setSelectedQuestionId(questionId);
      saveLastQuestion(questionId);
      setCanvasKey((prev) => prev + 1);
    },
    [excalidrawAPI, selectedQuestionId]
  );

  const handleAddCustomQuestion = useCallback((question: Question) => {
    setCustomQuestions((prev) => {
      // Generate a unique ID based on existing custom questions
      const maxNum = prev.reduce((max, q) => {
        const num = parseInt(q.id.replace("C", ""), 10);
        return isNaN(num) ? max : Math.max(max, num);
      }, 0);
      const uniqueQuestion = { ...question, id: `C${String(maxNum + 1).padStart(2, "0")}` };
      const next = [...prev, uniqueQuestion];
      saveCustomQuestions(next);
      return next;
    });
  }, []);

  const handleDeleteCustomQuestion = useCallback(
    (id: string) => {
      deleteCustomQuestion(id);
      setCustomQuestions((prev) => prev.filter((q) => q.id !== id));
      // If deleting the currently selected question, switch to Q01
      if (selectedQuestionId === id) {
        setSelectedQuestionId("Q01");
        saveLastQuestion("Q01");
        setCanvasKey((prev) => prev + 1);
      }
    },
    [selectedQuestionId]
  );

  // Save on page unload
  useEffect(() => {
    const handleBeforeUnload = () => {
      if (excalidrawAPI) {
        const appState = excalidrawAPI.getAppState() as Record<string, unknown>;
        saveDiagram(selectedQuestionId, {
          elements: excalidrawAPI.getSceneElements() as never,
          appState: {
            viewBackgroundColor: appState.viewBackgroundColor,
            currentItemFontFamily: appState.currentItemFontFamily,
          },
          files: excalidrawAPI.getFiles() as never,
          lastModified: Date.now(),
        });
        saveLastQuestion(selectedQuestionId);
      }
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [excalidrawAPI, selectedQuestionId]);

  return (
    <div className="app-layout">
      <QuestionSidebar
        questions={questions}
        customQuestions={customQuestions}
        selectedQuestionId={selectedQuestionId}
        onSelectQuestion={handleSelectQuestion}
        onAddCustomQuestion={handleAddCustomQuestion}
        onDeleteCustomQuestion={handleDeleteCustomQuestion}
      />
      <main className="main-area">
        <QuestionDetail question={selectedQuestion} />
        <ExcalidrawCanvas
          key={canvasKey}
          questionId={selectedQuestionId}
          initialData={getInitialData()}
          onAPIReady={setExcalidrawAPI}
        />
      </main>
      <ReviewPanel
        isOpen={isReviewPanelOpen}
        onToggle={() => setIsReviewPanelOpen((o) => !o)}
        excalidrawAPI={excalidrawAPI}
        questionId={selectedQuestionId}
        question={selectedQuestion}
      />
    </div>
  );
}
