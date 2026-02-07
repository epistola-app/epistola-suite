import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { createRenderer, RENDERER_CLASSES, getBlockIdFromEvent } from "./renderer.ts";
import { registry, registerBlock } from "../blocks/registry.ts";
import type { BlockDefinition } from "../blocks/types.ts";
import type { Template, ContainerBlock, TextBlock } from "../types/template.ts";

// Register test block definitions
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

function createTestTemplate(blocks: Template["blocks"] = []): Template {
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

describe("createRenderer", () => {
  let root: HTMLElement;

  beforeEach(() => {
    registry.clear();
    registerBlock(containerDef);
    registerBlock(textDef);

    root = document.createElement("div");
    document.body.appendChild(root);
  });

  afterEach(() => {
    root.remove();
  });

  describe("render", () => {
    it("should add root class to container", () => {
      const renderer = createRenderer({
        root,
        template: createTestTemplate(),
        data: {},
        mode: "edit",
      });

      renderer.render();

      expect(root.classList.contains(RENDERER_CLASSES.root)).toBe(true);

      renderer.dispose();
    });

    it("should create canvas element", () => {
      const renderer = createRenderer({
        root,
        template: createTestTemplate(),
        data: {},
        mode: "edit",
      });

      renderer.render();

      const canvas = root.querySelector(`.${RENDERER_CLASSES.canvas}`);
      expect(canvas).not.toBeNull();

      renderer.dispose();
    });

    it("should render placeholder for empty template", () => {
      const renderer = createRenderer({
        root,
        template: createTestTemplate([]),
        data: {},
        mode: "edit",
      });

      renderer.render();

      const placeholder = root.querySelector(`.${RENDERER_CLASSES.placeholder}`);
      expect(placeholder).not.toBeNull();
      expect(placeholder?.textContent).toContain("Drop blocks here");

      renderer.dispose();
    });

    it("should render blocks", () => {
      const template = createTestTemplate([
        { id: "block-1", type: "text", content: { type: "doc", content: [] } },
        { id: "block-2", type: "text", content: { type: "doc", content: [] } },
      ]);

      const renderer = createRenderer({
        root,
        template,
        data: {},
        mode: "edit",
      });

      renderer.render();

      const blocks = root.querySelectorAll(`.${RENDERER_CLASSES.block}`);
      expect(blocks).toHaveLength(2);

      renderer.dispose();
    });

    it("should set block data attributes", () => {
      const template = createTestTemplate([
        { id: "block-1", type: "text", content: { type: "doc", content: [] } },
      ]);

      const renderer = createRenderer({
        root,
        template,
        data: {},
        mode: "edit",
      });

      renderer.render();

      const blockEl = root.querySelector(`.${RENDERER_CLASSES.block}`) as HTMLElement;
      expect(blockEl.dataset.blockId).toBe("block-1");
      expect(blockEl.dataset.blockType).toBe("text");

      renderer.dispose();
    });

    it("should render nested blocks", () => {
      const template = createTestTemplate([
        {
          id: "container-1",
          type: "container",
          children: [
            { id: "text-1", type: "text", content: { type: "doc", content: [] } },
          ],
        },
      ]);

      const renderer = createRenderer({
        root,
        template,
        data: {},
        mode: "edit",
      });

      renderer.render();

      const containerEl = root.querySelector('[data-block-id="container-1"]');
      const nestedTextEl = root.querySelector('[data-block-id="text-1"]');

      expect(containerEl).not.toBeNull();
      expect(nestedTextEl).not.toBeNull();
      expect(containerEl?.contains(nestedTextEl)).toBe(true);

      renderer.dispose();
    });

    it("should apply block styles", () => {
      const template = createTestTemplate([
        {
          id: "block-1",
          type: "text",
          content: { type: "doc", content: [] },
          styles: { color: "red", padding: "10px" },
        },
      ]);

      const renderer = createRenderer({
        root,
        template,
        data: {},
        mode: "edit",
      });

      renderer.render();

      const blockEl = root.querySelector('[data-block-id="block-1"]') as HTMLElement;
      expect(blockEl.style.color).toBe("red");
      expect(blockEl.style.padding).toBe("10px");

      renderer.dispose();
    });
  });

  describe("getBlockElement", () => {
    it("should return element for block ID", () => {
      const template = createTestTemplate([
        { id: "block-1", type: "text", content: { type: "doc", content: [] } },
      ]);

      const renderer = createRenderer({
        root,
        template,
        data: {},
        mode: "edit",
      });

      renderer.render();

      const element = renderer.getBlockElement("block-1");
      expect(element).not.toBeNull();
      expect(element?.dataset.blockId).toBe("block-1");

      renderer.dispose();
    });

    it("should return null for unknown block ID", () => {
      const renderer = createRenderer({
        root,
        template: createTestTemplate(),
        data: {},
        mode: "edit",
      });

      renderer.render();

      expect(renderer.getBlockElement("unknown")).toBeNull();

      renderer.dispose();
    });
  });

  describe("getOrderedBlockIds", () => {
    it("should return empty array for empty template", () => {
      const renderer = createRenderer({
        root,
        template: createTestTemplate([]),
        data: {},
        mode: "edit",
      });

      renderer.render();

      expect(renderer.getOrderedBlockIds()).toEqual([]);

      renderer.dispose();
    });

    it("should return block IDs in order", () => {
      const template = createTestTemplate([
        { id: "block-1", type: "text", content: { type: "doc", content: [] } },
        { id: "block-2", type: "text", content: { type: "doc", content: [] } },
      ]);

      const renderer = createRenderer({
        root,
        template,
        data: {},
        mode: "edit",
      });

      renderer.render();

      expect(renderer.getOrderedBlockIds()).toEqual(["block-1", "block-2"]);

      renderer.dispose();
    });

    it("should include nested block IDs", () => {
      const template = createTestTemplate([
        {
          id: "container-1",
          type: "container",
          children: [
            { id: "text-1", type: "text", content: { type: "doc", content: [] } },
          ],
        },
        { id: "block-2", type: "text", content: { type: "doc", content: [] } },
      ]);

      const renderer = createRenderer({
        root,
        template,
        data: {},
        mode: "edit",
      });

      renderer.render();

      expect(renderer.getOrderedBlockIds()).toEqual(["container-1", "text-1", "block-2"]);

      renderer.dispose();
    });
  });

  describe("dispose", () => {
    it("should clear root content", () => {
      const renderer = createRenderer({
        root,
        template: createTestTemplate([
          { id: "block-1", type: "text", content: { type: "doc", content: [] } },
        ]),
        data: {},
        mode: "edit",
      });

      renderer.render();
      renderer.dispose();

      expect(root.innerHTML).toBe("");
    });

    it("should remove root class", () => {
      const renderer = createRenderer({
        root,
        template: createTestTemplate(),
        data: {},
        mode: "edit",
      });

      renderer.render();
      renderer.dispose();

      expect(root.classList.contains(RENDERER_CLASSES.root)).toBe(false);
    });
  });

  describe("callbacks", () => {
    it("should call onBlockClick when block is clicked", () => {
      const template = createTestTemplate([
        { id: "block-1", type: "text", content: { type: "doc", content: [] } },
      ]);

      const onBlockClick = vi.fn();
      const renderer = createRenderer({
        root,
        template,
        data: {},
        mode: "edit",
        onBlockClick,
      });

      renderer.render();

      const blockEl = root.querySelector('[data-block-id="block-1"]') as HTMLElement;
      blockEl.click();

      expect(onBlockClick).toHaveBeenCalledWith("block-1", expect.any(MouseEvent));

      renderer.dispose();
    });
  });
});

describe("getBlockIdFromEvent", () => {
  it("should return block ID from event target", () => {
    const element = document.createElement("div");
    element.dataset.blockId = "block-1";

    const event = new MouseEvent("click");
    Object.defineProperty(event, "target", { value: element });

    expect(getBlockIdFromEvent(event)).toBe("block-1");
  });

  it("should return block ID from parent element", () => {
    const parent = document.createElement("div");
    parent.dataset.blockId = "block-1";

    const child = document.createElement("span");
    parent.appendChild(child);

    const event = new MouseEvent("click");
    Object.defineProperty(event, "target", { value: child });

    expect(getBlockIdFromEvent(event)).toBe("block-1");
  });

  it("should return null if no block found", () => {
    const element = document.createElement("div");

    const event = new MouseEvent("click");
    Object.defineProperty(event, "target", { value: element });

    expect(getBlockIdFromEvent(event)).toBeNull();
  });
});
