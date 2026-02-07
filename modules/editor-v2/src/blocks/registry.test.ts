import { describe, it, expect, beforeEach } from "vitest";
import {
  registry,
  registerBlock,
  unregisterBlock,
  createBlock,
  getBlockLabel,
  getBlockIcon,
  canContain,
} from "./registry.ts";
import type { BlockDefinition } from "./types.ts";
import type { ContainerBlock, TextBlock, Block } from "../types/template.ts";

// Test block definitions
const containerDef: BlockDefinition<ContainerBlock> = {
  type: "container",
  label: "Container",
  category: "structure",
  icon: "<svg>container</svg>",
  createDefault: () => ({
    id: "test-container",
    type: "container",
    children: [],
  }),
  getChildren: (block) => block.children,
  setChildren: (block, children) => ({ ...block, children }),
  canContain: () => true,
};

const textDef: BlockDefinition<TextBlock> = {
  type: "text",
  label: "Text",
  category: "content",
  icon: "<svg>text</svg>",
  description: "A text block",
  createDefault: () => ({
    id: "test-text",
    type: "text",
    content: { type: "doc", content: [] },
  }),
  // Text blocks have no children
};

describe("registry", () => {
  beforeEach(() => {
    registry.clear();
  });

  describe("registerBlock", () => {
    it("should register a block definition", () => {
      registerBlock(containerDef);
      expect(registry.has("container")).toBe(true);
    });

    it("should throw if registering duplicate type", () => {
      registerBlock(containerDef);
      expect(() => registerBlock(containerDef)).toThrow(
        'Block type "container" is already registered',
      );
    });

    it("should register multiple block types", () => {
      registerBlock(containerDef);
      registerBlock(textDef);
      expect(registry.size).toBe(2);
    });
  });

  describe("get", () => {
    it("should return undefined for unregistered type", () => {
      expect(registry.get("nonexistent")).toBeUndefined();
    });

    it("should return the block definition", () => {
      registerBlock(containerDef);
      const def = registry.get("container");
      expect(def).toBe(containerDef);
    });
  });

  describe("getAll", () => {
    it("should return empty array when no blocks registered", () => {
      expect(registry.getAll()).toEqual([]);
    });

    it("should return all registered definitions", () => {
      registerBlock(containerDef);
      registerBlock(textDef);
      const all = registry.getAll();
      expect(all).toHaveLength(2);
      expect(all).toContain(containerDef);
      expect(all).toContain(textDef);
    });
  });

  describe("getByCategory", () => {
    it("should return empty array for empty category", () => {
      expect(registry.getByCategory("structure")).toEqual([]);
    });

    it("should return blocks in category", () => {
      registerBlock(containerDef);
      registerBlock(textDef);

      const structureBlocks = registry.getByCategory("structure");
      expect(structureBlocks).toHaveLength(1);
      expect(structureBlocks[0]).toBe(containerDef);

      const contentBlocks = registry.getByCategory("content");
      expect(contentBlocks).toHaveLength(1);
      expect(contentBlocks[0]).toBe(textDef);
    });
  });

  describe("getAllCategories", () => {
    it("should return map of categories to blocks", () => {
      registerBlock(containerDef);
      registerBlock(textDef);

      const categories = registry.getAllCategories();
      expect(categories.size).toBe(2);
      expect(categories.get("structure")).toContain(containerDef);
      expect(categories.get("content")).toContain(textDef);
    });
  });

  describe("unregisterBlock", () => {
    it("should return false for unregistered type", () => {
      expect(unregisterBlock("nonexistent")).toBe(false);
    });

    it("should remove registered block", () => {
      registerBlock(containerDef);
      expect(registry.has("container")).toBe(true);

      expect(unregisterBlock("container")).toBe(true);
      expect(registry.has("container")).toBe(false);
    });

    it("should remove from category index", () => {
      registerBlock(containerDef);
      unregisterBlock("container");
      expect(registry.getByCategory("structure")).toEqual([]);
    });
  });
});

describe("utility functions", () => {
  beforeEach(() => {
    registry.clear();
    registerBlock(containerDef);
    registerBlock(textDef);
  });

  describe("createBlock", () => {
    it("should return undefined for unregistered type", () => {
      expect(createBlock("nonexistent" as Block["type"])).toBeUndefined();
    });

    it("should create a new block instance", () => {
      const block = createBlock("container");
      expect(block).toBeDefined();
      expect(block?.type).toBe("container");
    });
  });

  describe("getBlockLabel", () => {
    it("should return type for unregistered block", () => {
      expect(getBlockLabel("nonexistent")).toBe("nonexistent");
    });

    it("should return label for registered block", () => {
      expect(getBlockLabel("container")).toBe("Container");
      expect(getBlockLabel("text")).toBe("Text");
    });
  });

  describe("getBlockIcon", () => {
    it("should return empty string for unregistered block", () => {
      expect(getBlockIcon("nonexistent")).toBe("");
    });

    it("should return icon for registered block", () => {
      expect(getBlockIcon("container")).toBe("<svg>container</svg>");
    });
  });

  describe("canContain", () => {
    it("should return false for unregistered parent", () => {
      expect(canContain("nonexistent", "text")).toBe(false);
    });

    it("should use canContain if defined", () => {
      expect(canContain("container", "text")).toBe(true);
      expect(canContain("container", "container")).toBe(true);
    });

    it("should return false for leaf blocks", () => {
      expect(canContain("text", "text")).toBe(false);
    });
  });
});
