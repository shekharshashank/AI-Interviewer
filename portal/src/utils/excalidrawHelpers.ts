import type { ExcalidrawImperativeAPI } from "@excalidraw/excalidraw/types";

export async function exportDiagramAsBlob(
  api: ExcalidrawImperativeAPI
): Promise<Blob> {
  const { exportToBlob } = await import("@excalidraw/excalidraw");
  const elements = api.getSceneElements();
  const appState = api.getAppState();
  const files = api.getFiles();

  return exportToBlob({
    elements,
    appState: {
      ...appState,
      exportWithDarkMode: appState.theme === "dark",
      exportBackground: true,
    },
    files,
    getDimensions: () => ({ width: 1920, height: 1080 }),
  });
}

export function exportDiagramAsJSON(api: ExcalidrawImperativeAPI): string {
  const elements = api.getSceneElements();
  const appState = api.getAppState();
  const files = api.getFiles();

  return JSON.stringify(
    {
      type: "excalidraw",
      version: 2,
      source: "system-design-portal",
      elements,
      appState: {
        viewBackgroundColor: appState.viewBackgroundColor,
        gridSize: appState.gridSize,
      },
      files,
    },
    null,
    2
  );
}
