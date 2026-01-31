import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { useUndoRedoShortcuts } from "./use-undo-redo-shortcuts";
import * as editorStore from "../store/editorStore";

// Mock the store functions
vi.mock("../store/editorStore", async () => {
  const actual = await vi.importActual("../store/editorStore");
  return {
    ...actual,
    undo: vi.fn(),
    redo: vi.fn(),
  };
});

describe("useUndoRedoShortcuts", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    // Clean up any event listeners
  });

  function dispatchKeyEvent(key: string, options: Partial<KeyboardEventInit> = {}) {
    const event = new KeyboardEvent("keydown", {
      key,
      bubbles: true,
      ...options,
    });
    document.dispatchEvent(event);
    return event;
  }

  it("calls undo on Ctrl+Z", () => {
    renderHook(() => useUndoRedoShortcuts());

    dispatchKeyEvent("z", { ctrlKey: true });

    expect(editorStore.undo).toHaveBeenCalledTimes(1);
    expect(editorStore.redo).not.toHaveBeenCalled();
  });

  it("calls undo on Cmd+Z (macOS)", () => {
    renderHook(() => useUndoRedoShortcuts());

    dispatchKeyEvent("z", { metaKey: true });

    expect(editorStore.undo).toHaveBeenCalledTimes(1);
  });

  it("calls redo on Ctrl+Shift+Z", () => {
    renderHook(() => useUndoRedoShortcuts());

    dispatchKeyEvent("z", { ctrlKey: true, shiftKey: true });

    expect(editorStore.redo).toHaveBeenCalledTimes(1);
    expect(editorStore.undo).not.toHaveBeenCalled();
  });

  it("calls redo on Cmd+Shift+Z (macOS)", () => {
    renderHook(() => useUndoRedoShortcuts());

    dispatchKeyEvent("z", { metaKey: true, shiftKey: true });

    expect(editorStore.redo).toHaveBeenCalledTimes(1);
  });

  it("calls redo on Ctrl+Y", () => {
    renderHook(() => useUndoRedoShortcuts());

    dispatchKeyEvent("y", { ctrlKey: true });

    expect(editorStore.redo).toHaveBeenCalledTimes(1);
    expect(editorStore.undo).not.toHaveBeenCalled();
  });

  it("does not call undo/redo without modifier key", () => {
    renderHook(() => useUndoRedoShortcuts());

    dispatchKeyEvent("z");
    dispatchKeyEvent("y");

    expect(editorStore.undo).not.toHaveBeenCalled();
    expect(editorStore.redo).not.toHaveBeenCalled();
  });

  it("does not call undo when focused in TipTap editor", () => {
    // Create a mock TipTap editor element
    const mockEditor = document.createElement("div");
    mockEditor.className = "ProseMirror";
    mockEditor.setAttribute("contenteditable", "true");
    document.body.appendChild(mockEditor);

    // Focus the mock editor
    mockEditor.focus();
    Object.defineProperty(document, "activeElement", {
      value: mockEditor,
      configurable: true,
    });

    renderHook(() => useUndoRedoShortcuts());

    dispatchKeyEvent("z", { ctrlKey: true });

    expect(editorStore.undo).not.toHaveBeenCalled();

    // Cleanup
    document.body.removeChild(mockEditor);
  });

  it("does not call redo when focused in TipTap editor", () => {
    // Create a mock TipTap editor element
    const mockEditor = document.createElement("div");
    mockEditor.className = "ProseMirror";
    mockEditor.setAttribute("contenteditable", "true");
    document.body.appendChild(mockEditor);

    Object.defineProperty(document, "activeElement", {
      value: mockEditor,
      configurable: true,
    });

    renderHook(() => useUndoRedoShortcuts());

    dispatchKeyEvent("z", { ctrlKey: true, shiftKey: true });
    dispatchKeyEvent("y", { ctrlKey: true });

    expect(editorStore.redo).not.toHaveBeenCalled();

    // Cleanup
    document.body.removeChild(mockEditor);
  });

  it("cleans up event listener on unmount", () => {
    const removeEventListenerSpy = vi.spyOn(document, "removeEventListener");

    const { unmount } = renderHook(() => useUndoRedoShortcuts());
    unmount();

    expect(removeEventListenerSpy).toHaveBeenCalledWith("keydown", expect.any(Function));
  });
});
