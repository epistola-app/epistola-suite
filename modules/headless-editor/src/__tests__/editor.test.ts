import { describe, it, expect, vi } from "vitest";
import { TemplateEditor } from "../editor";
import type { Template, Block } from "../types";

describe("TemplateEditor", () => {
  describe("initialization", () => {
    it("should create with default template", () => {
      const editor = new TemplateEditor();
      const state = editor.getState();

      expect(state.template.blocks).toEqual([]);
      expect(state.selectedBlockId).toBeNull();
    });

    it("should create with provided template", () => {
      const template: Template = {
        id: "custom-1",
        name: "Custom Template",
        blocks: [{ id: "block-1", type: "text", content: "Hello" }],
      };
      const editor = new TemplateEditor({ template });

      expect(editor.getTemplate()).toEqual(template);
    });

    it("should generate a unique ID for default template", () => {
      const editor1 = new TemplateEditor();
      const editor2 = new TemplateEditor();

      expect(editor1.getTemplate().id).not.toBe(editor2.getTemplate().id);
    });

    it("should default template name to Untitled", () => {
      const editor = new TemplateEditor();
      expect(editor.getTemplate().name).toBe("Untitled");
    });
  });

  describe("addBlock", () => {
    it("should add a text block at root", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text");

      expect(block).not.toBeNull();
      expect(block?.type).toBe("text");
      expect(editor.getTemplate().blocks).toHaveLength(1);
    });

    it("should add block to container", () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock("container")!;
      const child = editor.addBlock("text", container.id);

      expect(child).not.toBeNull();
      const updated = editor.findBlock(container.id) as { children: Block[] };
      expect(updated.children).toHaveLength(1);
    });

    it("should add block at a specific index", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text"); // index 0
      editor.addBlock("text"); // index 1
      const inserted = editor.addBlock("container", null, 1); // insert at index 1

      expect(inserted).not.toBeNull();
      expect(editor.getTemplate().blocks[1].id).toBe(inserted!.id);
      expect(editor.getTemplate().blocks).toHaveLength(3);
    });

    it("should append to end when index is -1", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");
      const second = editor.addBlock("text");

      expect(editor.getTemplate().blocks[1].id).toBe(second!.id);
    });

    it("should reject adding to non-container", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const text = editor.addBlock("text")!;

      const result = editor.addBlock("text", text.id);

      expect(result).toBeNull();
      expect(onError).toHaveBeenCalled();
    });

    it("should reject unknown block type", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });

      const result = editor.addBlock("unknown");

      expect(result).toBeNull();
      expect(onError).toHaveBeenCalled();
    });

    it("should respect pagebreak root-only constraint", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const container = editor.addBlock("container")!;

      const result = editor.addBlock("pagebreak", container.id);

      expect(result).toBeNull();
      expect(onError).toHaveBeenCalled();
    });

    it("should allow pagebreak at root", () => {
      const editor = new TemplateEditor();
      const pagebreak = editor.addBlock("pagebreak");

      expect(pagebreak).not.toBeNull();
      expect(pagebreak?.type).toBe("pagebreak");
    });

    it("should respect onBeforeBlockAdd callback returning false", () => {
      const editor = new TemplateEditor({
        callbacks: { onBeforeBlockAdd: () => false },
      });

      const result = editor.addBlock("text");

      expect(result).toBeNull();
      expect(editor.getTemplate().blocks).toHaveLength(0);
    });

    it("should reject adding to non-existent parent", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });

      const result = editor.addBlock("text", "non-existent-id");

      expect(result).toBeNull();
      expect(onError).toHaveBeenCalled();
    });

    it("should add block to a column", () => {
      const editor = new TemplateEditor();
      const columns = editor.addBlock("columns")! as {
        columns: { id: string }[];
      };
      const columnId = columns.columns[0].id;

      const child = editor.addBlock("text", columnId);

      expect(child).not.toBeNull();
    });

    it("should add block to a table cell", () => {
      const editor = new TemplateEditor();
      const table = editor.addBlock("table")! as {
        rows: { cells: { id: string }[] }[];
      };
      const cellId = table.rows[0].cells[0].id;

      const child = editor.addBlock("text", cellId);

      expect(child).not.toBeNull();
    });
  });

  describe("updateBlock", () => {
    it("should update block properties", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;

      editor.updateBlock(block.id, { content: "Updated" });

      const updated = editor.findBlock(block.id) as { content: string };
      expect(updated.content).toBe("Updated");
    });

    it("should update styles on a block", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;

      editor.updateBlock(block.id, { styles: { color: "red", fontSize: 16 } });

      const updated = editor.findBlock(block.id) as {
        styles: Record<string, unknown>;
      };
      expect(updated.styles.color).toBe("red");
      expect(updated.styles.fontSize).toBe(16);
    });
  });

  describe("deleteBlock", () => {
    it("should delete a block", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;

      const result = editor.deleteBlock(block.id);

      expect(result).toBe(true);
      expect(editor.getTemplate().blocks).toHaveLength(0);
    });

    it("should clear selection when deleted block was selected", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;
      editor.selectBlock(block.id);

      editor.deleteBlock(block.id);

      expect(editor.getState().selectedBlockId).toBeNull();
    });

    it("should respect onBeforeBlockDelete callback returning false", () => {
      const editor = new TemplateEditor({
        callbacks: { onBeforeBlockDelete: () => false },
      });
      const block = editor.addBlock("text")!;

      const result = editor.deleteBlock(block.id);

      expect(result).toBe(false);
      expect(editor.getTemplate().blocks).toHaveLength(1);
    });

    it("should delete nested block", () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock("container")!;
      const child = editor.addBlock("text", container.id)!;

      editor.deleteBlock(child.id);

      const updated = editor.findBlock(container.id) as { children: Block[] };
      expect(updated.children).toHaveLength(0);
    });
  });

  describe("moveBlock", () => {
    it("should move block to new position", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text"); // block-1
      editor.addBlock("text"); // block-2

      const blocks = editor.getTemplate().blocks;
      const block1Id = blocks[0].id;
      const block2Id = blocks[1].id;

      editor.moveBlock(block2Id, null, 0);

      const reordered = editor.getTemplate().blocks;
      expect(reordered[0].id).toBe(block2Id);
      expect(reordered[1].id).toBe(block1Id);
    });

    it("should move block into a container", () => {
      const editor = new TemplateEditor();
      const text = editor.addBlock("text")!;
      const container = editor.addBlock("container")!;

      editor.moveBlock(text.id, container.id, 0);

      expect(editor.getTemplate().blocks).toHaveLength(1);
      const updated = editor.findBlock(container.id) as { children: Block[] };
      expect(updated.children).toHaveLength(1);
      expect(updated.children[0].id).toBe(text.id);
    });
  });

  describe("findBlock", () => {
    it("should find a block by ID", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;

      const found = editor.findBlock(block.id);

      expect(found).not.toBeNull();
      expect(found!.id).toBe(block.id);
    });

    it("should return null for non-existent block", () => {
      const editor = new TemplateEditor();

      expect(editor.findBlock("non-existent")).toBeNull();
    });
  });

  describe("selection", () => {
    it("should select a block", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;

      editor.selectBlock(block.id);

      expect(editor.getState().selectedBlockId).toBe(block.id);
      expect(editor.getSelectedBlock()).toEqual(block);
    });

    it("should clear selection", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;
      editor.selectBlock(block.id);

      editor.selectBlock(null);

      expect(editor.getState().selectedBlockId).toBeNull();
    });

    it("should return null from getSelectedBlock when nothing selected", () => {
      const editor = new TemplateEditor();

      expect(editor.getSelectedBlock()).toBeNull();
    });
  });

  describe("template operations", () => {
    it("should set template", () => {
      const editor = new TemplateEditor();
      const newTemplate: Template = {
        id: "new-1",
        name: "New Template",
        blocks: [{ id: "b-1", type: "text", content: "Hello" }],
      };

      editor.setTemplate(newTemplate);

      expect(editor.getTemplate()).toEqual(newTemplate);
    });

    it("should update template metadata", () => {
      const editor = new TemplateEditor();

      editor.updateTemplate({ name: "Updated Name" });

      expect(editor.getTemplate().name).toBe("Updated Name");
    });
  });

  describe("undo/redo", () => {
    it("should undo block addition", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");

      expect(editor.canUndo()).toBe(true);

      editor.undo();

      expect(editor.getTemplate().blocks).toHaveLength(0);
    });

    it("should redo after undo", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");
      editor.undo();

      expect(editor.canRedo()).toBe(true);

      editor.redo();

      expect(editor.getTemplate().blocks).toHaveLength(1);
    });

    it("should not undo when no history", () => {
      const editor = new TemplateEditor();

      const result = editor.undo();

      expect(result).toBe(false);
    });

    it("should not redo when no future", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");

      const result = editor.redo();

      expect(result).toBe(false);
    });

    it("should undo block deletion", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;
      editor.deleteBlock(block.id);

      editor.undo();

      expect(editor.getTemplate().blocks).toHaveLength(1);
    });

    it("should undo block move", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");
      editor.addBlock("text");

      const blocks = editor.getTemplate().blocks;
      const firstId = blocks[0].id;

      editor.moveBlock(firstId, null, 1);
      expect(editor.getTemplate().blocks[0].id).not.toBe(firstId);

      editor.undo();
      expect(editor.getTemplate().blocks[0].id).toBe(firstId);
    });
  });

  describe("subscribe", () => {
    it("should notify on template change", () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();
      editor.subscribe(callback);

      editor.addBlock("text");

      expect(callback).toHaveBeenCalled();
    });

    it("should notify on selection change", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;
      const callback = vi.fn();
      editor.subscribe(callback);

      editor.selectBlock(block.id);

      expect(callback).toHaveBeenCalled();
    });

    it("should unsubscribe correctly", () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();
      const unsubscribe = editor.subscribe(callback);

      unsubscribe();
      const callCountAfterUnsub = callback.mock.calls.length;
      editor.addBlock("text");

      expect(callback.mock.calls.length).toBe(callCountAfterUnsub);
    });

    it("should expose nanostores via getStores", () => {
      const editor = new TemplateEditor();
      const stores = editor.getStores();

      expect(stores.$template).toBeDefined();
      expect(stores.$selectedBlockId).toBeDefined();
      expect(typeof stores.$template.get).toBe("function");
      expect(typeof stores.$selectedBlockId.get).toBe("function");
    });
  });

  describe("validation", () => {
    it("should validate a valid template", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");

      const result = editor.validateTemplate();

      expect(result.valid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });

    it("should detect unknown block types", () => {
      const editor = new TemplateEditor();
      // Manually set a template with an unknown block type
      editor.setTemplate({
        id: "test",
        name: "Test",
        blocks: [{ id: "bad-1", type: "unknown_type", content: "" } as Block],
      });

      const result = editor.validateTemplate();

      expect(result.valid).toBe(false);
      expect(result.errors).toContain("Unknown block type: unknown_type");
    });

    it("should validate nested blocks in container", () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock("container")!;
      editor.addBlock("text", container.id);

      const result = editor.validateTemplate();
      expect(result.valid).toBe(true);
    });
  });

  describe("serialization", () => {
    it("should export to JSON", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");

      const json = editor.exportJSON();
      const parsed = JSON.parse(json);

      expect(parsed.blocks).toHaveLength(1);
    });

    it("should import from JSON", () => {
      const editor = new TemplateEditor();
      const template: Template = {
        id: "imported",
        name: "Imported Template",
        blocks: [{ id: "block-1", type: "text", content: "Imported" }],
      };

      editor.importJSON(JSON.stringify(template));

      expect(editor.getTemplate().name).toBe("Imported Template");
    });

    it("should handle invalid JSON gracefully", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });

      editor.importJSON("not valid json {{{");

      expect(onError).toHaveBeenCalled();
    });

    it("should roundtrip through JSON serialization", () => {
      const editor = new TemplateEditor();
      editor.addBlock("container");
      editor.addBlock("text");

      const json = editor.exportJSON();
      const editor2 = new TemplateEditor();
      editor2.importJSON(json);

      expect(editor2.getTemplate()).toEqual(editor.getTemplate());
    });
  });

  describe("drag and drop", () => {
    it("should check if block can be dragged", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text")!;

      expect(editor.canDrag(block.id)).toBe(true);
    });

    it("should return false for non-existent block drag check", () => {
      const editor = new TemplateEditor();

      expect(editor.canDrag("non-existent")).toBe(false);
    });

    it("should check if block can be dropped", () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock("container")!;
      const text = editor.addBlock("text")!;

      // Can drop text inside container
      expect(editor.canDrop(text.id, container.id, "inside")).toBe(true);

      // Cannot drop on itself
      expect(editor.canDrop(text.id, text.id, "inside")).toBe(false);
    });

    it("should prevent dropping pagebreak inside container", () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock("container")!;
      const pagebreak = editor.addBlock("pagebreak")!;

      expect(editor.canDrop(pagebreak.id, container.id, "inside")).toBe(false);
    });

    it("should prevent dropping inside own descendants", () => {
      const editor = new TemplateEditor();
      const outer = editor.addBlock("container")!;
      const inner = editor.addBlock("container", outer.id)!;

      // Can't drop outer inside its own child inner
      expect(editor.canDrop(outer.id, inner.id, "inside")).toBe(false);
    });

    it("should allow dropping before/after sibling at root", () => {
      const editor = new TemplateEditor();
      const block1 = editor.addBlock("text")!;
      const block2 = editor.addBlock("text")!;

      expect(editor.canDrop(block1.id, block2.id, "before")).toBe(true);
      expect(editor.canDrop(block1.id, block2.id, "after")).toBe(true);
    });

    it("should prevent dropping non-existent block", () => {
      const editor = new TemplateEditor();
      editor.addBlock("text");

      expect(editor.canDrop("non-existent", null, "inside")).toBe(false);
    });

    it("should prevent drop at non-existent target", () => {
      const editor = new TemplateEditor();
      const text = editor.addBlock("text")!;

      expect(editor.canDrop(text.id, "non-existent", "inside")).toBe(false);
    });

    it("should get drop zones", () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock("container")!;
      const text = editor.addBlock("text")!;

      const zones = editor.getDropZones(text.id);

      // Should have zones for root, container, etc.
      expect(zones.length).toBeGreaterThan(0);
      expect(
        zones.some(
          (z) => z.targetId === container.id && z.position === "inside",
        ),
      ).toBe(true);
    });

    it("should provide DragDropPort interface", () => {
      const editor = new TemplateEditor();
      const port = editor.getDragDropPort();

      expect(typeof port.canDrag).toBe("function");
      expect(typeof port.canDrop).toBe("function");
      expect(typeof port.getDropZones).toBe("function");
      expect(typeof port.drop).toBe("function");
    });

    it("should execute drop via port", () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock("container")!;
      const text = editor.addBlock("text")!;
      const port = editor.getDragDropPort();

      port.drop(text.id, container.id, 0);

      expect(editor.getTemplate().blocks).toHaveLength(1);
      const updated = editor.findBlock(container.id) as { children: Block[] };
      expect(updated.children).toHaveLength(1);
    });

    it("should reject invalid drop via port", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const text = editor.addBlock("text")!;
      const port = editor.getDragDropPort();

      // text cannot have children, so drop inside text is invalid
      port.drop(text.id, text.id, 0);

      expect(onError).toHaveBeenCalled();
    });
  });

  describe("columns operations", () => {
    it("should create columns block with 2 default columns", () => {
      const editor = new TemplateEditor();
      const columns = editor.addBlock("columns")!;

      expect(columns.type).toBe("columns");
      expect((columns as { columns: unknown[] }).columns).toHaveLength(2);
    });

    it("should add column to columns block", () => {
      const editor = new TemplateEditor();
      const columns = editor.addBlock("columns")!;

      editor.addColumn(columns.id);

      const updated = editor.findBlock(columns.id) as { columns: unknown[] };
      expect(updated.columns).toHaveLength(3);
    });

    it("should limit to 6 columns", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const columns = editor.addBlock("columns")!;

      // Add 4 more columns (start with 2)
      editor.addColumn(columns.id);
      editor.addColumn(columns.id);
      editor.addColumn(columns.id);
      editor.addColumn(columns.id);

      // Try to add 7th column
      editor.addColumn(columns.id);

      expect(onError).toHaveBeenCalled();
      const updated = editor.findBlock(columns.id) as { columns: unknown[] };
      expect(updated.columns).toHaveLength(6);
    });

    it("should reject addColumn on non-columns block", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const text = editor.addBlock("text")!;

      editor.addColumn(text.id);

      expect(onError).toHaveBeenCalled();
    });

    it("should remove a column", () => {
      const editor = new TemplateEditor();
      const columns = editor.addBlock("columns")! as {
        columns: { id: string }[];
      };
      const columnToRemove = columns.columns[0].id;

      editor.removeColumn(columns.id, columnToRemove);

      const updated = editor.findBlock(columns.id) as {
        columns: { id: string }[];
      };
      expect(updated.columns).toHaveLength(1);
      expect(updated.columns[0].id).not.toBe(columnToRemove);
    });

    it("should reject removing last column", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const columns = editor.addBlock("columns")! as {
        columns: { id: string }[];
      };

      // Remove first column leaving one
      editor.removeColumn(columns.id, columns.columns[0].id);
      onError.mockClear();

      // Try to remove the last one
      const updated = editor.findBlock(columns.id) as {
        columns: { id: string }[];
      };
      editor.removeColumn(columns.id, updated.columns[0].id);

      expect(onError).toHaveBeenCalled();
    });

    it("should reject removeColumn on non-columns block", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const text = editor.addBlock("text")!;

      editor.removeColumn(text.id, "any-col");

      expect(onError).toHaveBeenCalled();
    });
  });

  describe("table operations", () => {
    it("should create table block with default structure", () => {
      const editor = new TemplateEditor();
      const table = editor.addBlock("table")!;

      expect(table.type).toBe("table");
      const tableBlock = table as { rows: { cells: unknown[] }[] };
      expect(tableBlock.rows).toHaveLength(3); // header + 2 data rows
      expect(tableBlock.rows[0].cells).toHaveLength(3);
    });

    it("should add row to table", () => {
      const editor = new TemplateEditor();
      const table = editor.addBlock("table")!;

      editor.addRow(table.id);

      const updated = editor.findBlock(table.id) as { rows: unknown[] };
      expect(updated.rows).toHaveLength(4);
    });

    it("should add header row to table", () => {
      const editor = new TemplateEditor();
      const table = editor.addBlock("table")!;

      editor.addRow(table.id, true);

      const updated = editor.findBlock(table.id) as {
        rows: { isHeader?: boolean }[];
      };
      expect(updated.rows[3].isHeader).toBe(true);
    });

    it("should reject addRow on non-table block", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const text = editor.addBlock("text")!;

      editor.addRow(text.id);

      expect(onError).toHaveBeenCalled();
    });

    it("should remove a row from table", () => {
      const editor = new TemplateEditor();
      const table = editor.addBlock("table")! as { rows: { id: string }[] };
      const rowToRemove = table.rows[1].id;

      editor.removeRow(table.id, rowToRemove);

      const updated = editor.findBlock(table.id) as { rows: { id: string }[] };
      expect(updated.rows).toHaveLength(2);
      expect(updated.rows.every((r) => r.id !== rowToRemove)).toBe(true);
    });

    it("should reject removing last row", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const table = editor.addBlock("table")! as { rows: { id: string }[] };

      // Remove rows until one left
      editor.removeRow(table.id, table.rows[2].id);
      editor.removeRow(table.id, table.rows[1].id);
      onError.mockClear();

      // Try to remove the last one
      const updated = editor.findBlock(table.id) as { rows: { id: string }[] };
      editor.removeRow(table.id, updated.rows[0].id);

      expect(onError).toHaveBeenCalled();
    });

    it("should reject removeRow on non-table block", () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const text = editor.addBlock("text")!;

      editor.removeRow(text.id, "any-row");

      expect(onError).toHaveBeenCalled();
    });
  });

  describe("block registry", () => {
    it("should list all block types", () => {
      const editor = new TemplateEditor();
      const types = editor.getBlockTypes();

      expect(types).toContain("text");
      expect(types).toContain("container");
      expect(types).toContain("columns");
      expect(types).toContain("table");
      expect(types).toContain("pagebreak");
      expect(types).toContain("pageheader");
      expect(types).toContain("pagefooter");
      expect(types).toContain("conditional");
      expect(types).toContain("loop");
    });

    it("should get block definition", () => {
      const editor = new TemplateEditor();
      const def = editor.getBlockDefinition("text");

      expect(def).toBeDefined();
      expect(def?.type).toBe("text");
    });

    it("should return undefined for unknown block definition", () => {
      const editor = new TemplateEditor();

      expect(editor.getBlockDefinition("unknown")).toBeUndefined();
    });

    it("should register a custom block type", () => {
      const editor = new TemplateEditor();

      editor.registerBlock({
        type: "custom-banner",
        create: (id) =>
          ({ id, type: "custom-banner" as const, content: "Banner" }) as Block,
        validate: () => ({ valid: true, errors: [] }),
        constraints: {
          canHaveChildren: false,
          allowedChildTypes: [],
          canBeDragged: true,
          canBeNested: true,
          allowedParentTypes: null,
        },
      });

      expect(editor.getBlockTypes()).toContain("custom-banner");
      expect(editor.getBlockDefinition("custom-banner")).toBeDefined();
    });

    it("should use custom block definitions from config", () => {
      const editor = new TemplateEditor({
        blocks: {
          myblock: {
            type: "myblock",
            create: (id) => ({ id, type: "myblock" as const }) as Block,
            validate: () => ({ valid: true, errors: [] }),
            constraints: {
              canHaveChildren: false,
              allowedChildTypes: [],
              canBeDragged: true,
              canBeNested: true,
              allowedParentTypes: null,
            },
          },
        },
      });

      expect(editor.getBlockTypes()).toContain("myblock");
      // Default blocks are not present when custom blocks provided
      expect(editor.getBlockTypes()).not.toContain("text");
    });
  });

  describe("callbacks", () => {
    it("should call onTemplateChange on block addition", () => {
      const onTemplateChange = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onTemplateChange } });

      editor.addBlock("text");

      expect(onTemplateChange).toHaveBeenCalled();
    });

    it("should call onBlockSelect on selection change", () => {
      const onBlockSelect = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onBlockSelect } });
      const block = editor.addBlock("text")!;

      editor.selectBlock(block.id);

      expect(onBlockSelect).toHaveBeenCalledWith(block.id);
    });
  });
});
