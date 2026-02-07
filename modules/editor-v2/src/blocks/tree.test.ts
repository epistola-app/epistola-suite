import { describe, it, expect, beforeEach } from "vitest";
import {
  registry,
  registerBlock,
  findBlock,
  findBlockLocation,
  getChildren,
  updateBlock,
  insertBlock,
  removeBlock,
  moveBlock,
  walkTree,
  countBlocks,
  findBlocksByType,
  getBlockPath,
} from "./index.ts";
import type { BlockDefinition, MultiContainerBlockDefinition } from "./types.ts";
import type {
  Block,
  ContainerBlock,
  TextBlock,
  ColumnsBlock,
  Column,
} from "../types/template.ts";

// ============================================================================
// Test Block Definitions
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

// ============================================================================
// Test Helpers
// ============================================================================

function createContainer(id: string, children: Block[] = []): ContainerBlock {
  return { id, type: "container", children };
}

function createText(id: string): TextBlock {
  return { id, type: "text", content: { type: "doc", content: [] } };
}

function createColumns(id: string, columns: Column[]): ColumnsBlock {
  return { id, type: "columns", columns };
}

// ============================================================================
// Tests
// ============================================================================

describe("tree operations", () => {
  beforeEach(() => {
    registry.clear();
    registerBlock(containerDef);
    registerBlock(textDef);
    registerBlock(columnsDef);
  });

  describe("getChildren", () => {
    it("should return empty array for leaf blocks", () => {
      const text = createText("t1");
      expect(getChildren(text)).toEqual([]);
    });

    it("should return children for container blocks", () => {
      const child = createText("t1");
      const container = createContainer("c1", [child]);
      expect(getChildren(container)).toEqual([child]);
    });

    it("should return flattened children for columns", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const cols = createColumns("cols", [
        { id: "col-1", size: 1, children: [t1] },
        { id: "col-2", size: 1, children: [t2] },
      ]);
      expect(getChildren(cols)).toEqual([t1, t2]);
    });
  });

  describe("findBlock", () => {
    it("should return undefined for non-existent block", () => {
      expect(findBlock([], "nonexistent")).toBeUndefined();
    });

    it("should find block at root level", () => {
      const t1 = createText("t1");
      expect(findBlock([t1], "t1")).toBe(t1);
    });

    it("should find nested block", () => {
      const t1 = createText("t1");
      const container = createContainer("c1", [t1]);
      expect(findBlock([container], "t1")).toBe(t1);
    });

    it("should find deeply nested block", () => {
      const t1 = createText("t1");
      const inner = createContainer("inner", [t1]);
      const outer = createContainer("outer", [inner]);
      expect(findBlock([outer], "t1")).toBe(t1);
    });

    it("should find block in columns", () => {
      const t1 = createText("t1");
      const cols = createColumns("cols", [
        { id: "col-1", size: 1, children: [t1] },
        { id: "col-2", size: 1, children: [] },
      ]);
      expect(findBlock([cols], "t1")).toBe(t1);
    });
  });

  describe("findBlockLocation", () => {
    it("should return undefined for non-existent block", () => {
      expect(findBlockLocation([], "nonexistent")).toBeUndefined();
    });

    it("should find root-level block location", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const location = findBlockLocation([t1, t2], "t2");

      expect(location).toBeDefined();
      expect(location?.block).toBe(t2);
      expect(location?.parent).toBeNull();
      expect(location?.containerId).toBeNull();
      expect(location?.index).toBe(1);
    });

    it("should find nested block location", () => {
      const t1 = createText("t1");
      const container = createContainer("c1", [t1]);
      const location = findBlockLocation([container], "t1");

      expect(location).toBeDefined();
      expect(location?.block).toBe(t1);
      expect(location?.parent).toBe(container);
      expect(location?.containerId).toBeNull();
      expect(location?.index).toBe(0);
    });

    it("should find block location in columns", () => {
      const t1 = createText("t1");
      const cols = createColumns("cols", [
        { id: "col-1", size: 1, children: [] },
        { id: "col-2", size: 1, children: [t1] },
      ]);
      const location = findBlockLocation([cols], "t1");

      expect(location).toBeDefined();
      expect(location?.block).toBe(t1);
      expect(location?.parent).toBe(cols);
      expect(location?.containerId).toBe("col-2");
      expect(location?.index).toBe(0);
    });
  });

  describe("updateBlock", () => {
    it("should return same array if block not found", () => {
      const blocks: Block[] = [];
      const result = updateBlock(blocks, "nonexistent", () => createText("x"));
      expect(result).toEqual([]);
    });

    it("should update root-level block", () => {
      const t1 = createText("t1");
      const result = updateBlock([t1], "t1", (block) => ({
        ...block,
        styles: { color: "red" },
      }));

      expect(result).toHaveLength(1);
      expect(result[0].styles).toEqual({ color: "red" });
    });

    it("should remove block when updater returns null", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const result = updateBlock([t1, t2], "t1", () => null);

      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("t2");
    });

    it("should update nested block", () => {
      const t1 = createText("t1");
      const container = createContainer("c1", [t1]);
      const result = updateBlock([container], "t1", (block) => ({
        ...block,
        styles: { color: "blue" },
      }));

      const updated = result[0] as ContainerBlock;
      expect(updated.children[0].styles).toEqual({ color: "blue" });
    });

    it("should update block in columns", () => {
      const t1 = createText("t1");
      const cols = createColumns("cols", [
        { id: "col-1", size: 1, children: [t1] },
        { id: "col-2", size: 1, children: [] },
      ]);
      const result = updateBlock([cols], "t1", (block) => ({
        ...block,
        styles: { fontWeight: "bold" },
      }));

      const updated = result[0] as ColumnsBlock;
      expect(updated.columns[0].children[0].styles).toEqual({ fontWeight: "bold" });
    });
  });

  describe("insertBlock", () => {
    it("should insert at root level", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const result = insertBlock([t1], t2, null, 0);

      expect(result).toHaveLength(2);
      expect(result[0].id).toBe("t2");
      expect(result[1].id).toBe("t1");
    });

    it("should insert at end of root", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const result = insertBlock([t1], t2, null, 1);

      expect(result).toHaveLength(2);
      expect(result[0].id).toBe("t1");
      expect(result[1].id).toBe("t2");
    });

    it("should insert into container", () => {
      const t1 = createText("t1");
      const container = createContainer("c1", []);
      const result = insertBlock([container], t1, "c1", 0);

      const updated = result[0] as ContainerBlock;
      expect(updated.children).toHaveLength(1);
      expect(updated.children[0].id).toBe("t1");
    });

    it("should insert into column", () => {
      const t1 = createText("t1");
      const cols = createColumns("cols", [
        { id: "col-1", size: 1, children: [] },
        { id: "col-2", size: 1, children: [] },
      ]);
      const result = insertBlock([cols], t1, "cols", 0, "col-2");

      const updated = result[0] as ColumnsBlock;
      expect(updated.columns[1].children).toHaveLength(1);
      expect(updated.columns[1].children[0].id).toBe("t1");
    });
  });

  describe("removeBlock", () => {
    it("should remove root-level block", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const result = removeBlock([t1, t2], "t1");

      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("t2");
    });

    it("should remove nested block", () => {
      const t1 = createText("t1");
      const container = createContainer("c1", [t1]);
      const result = removeBlock([container], "t1");

      const updated = result[0] as ContainerBlock;
      expect(updated.children).toHaveLength(0);
    });

    it("should remove block from columns", () => {
      const t1 = createText("t1");
      const cols = createColumns("cols", [
        { id: "col-1", size: 1, children: [t1] },
        { id: "col-2", size: 1, children: [] },
      ]);
      const result = removeBlock([cols], "t1");

      const updated = result[0] as ColumnsBlock;
      expect(updated.columns[0].children).toHaveLength(0);
    });
  });

  describe("moveBlock", () => {
    it("should move block within root level", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const t3 = createText("t3");
      const result = moveBlock([t1, t2, t3], "t3", null, 0);

      expect(result.map((b) => b.id)).toEqual(["t3", "t1", "t2"]);
    });

    it("should move block from root to container", () => {
      const t1 = createText("t1");
      const container = createContainer("c1", []);
      const result = moveBlock([t1, container], "t1", "c1", 0);

      expect(result).toHaveLength(1);
      const updated = result[0] as ContainerBlock;
      expect(updated.children[0].id).toBe("t1");
    });

    it("should move block from container to root", () => {
      const t1 = createText("t1");
      const container = createContainer("c1", [t1]);
      const result = moveBlock([container], "t1", null, 0);

      expect(result).toHaveLength(2);
      expect(result[0].id).toBe("t1");
      const updated = result[1] as ContainerBlock;
      expect(updated.children).toHaveLength(0);
    });

    it("should move block between containers", () => {
      const t1 = createText("t1");
      const c1 = createContainer("c1", [t1]);
      const c2 = createContainer("c2", []);
      const result = moveBlock([c1, c2], "t1", "c2", 0);

      const updated1 = result[0] as ContainerBlock;
      const updated2 = result[1] as ContainerBlock;
      expect(updated1.children).toHaveLength(0);
      expect(updated2.children[0].id).toBe("t1");
    });

    it("should move block between columns", () => {
      const t1 = createText("t1");
      const cols = createColumns("cols", [
        { id: "col-1", size: 1, children: [t1] },
        { id: "col-2", size: 1, children: [] },
      ]);
      const result = moveBlock([cols], "t1", "cols", 0, "col-2");

      const updated = result[0] as ColumnsBlock;
      expect(updated.columns[0].children).toHaveLength(0);
      expect(updated.columns[1].children[0].id).toBe("t1");
    });
  });

  describe("walkTree", () => {
    it("should visit all blocks", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const container = createContainer("c1", [t1, t2]);

      const visited: string[] = [];
      walkTree([container], (block) => visited.push(block.id));

      expect(visited).toEqual(["c1", "t1", "t2"]);
    });

    it("should provide parent info", () => {
      const t1 = createText("t1");
      const container = createContainer("c1", [t1]);

      const parents: Array<string | null> = [];
      walkTree([container], (_block, parent) =>
        parents.push(parent?.id ?? null),
      );

      expect(parents).toEqual([null, "c1"]);
    });

    it("should provide container ID for columns", () => {
      const t1 = createText("t1");
      const cols = createColumns("cols", [
        { id: "col-1", size: 1, children: [t1] },
        { id: "col-2", size: 1, children: [] },
      ]);

      const containerIds: Array<string | null> = [];
      walkTree([cols], (_block, _parent, containerId) =>
        containerIds.push(containerId),
      );

      expect(containerIds).toEqual([null, "col-1"]);
    });
  });

  describe("countBlocks", () => {
    it("should count empty tree", () => {
      expect(countBlocks([])).toBe(0);
    });

    it("should count all blocks including nested", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const container = createContainer("c1", [t1, t2]);

      expect(countBlocks([container])).toBe(3);
    });
  });

  describe("findBlocksByType", () => {
    it("should find all blocks of type", () => {
      const t1 = createText("t1");
      const t2 = createText("t2");
      const container = createContainer("c1", [t1]);

      const texts = findBlocksByType<TextBlock>([container, t2], "text");
      expect(texts).toHaveLength(2);
      expect(texts.map((t) => t.id).sort()).toEqual(["t1", "t2"]);
    });

    it("should return empty array if none found", () => {
      const container = createContainer("c1", []);
      expect(findBlocksByType([container], "text")).toEqual([]);
    });
  });

  describe("getBlockPath", () => {
    it("should return undefined for non-existent block", () => {
      expect(getBlockPath([], "nonexistent")).toBeUndefined();
    });

    it("should return path for root block", () => {
      const t1 = createText("t1");
      expect(getBlockPath([t1], "t1")).toEqual(["t1"]);
    });

    it("should return full path for nested block", () => {
      const t1 = createText("t1");
      const inner = createContainer("inner", [t1]);
      const outer = createContainer("outer", [inner]);

      expect(getBlockPath([outer], "t1")).toEqual(["outer", "inner", "t1"]);
    });
  });
});
