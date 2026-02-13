import { describe, it, expect } from "vitest";
import { UndoManager } from "../undo";
import type { Template } from "../types";

function createTemplate(name: string): Template {
  return { id: "test-1", name, blocks: [] };
}

describe("UndoManager $canUndo / $canRedo atoms", () => {
  it("should both be false initially", () => {
    const manager = new UndoManager();
    expect(manager.$canUndo.get()).toBe(false);
    expect(manager.$canRedo.get()).toBe(false);
  });

  it("should update $canUndo after push", () => {
    const manager = new UndoManager();
    manager.push(createTemplate("A"));
    expect(manager.$canUndo.get()).toBe(true);
    expect(manager.$canRedo.get()).toBe(false);
  });

  it("should update both after undo", () => {
    const manager = new UndoManager();
    manager.push(createTemplate("A"));
    manager.undo(createTemplate("B"));
    expect(manager.$canUndo.get()).toBe(false);
    expect(manager.$canRedo.get()).toBe(true);
  });

  it("should update both after redo", () => {
    const manager = new UndoManager();
    manager.push(createTemplate("A"));
    manager.push(createTemplate("B"));
    manager.undo(createTemplate("C"));
    // past has A, future has C
    expect(manager.$canUndo.get()).toBe(true);
    expect(manager.$canRedo.get()).toBe(true);

    manager.redo(createTemplate("B")); // B pushed to past
    expect(manager.$canUndo.get()).toBe(true);
    expect(manager.$canRedo.get()).toBe(false);
  });

  it("should both be false after clear", () => {
    const manager = new UndoManager();
    manager.push(createTemplate("A"));
    manager.push(createTemplate("B"));
    manager.undo(createTemplate("C"));

    manager.clear();
    expect(manager.$canUndo.get()).toBe(false);
    expect(manager.$canRedo.get()).toBe(false);
  });

  it("should clear $canRedo when push is called after undo", () => {
    const manager = new UndoManager();
    manager.push(createTemplate("A"));
    manager.undo(createTemplate("B"));
    expect(manager.$canRedo.get()).toBe(true);

    manager.push(createTemplate("C"));
    expect(manager.$canRedo.get()).toBe(false);
  });
});
