import { describe, it, expect, vi } from "vitest";
import { TemplateEditor } from "../editor";

describe("$isDirty reactive store", () => {
  it("should return false for empty untitled template", () => {
    const editor = new TemplateEditor();
    expect(editor.$isDirty.get()).toBe(false);
  });

  it("should return true when never saved and has blocks", () => {
    const editor = new TemplateEditor();
    editor.addBlock("text");
    expect(editor.$isDirty.get()).toBe(true);
  });

  it("should return true when never saved and name is changed", () => {
    const editor = new TemplateEditor();
    editor.updateTemplate({ name: "Custom" });
    expect(editor.$isDirty.get()).toBe(true);
  });

  it("should return false after markAsSaved with no further changes", () => {
    const editor = new TemplateEditor();
    editor.addBlock("text");
    editor.markAsSaved();
    expect(editor.$isDirty.get()).toBe(false);
  });

  it("should return true after modification following save", () => {
    const editor = new TemplateEditor();
    editor.addBlock("text");
    editor.markAsSaved();
    editor.addBlock("text");
    expect(editor.$isDirty.get()).toBe(true);
  });

  it("should return false when template matches saved state after undo", () => {
    const editor = new TemplateEditor();
    editor.addBlock("text");
    editor.markAsSaved();
    editor.addBlock("text");
    expect(editor.$isDirty.get()).toBe(true);

    editor.undo();
    expect(editor.$isDirty.get()).toBe(false);
  });

  it("should notify subscriber on transition from false to true", () => {
    const editor = new TemplateEditor();
    editor.addBlock("text");
    editor.markAsSaved();

    const values: boolean[] = [];
    const unsub = editor.$isDirty.subscribe((v) => values.push(v));

    // Trigger dirty
    editor.addBlock("text");

    // First call is the immediate subscription value (false), second is the transition (true)
    expect(values).toContain(false);
    expect(values).toContain(true);
    unsub();
  });

  it("should notify subscriber on transition from true to false", () => {
    const editor = new TemplateEditor();
    editor.addBlock("text");
    editor.markAsSaved();
    editor.addBlock("text");

    const values: boolean[] = [];
    const unsub = editor.$isDirty.subscribe((v) => values.push(v));

    // Save again — transitions to false
    editor.markAsSaved();

    expect(values[values.length - 1]).toBe(false);
    unsub();
  });

  it("should be accessible via getStores()", () => {
    const editor = new TemplateEditor();
    const stores = editor.getStores();
    expect(stores.$isDirty).toBe(editor.$isDirty);
  });
});
