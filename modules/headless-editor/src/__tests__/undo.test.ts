import { describe, it, expect } from "vitest";
import { UndoManager } from "../undo";
import type { Template } from "../types";

function createTemplate(name: string): Template {
  return {
    id: "test-1",
    name,
    blocks: [],
  };
}

describe("UndoManager", () => {
  describe("push", () => {
    it("should store state in history", () => {
      const manager = new UndoManager();
      const template = createTemplate("State 1");

      manager.push(template);

      expect(manager.canUndo()).toBe(true);
      expect(manager.getUndoCount()).toBe(1);
    });

    it("should clear redo stack on new push", () => {
      const manager = new UndoManager();
      const state1 = createTemplate("State 1");
      const state2 = createTemplate("State 2");
      const state3 = createTemplate("State 3");

      manager.push(state1);
      manager.push(state2);
      manager.undo(state3); // state2 goes to future

      expect(manager.canRedo()).toBe(true);

      manager.push(createTemplate("New State"));

      expect(manager.canRedo()).toBe(false);
    });

    it("should limit history size", () => {
      const manager = new UndoManager(3); // max 3

      for (let i = 0; i < 5; i++) {
        manager.push(createTemplate(`State ${i}`));
      }

      expect(manager.getUndoCount()).toBe(3);
    });

    it("should accept default maxHistory of 50", () => {
      const manager = new UndoManager();

      for (let i = 0; i < 55; i++) {
        manager.push(createTemplate(`State ${i}`));
      }

      expect(manager.getUndoCount()).toBe(50);
    });
  });

  describe("undo", () => {
    it("should return previous state", () => {
      const manager = new UndoManager();
      const state1 = createTemplate("State 1");
      const state2 = createTemplate("State 2");

      manager.push(state1);
      const result = manager.undo(state2);

      expect(result).toEqual(state1);
    });

    it("should return null when no history", () => {
      const manager = new UndoManager();
      const current = createTemplate("Current");

      const result = manager.undo(current);

      expect(result).toBeNull();
    });

    it("should move current to redo stack", () => {
      const manager = new UndoManager();
      const state1 = createTemplate("State 1");
      const state2 = createTemplate("State 2");

      manager.push(state1);
      manager.undo(state2);

      expect(manager.canRedo()).toBe(true);
      expect(manager.getRedoCount()).toBe(1);
    });

    it("should support multiple undos in sequence", () => {
      const manager = new UndoManager();
      manager.push(createTemplate("A"));
      manager.push(createTemplate("B"));
      manager.push(createTemplate("C"));

      const r1 = manager.undo(createTemplate("D"));
      expect(r1!.name).toBe("C");

      const r2 = manager.undo(r1!);
      expect(r2!.name).toBe("B");

      const r3 = manager.undo(r2!);
      expect(r3!.name).toBe("A");

      const r4 = manager.undo(r3!);
      expect(r4).toBeNull();
    });
  });

  describe("redo", () => {
    it("should return next state after undo", () => {
      const manager = new UndoManager();
      const state1 = createTemplate("State 1");
      const state2 = createTemplate("State 2");
      const state3 = createTemplate("State 3");

      manager.push(state1);
      manager.push(state2);
      const afterUndo = manager.undo(state3)!; // returns state2

      // Now redo
      const result = manager.redo(afterUndo);

      expect(result).toEqual(state3);
    });

    it("should return null when no future states", () => {
      const manager = new UndoManager();
      const current = createTemplate("Current");

      const result = manager.redo(current);

      expect(result).toBeNull();
    });

    it("should support multiple redos in sequence", () => {
      const manager = new UndoManager();
      manager.push(createTemplate("A"));
      manager.push(createTemplate("B"));

      const undone = manager.undo(createTemplate("C"))!; // returns B, C in future
      const undone2 = manager.undo(undone)!; // returns A, B in future

      const r1 = manager.redo(undone2);
      expect(r1!.name).toBe("B");

      const r2 = manager.redo(r1!);
      expect(r2!.name).toBe("C");

      const r3 = manager.redo(r2!);
      expect(r3).toBeNull();
    });
  });

  describe("canUndo/canRedo", () => {
    it("should report correct availability", () => {
      const manager = new UndoManager();
      const state1 = createTemplate("State 1");
      const state2 = createTemplate("State 2");

      expect(manager.canUndo()).toBe(false);
      expect(manager.canRedo()).toBe(false);

      manager.push(state1);
      expect(manager.canUndo()).toBe(true);
      expect(manager.canRedo()).toBe(false);

      manager.undo(state2);
      expect(manager.canUndo()).toBe(false);
      expect(manager.canRedo()).toBe(true);
    });
  });

  describe("clear", () => {
    it("should clear all history", () => {
      const manager = new UndoManager();

      manager.push(createTemplate("State 1"));
      manager.push(createTemplate("State 2"));
      manager.undo(createTemplate("Current"));

      manager.clear();

      expect(manager.canUndo()).toBe(false);
      expect(manager.canRedo()).toBe(false);
      expect(manager.getUndoCount()).toBe(0);
      expect(manager.getRedoCount()).toBe(0);
    });
  });

  describe("deep cloning", () => {
    it("should deep clone templates to prevent mutation", () => {
      const manager = new UndoManager();
      const original: Template = {
        id: "test-1",
        name: "Original",
        blocks: [{ id: "block-1", type: "text", content: "Original content" }],
      };

      manager.push(original);

      // Mutate the original
      original.name = "Mutated";
      (original.blocks[0] as { content: string }).content = "Mutated content";

      // Undo should return the unmutated version
      const restored = manager.undo(createTemplate("Current"))!;

      expect(restored.name).toBe("Original");
      expect((restored.blocks[0] as { content: string }).content).toBe(
        "Original content",
      );
    });

    it("should deep clone on redo to prevent mutation", () => {
      const manager = new UndoManager();
      const state1 = createTemplate("State 1");
      const state2 = createTemplate("State 2");

      manager.push(state1);
      const undone = manager.undo(state2)!;

      // Mutate the undone result
      undone.name = "Mutated";

      // Redo should still return the original state2
      const redone = manager.redo(undone);
      expect(redone!.name).toBe("State 2");
    });
  });
});
