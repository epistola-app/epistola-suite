import { describe, it, expect, vi, beforeEach } from "vitest";
import { createHistory, createDebouncedPush } from "./history.ts";
import type { Command } from "./commands.ts";
import type { Template } from "../types/template.ts";

function createTestTemplate(name: string = "test"): Template {
  return {
    id: "test-template",
    name,
    version: 1,
    pageSettings: {
      format: "A4",
      orientation: "portrait",
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    blocks: [],
    documentStyles: {},
  };
}

function createMockCommand(name: string): Command<Template> {
  return {
    type: `TEST_${name.toUpperCase()}`,
    execute: (state) => ({ ...state, name: `${state.name}_${name}` }),
    undo: (state) => {
      // Simple undo: remove the suffix
      const newName = state.name.replace(`_${name}`, "");
      return { ...state, name: newName };
    },
    description: `Test command: ${name}`,
  };
}

describe("createHistory", () => {
  describe("initial state", () => {
    it("should start with empty history", () => {
      const history = createHistory();
      expect(history.canUndo()).toBe(false);
      expect(history.canRedo()).toBe(false);
      expect(history.undoCount()).toBe(0);
      expect(history.redoCount()).toBe(0);
    });
  });

  describe("push", () => {
    it("should add entry to undo stack", () => {
      const history = createHistory();
      const command = createMockCommand("test");
      const stateBefore = createTestTemplate();

      history.push(command, stateBefore);

      expect(history.canUndo()).toBe(true);
      expect(history.undoCount()).toBe(1);
    });

    it("should clear redo stack when pushing new command", () => {
      const history = createHistory();
      const template = createTestTemplate();

      // Push first command
      history.push(createMockCommand("first"), template);

      // Undo to create redo entry
      history.undo();
      expect(history.canRedo()).toBe(true);

      // Push new command - should clear redo
      history.push(createMockCommand("second"), template);
      expect(history.canRedo()).toBe(false);
    });

    it("should respect history limit", () => {
      const history = createHistory({ limit: 3 });
      const template = createTestTemplate();

      for (let i = 0; i < 5; i++) {
        history.push(createMockCommand(`cmd-${i}`), template);
      }

      expect(history.undoCount()).toBe(3);
    });
  });

  describe("undo", () => {
    it("should return state before command execution", () => {
      const history = createHistory();
      const stateBefore = createTestTemplate("original");
      const command = createMockCommand("change");

      history.push(command, stateBefore);
      const restored = history.undo();

      expect(restored).toEqual(stateBefore);
    });

    it("should return undefined when nothing to undo", () => {
      const history = createHistory();
      const result = history.undo();
      expect(result).toBeUndefined();
    });

    it("should move entry to redo stack", () => {
      const history = createHistory();
      history.push(createMockCommand("test"), createTestTemplate());

      history.undo();

      expect(history.canUndo()).toBe(false);
      expect(history.canRedo()).toBe(true);
      expect(history.redoCount()).toBe(1);
    });

    it("should support multiple undos", () => {
      const history = createHistory();

      history.push(createMockCommand("first"), createTestTemplate("v0"));
      history.push(createMockCommand("second"), createTestTemplate("v1"));
      history.push(createMockCommand("third"), createTestTemplate("v2"));

      expect(history.undo()?.name).toBe("v2");
      expect(history.undo()?.name).toBe("v1");
      expect(history.undo()?.name).toBe("v0");
      expect(history.undo()).toBeUndefined();
    });
  });

  describe("redo", () => {
    it("should re-execute command and return new state", () => {
      const history = createHistory();
      const originalState = createTestTemplate("original");
      const command = createMockCommand("change");

      history.push(command, originalState);
      history.undo();

      const redoneState = history.redo(originalState);
      expect(redoneState?.name).toBe("original_change");
    });

    it("should return undefined when nothing to redo", () => {
      const history = createHistory();
      const result = history.redo(createTestTemplate());
      expect(result).toBeUndefined();
    });

    it("should move entry back to undo stack", () => {
      const history = createHistory();
      history.push(createMockCommand("test"), createTestTemplate());
      history.undo();

      history.redo(createTestTemplate());

      expect(history.canUndo()).toBe(true);
      expect(history.canRedo()).toBe(false);
    });

    it("should support multiple redos", () => {
      const history = createHistory();
      const template = createTestTemplate("base");

      history.push(createMockCommand("a"), template);
      history.push(createMockCommand("b"), template);
      history.undo();
      history.undo();

      const state1 = history.redo(template);
      const state2 = history.redo(state1!);

      expect(state1?.name).toBe("base_a");
      expect(state2?.name).toBe("base_a_b");
    });
  });

  describe("clear", () => {
    it("should remove all history entries", () => {
      const history = createHistory();

      history.push(createMockCommand("a"), createTestTemplate());
      history.push(createMockCommand("b"), createTestTemplate());
      history.undo();

      history.clear();

      expect(history.canUndo()).toBe(false);
      expect(history.canRedo()).toBe(false);
      expect(history.undoCount()).toBe(0);
      expect(history.redoCount()).toBe(0);
    });
  });

  describe("subscribe", () => {
    it("should call listener immediately with current state", () => {
      const history = createHistory();
      const listener = vi.fn();

      history.subscribe(listener);

      expect(listener).toHaveBeenCalledWith(false, false);
    });

    it("should notify on push", () => {
      const history = createHistory();
      const listener = vi.fn();
      history.subscribe(listener);
      listener.mockClear();

      history.push(createMockCommand("test"), createTestTemplate());

      expect(listener).toHaveBeenCalledWith(true, false);
    });

    it("should notify on undo", () => {
      const history = createHistory();
      history.push(createMockCommand("test"), createTestTemplate());

      const listener = vi.fn();
      history.subscribe(listener);
      listener.mockClear();

      history.undo();

      expect(listener).toHaveBeenCalledWith(false, true);
    });

    it("should notify on redo", () => {
      const history = createHistory();
      history.push(createMockCommand("test"), createTestTemplate());
      history.undo();

      const listener = vi.fn();
      history.subscribe(listener);
      listener.mockClear();

      history.redo(createTestTemplate());

      expect(listener).toHaveBeenCalledWith(true, false);
    });

    it("should notify on clear", () => {
      const history = createHistory();
      history.push(createMockCommand("test"), createTestTemplate());

      const listener = vi.fn();
      history.subscribe(listener);
      listener.mockClear();

      history.clear();

      expect(listener).toHaveBeenCalledWith(false, false);
    });

    it("should return unsubscribe function", () => {
      const history = createHistory();
      const listener = vi.fn();
      const unsubscribe = history.subscribe(listener);
      listener.mockClear();

      unsubscribe();
      history.push(createMockCommand("test"), createTestTemplate());

      expect(listener).not.toHaveBeenCalled();
    });
  });
});

describe("createDebouncedPush", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  it("should debounce rapid pushes", () => {
    const history = createHistory();
    const debounced = createDebouncedPush(history, 500);
    const template = createTestTemplate("base");

    debounced.push(createMockCommand("a"), template);
    debounced.push(createMockCommand("b"), template);
    debounced.push(createMockCommand("c"), template);

    expect(history.undoCount()).toBe(0);

    vi.advanceTimersByTime(500);

    expect(history.undoCount()).toBe(1);
  });

  it("should use first state before in debounce window", () => {
    const history = createHistory();
    const debounced = createDebouncedPush(history, 500);

    const firstState = createTestTemplate("first");
    const secondState = createTestTemplate("second");

    debounced.push(createMockCommand("a"), firstState);
    debounced.push(createMockCommand("b"), secondState);

    vi.advanceTimersByTime(500);

    const restored = history.undo();
    expect(restored?.name).toBe("first");
  });

  it("should allow immediate flush", () => {
    const history = createHistory();
    const debounced = createDebouncedPush(history, 500);

    debounced.push(createMockCommand("test"), createTestTemplate());
    expect(history.undoCount()).toBe(0);

    debounced.flush();
    expect(history.undoCount()).toBe(1);
  });

  it("should not flush if nothing pending", () => {
    const history = createHistory();
    const debounced = createDebouncedPush(history, 500);

    debounced.flush();
    expect(history.undoCount()).toBe(0);
  });

  vi.useRealTimers();
});
