import { describe, it, expect } from "vitest";
import { BlockTree, createEditorStore } from "../store";
import type { Block, Template } from "../types";

describe("createEditorStore", () => {
  it("should create a store with initial template", () => {
    const template: Template = {
      id: "test-1",
      name: "Test Template",
      blocks: [],
    };
    const store = createEditorStore(template);

    expect(store.getTemplate()).toEqual(template);
    expect(store.getSelectedBlockId()).toBeNull();
  });

  it("should update template", () => {
    const template: Template = {
      id: "test-1",
      name: "Test Template",
      blocks: [],
    };
    const store = createEditorStore(template);

    const updated: Template = { ...template, name: "Updated" };
    store.setTemplate(updated);

    expect(store.getTemplate().name).toBe("Updated");
  });

  it("should update selected block ID", () => {
    const template: Template = {
      id: "test-1",
      name: "Test Template",
      blocks: [],
    };
    const store = createEditorStore(template);

    store.setSelectedBlockId("block-1");
    expect(store.getSelectedBlockId()).toBe("block-1");

    store.setSelectedBlockId(null);
    expect(store.getSelectedBlockId()).toBeNull();
  });

  it("should notify subscribers on template change", () => {
    const template: Template = { id: "test-1", name: "Test", blocks: [] };
    const store = createEditorStore(template);
    let notified = false;
    // nanostores calls subscriber immediately with current value, then on changes
    let callCount = 0;
    store.subscribeTemplate(() => {
      callCount++;
      if (callCount > 1) notified = true;
    });

    store.setTemplate({ ...template, name: "Changed" });

    expect(notified).toBe(true);
  });

  it("should notify subscribers on selectedBlockId change", () => {
    const template: Template = { id: "test-1", name: "Test", blocks: [] };
    const store = createEditorStore(template);
    let notified = false;
    let callCount = 0;
    store.subscribeSelectedBlockId(() => {
      callCount++;
      if (callCount > 1) notified = true;
    });

    store.setSelectedBlockId("block-1");

    expect(notified).toBe(true);
  });
});

describe("BlockTree", () => {
  describe("findBlock", () => {
    it("should find a block at root level", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "Hello" },
        { id: "block-2", type: "text", content: "World" },
      ];

      const result = BlockTree.findBlock(blocks, "block-1");
      expect(result).toEqual(blocks[0]);
    });

    it("should find a nested block in children", () => {
      const blocks: Block[] = [
        {
          id: "container-1",
          type: "container",
          children: [{ id: "nested-1", type: "text", content: "Nested" }],
        },
      ];

      const result = BlockTree.findBlock(blocks, "nested-1");
      expect(result).toEqual({
        id: "nested-1",
        type: "text",
        content: "Nested",
      });
    });

    it("should find a block in columns", () => {
      const blocks: Block[] = [
        {
          id: "columns-1",
          type: "columns",
          columns: [
            {
              id: "col-1",
              size: 1,
              children: [
                { id: "in-col-1", type: "text", content: "In column" },
              ],
            },
          ],
        },
      ];

      const result = BlockTree.findBlock(blocks, "in-col-1");
      expect(result).toEqual({
        id: "in-col-1",
        type: "text",
        content: "In column",
      });
    });

    it("should find a block in table cells", () => {
      const blocks: Block[] = [
        {
          id: "table-1",
          type: "table",
          rows: [
            {
              id: "row-1",
              cells: [
                {
                  id: "cell-1",
                  children: [
                    { id: "in-cell-1", type: "text", content: "In cell" },
                  ],
                },
              ],
            },
          ],
        },
      ];

      const result = BlockTree.findBlock(blocks, "in-cell-1");
      expect(result).toEqual({
        id: "in-cell-1",
        type: "text",
        content: "In cell",
      });
    });

    it("should return null for non-existent block", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "Hello" },
      ];

      const result = BlockTree.findBlock(blocks, "non-existent");
      expect(result).toBeNull();
    });

    it("should return null for empty block array", () => {
      expect(BlockTree.findBlock([], "anything")).toBeNull();
    });
  });

  describe("findParent", () => {
    it("should return null for root-level block", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "Hello" },
      ];

      const result = BlockTree.findParent(blocks, "block-1");
      expect(result).toBeNull();
    });

    it("should find parent of nested block", () => {
      const container: Block = {
        id: "container-1",
        type: "container",
        children: [{ id: "nested-1", type: "text", content: "Nested" }],
      };
      const blocks: Block[] = [container];

      const result = BlockTree.findParent(blocks, "nested-1");
      expect(result).toEqual(container);
    });

    it("should find parent of block inside a column", () => {
      const columnsBlock: Block = {
        id: "columns-1",
        type: "columns",
        columns: [
          {
            id: "col-1",
            size: 1,
            children: [{ id: "child-in-col", type: "text", content: "x" }],
          },
        ],
      };
      const blocks: Block[] = [columnsBlock];

      const result = BlockTree.findParent(blocks, "child-in-col");
      expect(result).toEqual(columnsBlock);
    });

    it("should find parent of block inside a table cell", () => {
      const tableBlock: Block = {
        id: "table-1",
        type: "table",
        rows: [
          {
            id: "row-1",
            cells: [
              {
                id: "cell-1",
                children: [{ id: "child-in-cell", type: "text", content: "x" }],
              },
            ],
          },
        ],
      };
      const blocks: Block[] = [tableBlock];

      const result = BlockTree.findParent(blocks, "child-in-cell");
      expect(result).toEqual(tableBlock);
    });
  });

  describe("findColumn", () => {
    it("should find a column by ID", () => {
      const blocks: Block[] = [
        {
          id: "columns-1",
          type: "columns",
          columns: [
            { id: "col-a", size: 1, children: [] },
            { id: "col-b", size: 2, children: [] },
          ],
        },
      ];

      const result = BlockTree.findColumn(blocks, "col-b");
      expect(result).not.toBeNull();
      expect(result!.column.id).toBe("col-b");
      expect(result!.column.size).toBe(2);
      expect(result!.block.id).toBe("columns-1");
    });

    it("should return null for non-existent column", () => {
      const blocks: Block[] = [
        {
          id: "columns-1",
          type: "columns",
          columns: [{ id: "col-a", size: 1, children: [] }],
        },
      ];

      expect(BlockTree.findColumn(blocks, "non-existent")).toBeNull();
    });
  });

  describe("findCell", () => {
    it("should find a cell by ID", () => {
      const blocks: Block[] = [
        {
          id: "table-1",
          type: "table",
          rows: [{ id: "row-1", cells: [{ id: "cell-x", children: [] }] }],
        },
      ];

      const result = BlockTree.findCell(blocks, "cell-x");
      expect(result).not.toBeNull();
      expect(result!.cell.id).toBe("cell-x");
      expect(result!.row.id).toBe("row-1");
      expect(result!.block.id).toBe("table-1");
    });

    it("should return null for non-existent cell", () => {
      const blocks: Block[] = [
        {
          id: "table-1",
          type: "table",
          rows: [{ id: "row-1", cells: [{ id: "cell-1", children: [] }] }],
        },
      ];

      expect(BlockTree.findCell(blocks, "non-existent")).toBeNull();
    });
  });

  describe("addBlock", () => {
    it("should add block at root level", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "First" },
      ];
      const newBlock: Block = {
        id: "block-2",
        type: "text",
        content: "Second",
      };

      const result = BlockTree.addBlock(blocks, newBlock, null, 1);

      expect(result).toHaveLength(2);
      expect(result[1]).toEqual(newBlock);
    });

    it("should add block at beginning", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "First" },
      ];
      const newBlock: Block = {
        id: "block-2",
        type: "text",
        content: "Before",
      };

      const result = BlockTree.addBlock(blocks, newBlock, null, 0);

      expect(result).toHaveLength(2);
      expect(result[0]).toEqual(newBlock);
    });

    it("should add block to container children", () => {
      const blocks: Block[] = [
        {
          id: "container-1",
          type: "container",
          children: [],
        },
      ];
      const newBlock: Block = { id: "new-1", type: "text", content: "New" };

      const result = BlockTree.addBlock(blocks, newBlock, "container-1", 0);

      const container = result[0] as { children: Block[] };
      expect(container.children).toHaveLength(1);
      expect(container.children[0]).toEqual(newBlock);
    });

    it("should add block to column", () => {
      const blocks: Block[] = [
        {
          id: "columns-1",
          type: "columns",
          columns: [{ id: "col-1", size: 1, children: [] }],
        },
      ];
      const newBlock: Block = { id: "new-1", type: "text", content: "New" };

      const result = BlockTree.addBlock(blocks, newBlock, "col-1", 0);

      const columnsBlock = result[0] as { columns: { children: Block[] }[] };
      expect(columnsBlock.columns[0].children).toHaveLength(1);
      expect(columnsBlock.columns[0].children[0]).toEqual(newBlock);
    });

    it("should add block to table cell", () => {
      const blocks: Block[] = [
        {
          id: "table-1",
          type: "table",
          rows: [{ id: "row-1", cells: [{ id: "cell-1", children: [] }] }],
        },
      ];
      const newBlock: Block = { id: "new-1", type: "text", content: "New" };

      const result = BlockTree.addBlock(blocks, newBlock, "cell-1", 0);

      const tableBlock = result[0] as {
        rows: { cells: { children: Block[] }[] }[];
      };
      expect(tableBlock.rows[0].cells[0].children).toHaveLength(1);
      expect(tableBlock.rows[0].cells[0].children[0]).toEqual(newBlock);
    });

    it("should add block to nested container inside another container", () => {
      const blocks: Block[] = [
        {
          id: "outer",
          type: "container",
          children: [{ id: "inner", type: "container", children: [] }],
        },
      ];
      const newBlock: Block = { id: "deep", type: "text", content: "Deep" };

      const result = BlockTree.addBlock(blocks, newBlock, "inner", 0);

      const outer = result[0] as { children: { children: Block[] }[] };
      expect(outer.children[0].children).toHaveLength(1);
      expect(outer.children[0].children[0]).toEqual(newBlock);
    });
  });

  describe("removeBlock", () => {
    it("should remove block at root level", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "First" },
        { id: "block-2", type: "text", content: "Second" },
      ];

      const result = BlockTree.removeBlock(blocks, "block-1");

      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("block-2");
    });

    it("should remove nested block", () => {
      const blocks: Block[] = [
        {
          id: "container-1",
          type: "container",
          children: [
            { id: "nested-1", type: "text", content: "Nested" },
            { id: "nested-2", type: "text", content: "Stay" },
          ],
        },
      ];

      const result = BlockTree.removeBlock(blocks, "nested-1");

      const container = result[0] as { children: Block[] };
      expect(container.children).toHaveLength(1);
      expect(container.children[0].id).toBe("nested-2");
    });

    it("should remove block from column", () => {
      const blocks: Block[] = [
        {
          id: "columns-1",
          type: "columns",
          columns: [
            {
              id: "col-1",
              size: 1,
              children: [{ id: "in-col", type: "text", content: "Remove me" }],
            },
          ],
        },
      ];

      const result = BlockTree.removeBlock(blocks, "in-col");

      const columnsBlock = result[0] as { columns: { children: Block[] }[] };
      expect(columnsBlock.columns[0].children).toHaveLength(0);
    });

    it("should remove block from table cell", () => {
      const blocks: Block[] = [
        {
          id: "table-1",
          type: "table",
          rows: [
            {
              id: "row-1",
              cells: [
                {
                  id: "cell-1",
                  children: [
                    { id: "in-cell", type: "text", content: "Remove" },
                  ],
                },
              ],
            },
          ],
        },
      ];

      const result = BlockTree.removeBlock(blocks, "in-cell");

      const tableBlock = result[0] as {
        rows: { cells: { children: Block[] }[] }[];
      };
      expect(tableBlock.rows[0].cells[0].children).toHaveLength(0);
    });

    it("should not mutate original array", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "First" },
        { id: "block-2", type: "text", content: "Second" },
      ];

      BlockTree.removeBlock(blocks, "block-1");

      expect(blocks).toHaveLength(2);
    });
  });

  describe("updateBlock", () => {
    it("should update block at root level", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "Original" },
      ];

      const result = BlockTree.updateBlock(blocks, "block-1", {
        content: "Updated",
      });

      expect((result[0] as { content: string }).content).toBe("Updated");
    });

    it("should update nested block", () => {
      const blocks: Block[] = [
        {
          id: "container-1",
          type: "container",
          children: [{ id: "nested-1", type: "text", content: "Original" }],
        },
      ];

      const result = BlockTree.updateBlock(blocks, "nested-1", {
        content: "Updated",
      });

      const container = result[0] as { children: Block[] };
      expect((container.children[0] as { content: string }).content).toBe(
        "Updated",
      );
    });

    it("should update block inside a column", () => {
      const blocks: Block[] = [
        {
          id: "columns-1",
          type: "columns",
          columns: [
            {
              id: "col-1",
              size: 1,
              children: [{ id: "in-col", type: "text", content: "Before" }],
            },
          ],
        },
      ];

      const result = BlockTree.updateBlock(blocks, "in-col", {
        content: "After",
      });

      const columnsBlock = result[0] as { columns: { children: Block[] }[] };
      expect(
        (columnsBlock.columns[0].children[0] as { content: string }).content,
      ).toBe("After");
    });

    it("should update block inside a table cell", () => {
      const blocks: Block[] = [
        {
          id: "table-1",
          type: "table",
          rows: [
            {
              id: "row-1",
              cells: [
                {
                  id: "cell-1",
                  children: [
                    { id: "in-cell", type: "text", content: "Before" },
                  ],
                },
              ],
            },
          ],
        },
      ];

      const result = BlockTree.updateBlock(blocks, "in-cell", {
        content: "After",
      });

      const tableBlock = result[0] as {
        rows: { cells: { children: Block[] }[] }[];
      };
      expect(
        (tableBlock.rows[0].cells[0].children[0] as { content: string })
          .content,
      ).toBe("After");
    });

    it("should not mutate original array", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "Original" },
      ];

      BlockTree.updateBlock(blocks, "block-1", { content: "Updated" });

      expect((blocks[0] as { content: string }).content).toBe("Original");
    });
  });

  describe("moveBlock", () => {
    it("should move block within root level", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "First" },
        { id: "block-2", type: "text", content: "Second" },
        { id: "block-3", type: "text", content: "Third" },
      ];

      const result = BlockTree.moveBlock(blocks, "block-3", null, 0);

      expect(result[0].id).toBe("block-3");
      expect(result[1].id).toBe("block-1");
      expect(result[2].id).toBe("block-2");
    });

    it("should move block from root into container", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "Move me" },
        { id: "container-1", type: "container", children: [] },
      ];

      const result = BlockTree.moveBlock(blocks, "block-1", "container-1", 0);

      expect(result).toHaveLength(1);
      const container = result[0] as { children: Block[] };
      expect(container.children).toHaveLength(1);
      expect(container.children[0].id).toBe("block-1");
    });

    it("should move block from container to root", () => {
      const blocks: Block[] = [
        {
          id: "container-1",
          type: "container",
          children: [{ id: "nested-1", type: "text", content: "Move me" }],
        },
      ];

      const result = BlockTree.moveBlock(blocks, "nested-1", null, 1);

      expect(result).toHaveLength(2);
      expect(result[1].id).toBe("nested-1");
      const container = result[0] as { children: Block[] };
      expect(container.children).toHaveLength(0);
    });

    it("should return original blocks when block ID not found", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "Only" },
      ];

      const result = BlockTree.moveBlock(blocks, "non-existent", null, 0);

      expect(result).toBe(blocks);
    });
  });

  describe("getChildren", () => {
    it("should return root blocks when parentId is null", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "First" },
        { id: "block-2", type: "text", content: "Second" },
      ];

      expect(BlockTree.getChildren(blocks, null)).toBe(blocks);
    });

    it("should return container children", () => {
      const child: Block = { id: "child-1", type: "text", content: "Child" };
      const blocks: Block[] = [
        { id: "container-1", type: "container", children: [child] },
      ];

      const result = BlockTree.getChildren(blocks, "container-1");
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("child-1");
    });

    it("should return column children", () => {
      const child: Block = { id: "col-child", type: "text", content: "In col" };
      const blocks: Block[] = [
        {
          id: "columns-1",
          type: "columns",
          columns: [{ id: "col-1", size: 1, children: [child] }],
        },
      ];

      const result = BlockTree.getChildren(blocks, "col-1");
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("col-child");
    });

    it("should return cell children", () => {
      const child: Block = {
        id: "cell-child",
        type: "text",
        content: "In cell",
      };
      const blocks: Block[] = [
        {
          id: "table-1",
          type: "table",
          rows: [{ id: "row-1", cells: [{ id: "cell-1", children: [child] }] }],
        },
      ];

      const result = BlockTree.getChildren(blocks, "cell-1");
      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("cell-child");
    });

    it("should return empty array for non-existent parent", () => {
      const blocks: Block[] = [];
      expect(BlockTree.getChildren(blocks, "non-existent")).toHaveLength(0);
    });
  });

  describe("getChildCount", () => {
    it("should count root blocks", () => {
      const blocks: Block[] = [
        { id: "block-1", type: "text", content: "First" },
        { id: "block-2", type: "text", content: "Second" },
      ];

      const count = BlockTree.getChildCount(blocks, null);
      expect(count).toBe(2);
    });

    it("should count container children", () => {
      const blocks: Block[] = [
        {
          id: "container-1",
          type: "container",
          children: [
            { id: "nested-1", type: "text", content: "One" },
            { id: "nested-2", type: "text", content: "Two" },
          ],
        },
      ];

      const count = BlockTree.getChildCount(blocks, "container-1");
      expect(count).toBe(2);
    });

    it("should return 0 for non-existent parent", () => {
      const blocks: Block[] = [];
      const count = BlockTree.getChildCount(blocks, "non-existent");
      expect(count).toBe(0);
    });
  });
});
