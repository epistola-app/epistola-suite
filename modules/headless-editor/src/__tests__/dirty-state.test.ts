import { describe, it, expect, vi } from "vitest";
import { TemplateEditor } from "../editor";
import type { Template } from "../types";

describe("Dirty State Tracking", () => {
  describe("initial state", () => {
    it("should have lastSavedTemplate as null initially", () => {
      const editor = new TemplateEditor();
      const stores = editor.getStores();

      expect(stores.$lastSavedTemplate.get()).toBeNull();
    });

    it("should not be dirty when template is empty and untitled", () => {
      const editor = new TemplateEditor();

      expect(editor.isDirty()).toBe(false);
    });

    it("should be dirty when never saved and has blocks", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");

      expect(editor.isDirty()).toBe(true);
    });

    it("should be dirty when never saved and name is changed", () => {
      const editor = new TemplateEditor();
      editor.updateTemplate({ name: "Custom Name" });

      expect(editor.isDirty()).toBe(true);
    });
  });

  describe("markAsSaved", () => {
    it("should save snapshot of current template", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");

      editor.markAsSaved();

      const stores = editor.getStores();
      expect(stores.$lastSavedTemplate.get()).not.toBeNull();
      expect(stores.$lastSavedTemplate.get()?.blocks).toHaveLength(1);
    });

    it("should create deep copy of template", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;
      editor.markAsSaved();

      // Mutate the current template
      editor.updateBlock(block.id, { content: "Modified" });

      // Saved version should be unchanged
      const stores = editor.getStores();
      const savedTemplate = stores.$lastSavedTemplate.get();
      const savedBlock = savedTemplate?.blocks[0] as { content?: string };
      expect(savedBlock?.content).not.toBe("Modified");
    });

    it("isDirty should return false after markAsSaved with no changes", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");
      editor.markAsSaved();

      expect(editor.isDirty()).toBe(false);
    });
  });

  describe("isDirty after modifications", () => {
    it("should return true after adding a block", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");
      editor.markAsSaved();
      expect(editor.isDirty()).toBe(false);

      editor.addBlock("text");

      expect(editor.isDirty()).toBe(true);
    });

    it("should return true after updating a block", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;
      editor.markAsSaved();
      expect(editor.isDirty()).toBe(false);

      editor.updateBlock(block.id, { content: "Updated" });

      expect(editor.isDirty()).toBe(true);
    });

    it("should return true after deleting a block", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;
      editor.markAsSaved();
      expect(editor.isDirty()).toBe(false);

      editor.deleteBlock(block.id);

      expect(editor.isDirty()).toBe(true);
    });

    it("should return true after moving a block", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");
      const block2 = editor.addBlock("text")!;
      editor.markAsSaved();
      expect(editor.isDirty()).toBe(false);

      editor.moveBlock(block2.id, null, 0);

      expect(editor.isDirty()).toBe(true);
    });

    it("should return true after renaming template", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");
      editor.markAsSaved();
      expect(editor.isDirty()).toBe(false);

      editor.updateTemplate({ name: "New Name" });

      expect(editor.isDirty()).toBe(true);
    });
  });

  describe("markAsSaved resets dirty state", () => {
    it("should mark as not dirty after saving changes", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");
      editor.markAsSaved();

      editor.addBlock("text");
      expect(editor.isDirty()).toBe(true);

      editor.markAsSaved();
      expect(editor.isDirty()).toBe(false);
    });
  });

  describe("subscription to lastSavedTemplate", () => {
    it("should notify when lastSavedTemplate changes", () => {
      const editor = new TemplateEditor();
      const stores = editor.getStores();
      const callback = vi.fn();

      const unsubscribe = stores.$lastSavedTemplate.subscribe(callback);
      editor.markAsSaved();

      expect(callback).toHaveBeenCalled();
      unsubscribe();
    });
  });
});
