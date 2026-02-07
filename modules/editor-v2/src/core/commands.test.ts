import { describe, it, expect, beforeEach } from "vitest";
import {
  findBlock,
  findBlockLocation,
  updateBlock,
  insertBlock,
  removeBlock,
  getChildren,
  AddBlockCommand,
  UpdateBlockCommand,
  DeleteBlockCommand,
  MoveBlockCommand,
  UpdateDocumentStylesCommand,
  UpdatePageSettingsCommand,
  CompositeCommand,
} from "./commands.ts";
import { registry, registerBlock } from "../blocks/index.ts";
import type { BlockDefinition, MultiContainerBlockDefinition } from "../blocks/types.ts";
import type { Block, Template, TextBlock, ContainerBlock, ColumnsBlock } from "../types/template.ts";
import { createEmptyDocument } from "../types/richtext.ts";

// ============================================================================
// Test Block Definitions (must register before tests)
// ============================================================================

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

const columnsDef: MultiContainerBlockDefinition<ColumnsBlock> = {
  type: "columns",
  label: "Columns",
  category: "structure",
  icon: "",
  createDefault: () => ({
    id: crypto.randomUUID(),
    type: "columns",
    columns: [
      { id: "col-1", size: 1, children: [] },
      { id: "col-2", size: 1, children: [] },
    ],
  }),
  getChildren: (block) => block.columns.flatMap((col) => col.children),
  getContainers: (block) =>
    block.columns.map((col) => ({
      blockId: block.id,
      containerId: col.id,
    })),
  getContainerChildren: (block, containerId) =>
    block.columns.find((col) => col.id === containerId)?.children ?? [],
  setContainerChildren: (block, containerId, children) => ({
    ...block,
    columns: block.columns.map((col) =>
      col.id === containerId ? { ...col, children } : col,
    ),
  }),
};

function createTestTemplate(blocks: Block[] = []): Template {
  return {
    id: "test-template",
    name: "Test Template",
    version: 1,
    pageSettings: {
      format: "A4",
      orientation: "portrait",
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    blocks,
    documentStyles: {},
  };
}

function createTextBlock(id: string): TextBlock {
  return {
    id,
    type: "text",
    content: createEmptyDocument(),
  };
}

function createContainerBlock(id: string, children: Block[] = []): ContainerBlock {
  return {
    id,
    type: "container",
    children,
  };
}

function createColumnsBlock(id: string): ColumnsBlock {
  return {
    id,
    type: "columns",
    columns: [
      { id: "col-1", size: 1, children: [] },
      { id: "col-2", size: 1, children: [] },
    ],
  };
}

describe("Block Tree Operations", () => {
  beforeEach(() => {
    registry.clear();
    registerBlock(containerDef);
    registerBlock(textDef);
    registerBlock(columnsDef);
  });

  describe("getChildren", () => {
    it("should return children from container block", () => {
      const child = createTextBlock("child");
      const container = createContainerBlock("container", [child]);
      expect(getChildren(container)).toEqual([child]);
    });

    it("should return empty array for leaf blocks", () => {
      const text = createTextBlock("text");
      expect(getChildren(text)).toEqual([]);
    });

    it("should flatten children from columns", () => {
      const block: ColumnsBlock = {
        id: "cols",
        type: "columns",
        columns: [
          { id: "col-1", size: 1, children: [createTextBlock("t1")] },
          { id: "col-2", size: 1, children: [createTextBlock("t2")] },
        ],
      };
      expect(getChildren(block)).toHaveLength(2);
    });
  });

  describe("findBlock", () => {
    it("should find block at root level", () => {
      const block = createTextBlock("target");
      const result = findBlock([block], "target");
      expect(result).toBe(block);
    });

    it("should find nested block", () => {
      const nested = createTextBlock("nested");
      const container = createContainerBlock("container", [nested]);
      const result = findBlock([container], "nested");
      expect(result).toBe(nested);
    });

    it("should return undefined for non-existent block", () => {
      const block = createTextBlock("other");
      const result = findBlock([block], "target");
      expect(result).toBeUndefined();
    });

    it("should find block in columns", () => {
      const colBlock: ColumnsBlock = {
        id: "cols",
        type: "columns",
        columns: [
          { id: "col-1", size: 1, children: [createTextBlock("in-col")] },
          { id: "col-2", size: 1, children: [] },
        ],
      };
      const result = findBlock([colBlock], "in-col");
      expect(result?.id).toBe("in-col");
    });
  });

  describe("findBlockLocation", () => {
    it("should return location with parent info", () => {
      const child = createTextBlock("child");
      const container = createContainerBlock("container", [child]);
      const location = findBlockLocation([container], "child");

      expect(location).toBeDefined();
      expect(location?.block).toBe(child);
      expect(location?.parent).toBe(container);
      expect(location?.index).toBe(0);
    });

    it("should return null parent for root blocks", () => {
      const block = createTextBlock("root");
      const location = findBlockLocation([block], "root");

      expect(location?.parent).toBeNull();
      expect(location?.containerId).toBeNull();
    });

    it("should return containerId for column children", () => {
      const child = createTextBlock("in-col");
      const colBlock: ColumnsBlock = {
        id: "cols",
        type: "columns",
        columns: [
          { id: "col-1", size: 1, children: [child] },
          { id: "col-2", size: 1, children: [] },
        ],
      };
      const location = findBlockLocation([colBlock], "in-col");

      expect(location?.containerId).toBe("col-1");
      expect(location?.parent?.id).toBe("cols");
    });
  });

  describe("updateBlock", () => {
    it("should update block at root level", () => {
      const block = createTextBlock("target");
      const result = updateBlock([block], "target", (b) => ({
        ...b,
        styles: { color: "red" },
      }));

      expect(result[0].styles).toEqual({ color: "red" });
    });

    it("should update nested block", () => {
      const nested = createTextBlock("nested");
      const container = createContainerBlock("container", [nested]);
      const result = updateBlock([container], "nested", (b) => ({
        ...b,
        styles: { color: "blue" },
      }));

      const updatedContainer = result[0] as ContainerBlock;
      expect(updatedContainer.children[0].styles).toEqual({ color: "blue" });
    });

    it("should remove block when updater returns null", () => {
      const block1 = createTextBlock("keep");
      const block2 = createTextBlock("remove");
      const result = updateBlock([block1, block2], "remove", () => null);

      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("keep");
    });
  });

  describe("insertBlock", () => {
    it("should insert at root level", () => {
      const existing = createTextBlock("existing");
      const newBlock = createTextBlock("new");
      const result = insertBlock([existing], newBlock, null, 0);

      expect(result).toHaveLength(2);
      expect(result[0].id).toBe("new");
      expect(result[1].id).toBe("existing");
    });

    it("should insert into container", () => {
      const container = createContainerBlock("container", [createTextBlock("child")]);
      const newBlock = createTextBlock("new");
      const result = insertBlock([container], newBlock, "container", 0);

      const updatedContainer = result[0] as ContainerBlock;
      expect(updatedContainer.children).toHaveLength(2);
      expect(updatedContainer.children[0].id).toBe("new");
    });

    it("should insert into column using composite ID", () => {
      const colBlock = createColumnsBlock("cols");
      const newBlock = createTextBlock("new");
      const result = insertBlock([colBlock], newBlock, "cols::col-1", 0);

      const updatedCols = result[0] as ColumnsBlock;
      expect(updatedCols.columns[0].children).toHaveLength(1);
      expect(updatedCols.columns[0].children[0].id).toBe("new");
    });
  });

  describe("removeBlock", () => {
    it("should remove from root level", () => {
      const block = createTextBlock("remove");
      const result = removeBlock([block], "remove");
      expect(result).toHaveLength(0);
    });

    it("should remove from nested structure", () => {
      const nested = createTextBlock("remove");
      const container = createContainerBlock("container", [nested]);
      const result = removeBlock([container], "remove");

      const updatedContainer = result[0] as ContainerBlock;
      expect(updatedContainer.children).toHaveLength(0);
    });
  });
});

describe("Block Commands", () => {
  beforeEach(() => {
    registry.clear();
    registerBlock(containerDef);
    registerBlock(textDef);
    registerBlock(columnsDef);
  });

  describe("AddBlockCommand", () => {
    it("should add block at root level", () => {
      const template = createTestTemplate();
      const block = createTextBlock("new");
      const command = new AddBlockCommand(block, null, 0);

      const result = command.execute(template);

      expect(result.blocks).toHaveLength(1);
      expect(result.blocks[0].id).toBe("new");
    });

    it("should undo by removing the block", () => {
      const template = createTestTemplate();
      const block = createTextBlock("new");
      const command = new AddBlockCommand(block, null, 0);

      const withBlock = command.execute(template);
      const undone = command.undo(withBlock);

      expect(undone.blocks).toHaveLength(0);
    });

    it("should add block into container", () => {
      const container = createContainerBlock("container");
      const template = createTestTemplate([container]);
      const block = createTextBlock("new");
      const command = new AddBlockCommand(block, "container", 0);

      const result = command.execute(template);

      const updatedContainer = result.blocks[0] as ContainerBlock;
      expect(updatedContainer.children).toHaveLength(1);
    });
  });

  describe("UpdateBlockCommand", () => {
    it("should update block properties", () => {
      const block = createTextBlock("target");
      const template = createTestTemplate([block]);
      const command = new UpdateBlockCommand("target", { styles: { color: "red" } });

      const result = command.execute(template);

      expect(result.blocks[0].styles).toEqual({ color: "red" });
    });

    it("should undo to previous state", () => {
      const block: TextBlock = { ...createTextBlock("target"), styles: { color: "blue" } };
      const template = createTestTemplate([block]);
      const command = new UpdateBlockCommand("target", { styles: { color: "red" } });

      const updated = command.execute(template);
      const undone = command.undo(updated);

      expect(undone.blocks[0].styles).toEqual({ color: "blue" });
    });

    it("should no-op for non-existent block", () => {
      const template = createTestTemplate();
      const command = new UpdateBlockCommand("nonexistent", { styles: {} });

      const result = command.execute(template);

      expect(result).toBe(template);
    });
  });

  describe("DeleteBlockCommand", () => {
    it("should delete block", () => {
      const block = createTextBlock("delete-me");
      const template = createTestTemplate([block]);
      const command = new DeleteBlockCommand("delete-me");

      const result = command.execute(template);

      expect(result.blocks).toHaveLength(0);
    });

    it("should undo by restoring block at original position", () => {
      const block1 = createTextBlock("keep-1");
      const block2 = createTextBlock("delete-me");
      const block3 = createTextBlock("keep-2");
      const template = createTestTemplate([block1, block2, block3]);
      const command = new DeleteBlockCommand("delete-me");

      const deleted = command.execute(template);
      expect(deleted.blocks).toHaveLength(2);

      const undone = command.undo(deleted);
      expect(undone.blocks).toHaveLength(3);
      expect(undone.blocks[1].id).toBe("delete-me");
    });
  });

  describe("MoveBlockCommand", () => {
    it("should move block to new position", () => {
      const block1 = createTextBlock("first");
      const block2 = createTextBlock("second");
      const template = createTestTemplate([block1, block2]);
      const command = new MoveBlockCommand("second", null, 0);

      const result = command.execute(template);

      expect(result.blocks[0].id).toBe("second");
      expect(result.blocks[1].id).toBe("first");
    });

    it("should undo move", () => {
      const block1 = createTextBlock("first");
      const block2 = createTextBlock("second");
      const template = createTestTemplate([block1, block2]);
      const command = new MoveBlockCommand("second", null, 0);

      const moved = command.execute(template);
      const undone = command.undo(moved);

      expect(undone.blocks[0].id).toBe("first");
      expect(undone.blocks[1].id).toBe("second");
    });

    it("should move block into container", () => {
      const container = createContainerBlock("container");
      const block = createTextBlock("move-me");
      const template = createTestTemplate([container, block]);
      const command = new MoveBlockCommand("move-me", "container", 0);

      const result = command.execute(template);

      expect(result.blocks).toHaveLength(1);
      const updatedContainer = result.blocks[0] as ContainerBlock;
      expect(updatedContainer.children).toHaveLength(1);
      expect(updatedContainer.children[0].id).toBe("move-me");
    });
  });
});

describe("Document Commands", () => {
  describe("UpdateDocumentStylesCommand", () => {
    it("should update document styles", () => {
      const template = createTestTemplate();
      const command = new UpdateDocumentStylesCommand({ fontFamily: "Arial" });

      const result = command.execute(template);

      expect(result.documentStyles.fontFamily).toBe("Arial");
    });

    it("should merge with existing styles", () => {
      const template = createTestTemplate();
      template.documentStyles = { fontSize: "12px" };
      const command = new UpdateDocumentStylesCommand({ fontFamily: "Arial" });

      const result = command.execute(template);

      expect(result.documentStyles.fontSize).toBe("12px");
      expect(result.documentStyles.fontFamily).toBe("Arial");
    });

    it("should undo to previous styles", () => {
      const template = createTestTemplate();
      template.documentStyles = { fontSize: "12px" };
      const command = new UpdateDocumentStylesCommand({ fontSize: "14px" });

      const updated = command.execute(template);
      const undone = command.undo(updated);

      expect(undone.documentStyles.fontSize).toBe("12px");
    });
  });

  describe("UpdatePageSettingsCommand", () => {
    it("should update page settings", () => {
      const template = createTestTemplate();
      const command = new UpdatePageSettingsCommand({ format: "Letter" });

      const result = command.execute(template);

      expect(result.pageSettings.format).toBe("Letter");
      expect(result.pageSettings.orientation).toBe("portrait"); // unchanged
    });

    it("should undo to previous settings", () => {
      const template = createTestTemplate();
      const command = new UpdatePageSettingsCommand({ orientation: "landscape" });

      const updated = command.execute(template);
      const undone = command.undo(updated);

      expect(undone.pageSettings.orientation).toBe("portrait");
    });
  });
});

describe("CompositeCommand", () => {
  it("should execute multiple commands", () => {
    const template = createTestTemplate();
    const block = createTextBlock("new");
    const composite = new CompositeCommand([
      new AddBlockCommand(block, null, 0),
      new UpdateDocumentStylesCommand({ fontFamily: "Arial" }),
    ]);

    const result = composite.execute(template);

    expect(result.blocks).toHaveLength(1);
    expect(result.documentStyles.fontFamily).toBe("Arial");
  });

  it("should undo in reverse order", () => {
    const block = createTextBlock("existing");
    const template = createTestTemplate([block]);
    const composite = new CompositeCommand([
      new UpdateBlockCommand("existing", { styles: { color: "red" } }),
      new UpdateBlockCommand("existing", { styles: { color: "blue" } }),
    ]);

    const executed = composite.execute(template);
    expect(executed.blocks[0].styles).toEqual({ color: "blue" });

    // Note: Due to how UpdateBlockCommand stores previousBlock,
    // composite undo may not perfectly restore intermediate states.
    // This tests the reverse order execution.
    const undone = composite.undo(executed);
    expect(undone.blocks[0].styles).toBeUndefined();
  });
});
