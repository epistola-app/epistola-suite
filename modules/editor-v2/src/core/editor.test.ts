import { describe, it, expect, beforeEach, vi } from "vitest";
import { createEditor } from "./editor.ts";
import { registry, registerBlock } from "../blocks/index.ts";
import type { BlockDefinition } from "../blocks/types.ts";
import type { Template, TextBlock, ContainerBlock } from "../types/template.ts";

// ============================================================================
// Test Block Definitions
// ============================================================================

const textDef: BlockDefinition<TextBlock> = {
  type: "text",
  label: "Text",
  category: "content",
  icon: "",
  createDefault: () => ({
    id: crypto.randomUUID(),
    type: "text",
    content: { type: "doc", content: [] },
  }),
};

const containerDef: BlockDefinition<ContainerBlock> = {
  type: "container",
  label: "Container",
  category: "structure",
  icon: "",
  createDefault: () => ({
    id: crypto.randomUUID(),
    type: "container",
    children: [],
  }),
  getChildren: (block) => block.children,
  setChildren: (block, children) => ({ ...block, children }),
};

// ============================================================================
// Test Helpers
// ============================================================================

function createTestTemplate(): Template {
  return {
    id: "test-template",
    name: "Test Template",
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

// ============================================================================
// Tests
// ============================================================================

describe("createEditor", () => {
  beforeEach(() => {
    registry.clear();
    registerBlock(textDef);
    registerBlock(containerDef);
  });

  describe("initialization", () => {
    it("should create editor with default template", () => {
      const editor = createEditor();

      const template = editor.getTemplate();
      expect(template.id).toBeDefined();
      expect(template.name).toBe("Untitled Template");
      expect(template.blocks).toEqual([]);

      editor.dispose();
    });

    it("should create editor with provided template", () => {
      const template = createTestTemplate();
      const editor = createEditor({ template });

      expect(editor.getTemplate()).toBe(template);

      editor.dispose();
    });

    it("should start with no selection", () => {
      const editor = createEditor();

      expect(editor.getSelectedBlockId()).toBeNull();

      editor.dispose();
    });
  });

  describe("block mutations", () => {
    it("should add a block at root level", () => {
      const editor = createEditor();

      const block = editor.addBlock("text", null, 0);

      expect(block).not.toBeNull();
      expect(editor.getTemplate().blocks).toHaveLength(1);
      expect(editor.getTemplate().blocks[0].type).toBe("text");

      editor.dispose();
    });

    it("should add a block at specific index", () => {
      const editor = createEditor();

      editor.addBlock("text", null, 0);
      editor.addBlock("container", null, 0);

      const blocks = editor.getTemplate().blocks;
      expect(blocks).toHaveLength(2);
      expect(blocks[0].type).toBe("container");
      expect(blocks[1].type).toBe("text");

      editor.dispose();
    });

    it("should return null for unknown block type", () => {
      const editor = createEditor();

      const block = editor.addBlock("unknown" as any, null, 0);

      expect(block).toBeNull();
      expect(editor.getTemplate().blocks).toHaveLength(0);

      editor.dispose();
    });

    it("should update a block", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;

      editor.updateBlock(block.id, { styles: { color: "red" } });

      expect(editor.getTemplate().blocks[0].styles).toEqual({ color: "red" });

      editor.dispose();
    });

    it("should delete a block", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;

      editor.deleteBlock(block.id);

      expect(editor.getTemplate().blocks).toHaveLength(0);

      editor.dispose();
    });

    it("should move a block", () => {
      const editor = createEditor();
      const block1 = editor.addBlock("text", null, 0)!;
      const block2 = editor.addBlock("container", null, 1)!;

      editor.moveBlock(block2.id, null, 0);

      const blocks = editor.getTemplate().blocks;
      expect(blocks[0].id).toBe(block2.id);
      expect(blocks[1].id).toBe(block1.id);

      editor.dispose();
    });
  });

  describe("document mutations", () => {
    it("should update document styles", () => {
      const editor = createEditor();

      editor.updateDocumentStyles({ fontFamily: "Arial" });

      expect(editor.getTemplate().documentStyles.fontFamily).toBe("Arial");

      editor.dispose();
    });

    it("should update page settings", () => {
      const editor = createEditor();

      editor.updatePageSettings({ format: "Letter" });

      expect(editor.getTemplate().pageSettings.format).toBe("Letter");

      editor.dispose();
    });

    it("should update theme", () => {
      const editor = createEditor();

      editor.updateTheme("theme-123");

      expect(editor.getTemplate().themeId).toBe("theme-123");

      editor.dispose();
    });
  });

  describe("history", () => {
    it("should support undo", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;
      editor.updateBlock(block.id, { styles: { color: "red" } });

      expect(editor.canUndo()).toBe(true);

      const result = editor.undo();

      expect(result).toBe(true);
      expect(editor.getTemplate().blocks[0].styles).toBeUndefined();

      editor.dispose();
    });

    it("should support redo", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;
      editor.updateBlock(block.id, { styles: { color: "red" } });
      editor.undo();

      expect(editor.canRedo()).toBe(true);

      const result = editor.redo();

      expect(result).toBe(true);
      expect(editor.getTemplate().blocks[0].styles).toEqual({ color: "red" });

      editor.dispose();
    });

    it("should return false when no undo available", () => {
      const editor = createEditor();

      expect(editor.canUndo()).toBe(false);
      expect(editor.undo()).toBe(false);

      editor.dispose();
    });

    it("should return false when no redo available", () => {
      const editor = createEditor();
      editor.addBlock("text", null, 0);

      expect(editor.canRedo()).toBe(false);
      expect(editor.redo()).toBe(false);

      editor.dispose();
    });

    it("should clear history", () => {
      const editor = createEditor();
      editor.addBlock("text", null, 0);
      editor.addBlock("text", null, 1);

      expect(editor.canUndo()).toBe(true);

      editor.clearHistory();

      expect(editor.canUndo()).toBe(false);

      editor.dispose();
    });
  });

  describe("persistence", () => {
    it("should start as not dirty", () => {
      const editor = createEditor();

      expect(editor.isDirty()).toBe(false);

      editor.dispose();
    });

    it("should become dirty after mutation", () => {
      const editor = createEditor();

      editor.addBlock("text", null, 0);

      expect(editor.isDirty()).toBe(true);

      editor.dispose();
    });

    it("should call onSave when saveNow is triggered", async () => {
      const onSave = vi.fn();
      const editor = createEditor({ onSave });

      editor.addBlock("text", null, 0);
      await editor.saveNow();

      expect(onSave).toHaveBeenCalledWith(editor.getTemplate());

      editor.dispose();
    });

    it("should not be dirty after markSaved", () => {
      const editor = createEditor();

      editor.addBlock("text", null, 0);
      expect(editor.isDirty()).toBe(true);

      editor.markSaved();

      expect(editor.isDirty()).toBe(false);

      editor.dispose();
    });
  });

  describe("template replacement", () => {
    it("should replace template", () => {
      const editor = createEditor();
      editor.addBlock("text", null, 0);

      const newTemplate = createTestTemplate();
      editor.setTemplate(newTemplate);

      expect(editor.getTemplate()).toBe(newTemplate);
      expect(editor.getSelectedBlockId()).toBeNull();

      editor.dispose();
    });

    it("should clear history after replacement", () => {
      const editor = createEditor();
      editor.addBlock("text", null, 0);
      editor.addBlock("text", null, 1);

      expect(editor.canUndo()).toBe(true);

      editor.setTemplate(createTestTemplate());

      expect(editor.canUndo()).toBe(false);

      editor.dispose();
    });

    it("should mark as saved after replacement", () => {
      const editor = createEditor();
      editor.addBlock("text", null, 0);

      expect(editor.isDirty()).toBe(true);

      editor.setTemplate(createTestTemplate());

      expect(editor.isDirty()).toBe(false);

      editor.dispose();
    });
  });

  describe("selection", () => {
    it("should set selected block ID", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;

      editor.setSelectedBlockId(block.id);

      expect(editor.getSelectedBlockId()).toBe(block.id);

      editor.dispose();
    });

    it("should clear selection with null", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;

      editor.setSelectedBlockId(block.id);
      editor.setSelectedBlockId(null);

      expect(editor.getSelectedBlockId()).toBeNull();

      editor.dispose();
    });
  });

  describe("subscriptions", () => {
    it("should notify onChange listeners when block is added", () => {
      const editor = createEditor();
      const listener = vi.fn();
      const unsubscribe = editor.onChange(listener);

      editor.addBlock("text", null, 0);

      expect(listener).toHaveBeenCalledWith(editor.getTemplate(), "block-added");

      unsubscribe();
      editor.dispose();
    });

    it("should notify onChange listeners when block is updated", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;
      const listener = vi.fn();
      const unsubscribe = editor.onChange(listener);

      editor.updateBlock(block.id, { styles: { color: "red" } });

      expect(listener).toHaveBeenCalledWith(editor.getTemplate(), "block-updated");

      unsubscribe();
      editor.dispose();
    });

    it("should notify onChange listeners when block is deleted", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;
      const listener = vi.fn();
      const unsubscribe = editor.onChange(listener);

      editor.deleteBlock(block.id);

      expect(listener).toHaveBeenCalledWith(editor.getTemplate(), "block-deleted");

      unsubscribe();
      editor.dispose();
    });

    it("should notify onChange listeners on undo", () => {
      const editor = createEditor();
      editor.addBlock("text", null, 0);
      const listener = vi.fn();
      const unsubscribe = editor.onChange(listener);

      editor.undo();

      expect(listener).toHaveBeenCalledWith(editor.getTemplate(), "undo");

      unsubscribe();
      editor.dispose();
    });

    it("should notify onChange listeners on redo", () => {
      const editor = createEditor();
      editor.addBlock("text", null, 0);
      editor.undo();
      const listener = vi.fn();
      const unsubscribe = editor.onChange(listener);

      editor.redo();

      expect(listener).toHaveBeenCalledWith(editor.getTemplate(), "redo");

      unsubscribe();
      editor.dispose();
    });

    it("should notify onChange listeners on selection change", () => {
      const editor = createEditor();
      const block = editor.addBlock("text", null, 0)!;
      const listener = vi.fn();
      const unsubscribe = editor.onChange(listener);

      editor.setSelectedBlockId(block.id);

      expect(listener).toHaveBeenCalledWith(editor.getTemplate(), "selection");

      unsubscribe();
      editor.dispose();
    });

    it("should stop notifying after unsubscribe", () => {
      const editor = createEditor();
      const listener = vi.fn();
      const unsubscribe = editor.onChange(listener);

      editor.addBlock("text", null, 0);
      expect(listener).toHaveBeenCalledTimes(1);

      unsubscribe();

      editor.addBlock("text", null, 1);
      expect(listener).toHaveBeenCalledTimes(1);

      editor.dispose();
    });
  });

  describe("disposal", () => {
    it("should throw after dispose", () => {
      const editor = createEditor();
      editor.dispose();

      expect(() => editor.getTemplate()).toThrow("Editor has been disposed");
    });

    it("should be safe to call dispose multiple times", () => {
      const editor = createEditor();

      editor.dispose();
      expect(() => editor.dispose()).not.toThrow();
    });
  });

  describe("batch operations", () => {
    it("should execute multiple commands as one operation", () => {
      const editor = createEditor();

      editor.addBlock("text", null, 0);
      editor.addBlock("text", null, 1);

      // Clear the history to test batch as single undo
      editor.clearHistory();

      editor.batch([
        { type: "UPDATE", execute: (s) => ({ ...s }), undo: (s) => s },
      ]);

      // Batch should be undoable as single operation
      expect(editor.canUndo()).toBe(true);

      editor.dispose();
    });
  });
});
