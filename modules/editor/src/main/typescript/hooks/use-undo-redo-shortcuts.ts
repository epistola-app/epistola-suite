import { useEffect } from "react";
import { undo, redo } from "../store/editorStore";

/**
 * Check if the active element is inside a TipTap editor.
 * TipTap editors have contenteditable="true" and class containing "ProseMirror".
 */
function isInsideTipTapEditor(): boolean {
  const activeElement = document.activeElement;
  if (!activeElement) return false;

  // Check if the active element itself is a TipTap editor
  if (
    activeElement.getAttribute("contenteditable") === "true" &&
    activeElement.classList.contains("ProseMirror")
  ) {
    return true;
  }

  // Check if the active element is inside a TipTap editor
  const proseMirror = activeElement.closest(".ProseMirror");
  return proseMirror !== null;
}

/**
 * Hook that adds global keyboard shortcuts for undo/redo.
 * Routes to TipTap's internal history when focused in a text editor,
 * otherwise uses Zustand store's history.
 */
export function useUndoRedoShortcuts(): void {
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent): void {
      // Check for Ctrl (Windows/Linux) or Cmd (macOS)
      const isMod = event.ctrlKey || event.metaKey;
      if (!isMod) return;

      // Undo: Ctrl+Z or Cmd+Z
      if (event.key === "z" && !event.shiftKey) {
        // If focused in TipTap, let TipTap handle it
        if (isInsideTipTapEditor()) return;

        event.preventDefault();
        undo();
        return;
      }

      // Redo: Ctrl+Shift+Z or Cmd+Shift+Z
      if (event.key === "z" && event.shiftKey) {
        // If focused in TipTap, let TipTap handle it
        if (isInsideTipTapEditor()) return;

        event.preventDefault();
        redo();
        return;
      }

      // Redo: Ctrl+Y (Windows alternative)
      if (event.key === "y" && !event.shiftKey) {
        // If focused in TipTap, let TipTap handle it
        if (isInsideTipTapEditor()) return;

        event.preventDefault();
        redo();
        return;
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, []);
}
