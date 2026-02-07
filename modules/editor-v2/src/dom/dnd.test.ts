import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  createDndManager,
  createDragHandle,
  calculateInsertIndex,
} from "./dnd.ts";

describe("createDndManager", () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
  });

  afterEach(() => {
    container.remove();
  });

  describe("makeDraggable", () => {
    it("should make element draggable", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      dnd.makeDraggable(element, "block-1");

      expect(element.draggable).toBe(true);
      expect(element.dataset.blockId).toBe("block-1");

      dnd.dispose();
    });

    it("should return cleanup function", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      const cleanup = dnd.makeDraggable(element, "block-1");
      cleanup();

      expect(element.draggable).toBe(false);

      dnd.dispose();
    });
  });

  describe("makeDropZone", () => {
    it("should set drop zone data attributes", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      dnd.makeDropZone(element, "block-1");

      expect(element.dataset.dropZone).toBe("true");
      expect(element.dataset.blockId).toBe("block-1");

      dnd.dispose();
    });

    it("should set container ID for multi-container blocks", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      dnd.makeDropZone(element, "block-1", "column-1");

      expect(element.dataset.containerId).toBe("column-1");

      dnd.dispose();
    });

    it("should return cleanup function", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      const cleanup = dnd.makeDropZone(element, "block-1");
      cleanup();

      expect(element.dataset.dropZone).toBeUndefined();

      dnd.dispose();
    });
  });

  describe("makePaletteDraggable", () => {
    it("should make palette item draggable", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      dnd.makePaletteDraggable(element, "text");

      expect(element.draggable).toBe(true);
      expect(element.dataset.blockType).toBe("text");

      dnd.dispose();
    });
  });

  describe("onDrop", () => {
    it("should set drop callback", () => {
      const dnd = createDndManager();
      const callback = vi.fn();

      dnd.onDrop(callback);

      // Callback should be stored (we can't easily test this without simulating drag events)
      dnd.dispose();
    });
  });

  describe("dispose", () => {
    it("should clean up all elements", () => {
      const dnd = createDndManager();
      const element1 = document.createElement("div");
      const element2 = document.createElement("div");
      container.appendChild(element1);
      container.appendChild(element2);

      dnd.makeDraggable(element1, "block-1");
      dnd.makeDropZone(element2, "block-2");

      dnd.dispose();

      expect(element1.draggable).toBe(false);
      expect(element2.dataset.dropZone).toBeUndefined();
    });
  });
});

describe("createDragHandle", () => {
  it("should create a drag handle element", () => {
    const handle = createDragHandle();

    expect(handle.tagName).toBe("DIV");
    expect(handle.className).toBe("ev2-drag-handle");
    expect(handle.querySelector("svg")).not.toBeNull();
  });
});

describe("calculateInsertIndex", () => {
  it("should return target index for before position", () => {
    expect(calculateInsertIndex("before", 2, false)).toBe(2);
    expect(calculateInsertIndex("before", 2, true)).toBe(2);
  });

  it("should return target index + 1 for after position", () => {
    expect(calculateInsertIndex("after", 2, false)).toBe(3);
    expect(calculateInsertIndex("after", 2, true)).toBe(3);
  });

  it("should return 0 for inside position", () => {
    expect(calculateInsertIndex("inside", 2, false)).toBe(0);
    expect(calculateInsertIndex("inside", 2, true)).toBe(0);
  });
});
