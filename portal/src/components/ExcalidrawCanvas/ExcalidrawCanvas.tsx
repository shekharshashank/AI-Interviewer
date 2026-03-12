import { lazy, Suspense, useCallback, useRef, useEffect } from "react";
import type { ExcalidrawImperativeAPI } from "@excalidraw/excalidraw/types";
import type { DiagramData } from "../../types";
import { saveDiagram } from "../../utils/storage";
import "@excalidraw/excalidraw/index.css";
import "./ExcalidrawCanvas.css";

const Excalidraw = lazy(async () => {
  const mod = await import("@excalidraw/excalidraw");
  return { default: mod.Excalidraw };
});

interface Props {
  questionId: string;
  initialData: DiagramData | null;
  onAPIReady: (api: ExcalidrawImperativeAPI) => void;
}

export function ExcalidrawCanvas({
  questionId,
  initialData,
  onAPIReady,
}: Props) {
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const questionIdRef = useRef(questionId);
  questionIdRef.current = questionId;

  const handleChange = useCallback(
    (
      elements: readonly Record<string, unknown>[],
      appState: Record<string, unknown>,
      files: Record<string, unknown>
    ) => {
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
      saveTimerRef.current = setTimeout(() => {
        saveDiagram(questionIdRef.current, {
          elements,
          appState: {
            viewBackgroundColor: appState.viewBackgroundColor,
            currentItemFontFamily: appState.currentItemFontFamily,
          },
          files,
          lastModified: Date.now(),
        });
      }, 2000);
    },
    []
  );

  useEffect(() => {
    return () => {
      if (saveTimerRef.current) clearTimeout(saveTimerRef.current);
    };
  }, []);

  return (
    <div className="excalidraw-container">
      <Suspense
        fallback={<div className="canvas-loading">Loading editor...</div>}
      >
        <Excalidraw
          excalidrawAPI={onAPIReady}
          initialData={
            initialData
              ? {
                  elements: initialData.elements as never,
                  appState: initialData.appState as never,
                  files: initialData.files as never,
                }
              : undefined
          }
          onChange={handleChange as never}
          theme="dark"
          name={`${questionId}-diagram`}
          UIOptions={{
            canvasActions: {
              loadScene: false,
            },
          }}
        />
      </Suspense>
    </div>
  );
}
