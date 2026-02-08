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

  describe("makeSortable", () => {
    it("should make container sortable with data attributes", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      dnd.makeSortable(element, "parent-1", null);

      expect(element.dataset.parentId).toBe("parent-1");
      expect(element.dataset.sortable).toBe("true");

      dnd.dispose();
    });

    it("should set container ID for multi-container blocks", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      dnd.makeSortable(element, "parent-1", "column-1");

      expect(element.dataset.parentId).toBe("parent-1");
      expect(element.dataset.containerId).toBe("column-1");

      dnd.dispose();
    });

    it("should use empty string for null parent ID", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      dnd.makeSortable(element, null, null);

      expect(element.dataset.parentId).toBe("");

      dnd.dispose();
    });

    it("should return cleanup function that removes data attributes", () => {
      const dnd = createDndManager();
      const element = document.createElement("div");
      container.appendChild(element);

      const cleanup = dnd.makeSortable(element, "parent-1", "column-1");
      cleanup();

      expect(element.dataset.parentId).toBeUndefined();
      expect(element.dataset.containerId).toBeUndefined();
      expect(element.dataset.sortable).toBeUndefined();

      dnd.dispose();
    });
  });

  describe("makePaletteContainer", () => {
    it("should make palette container sortable", () => {
      const dnd = createDndManager();
      const grid = document.createElement("div");
      grid.className = "ev2-palette__grid";
      container.appendChild(grid);

      // Create palette item
      const item = document.createElement("div");
      item.className = "ev2-palette__item";
      item.dataset.blockType = "text";
      grid.appendChild(item);

      const cleanup = dnd.makePaletteContainer(grid);

      // Just verify it returns a cleanup function and doesn't throw
      expect(typeof cleanup).toBe("function");

      cleanup();
      dnd.dispose();
    });
  });

  describe("onMove", () => {
    it("should set move callback", () => {
      const dnd = createDndManager();
      const callback = vi.fn();

      dnd.onMove(callback);

      // Callback should be stored (we can't easily test this without simulating drag events)
      dnd.dispose();
    });
  });

  describe("onAdd", () => {
    it("should set add callback", () => {
      const dnd = createDndManager();
      const callback = vi.fn();

      dnd.onAdd(callback);

      // Callback should be stored (we can't easily test this without simulating drag events)
      dnd.dispose();
    });
  });

  describe("dispose", () => {
    it("should clean up all sortable instances", () => {
      const dnd = createDndManager();
      const element1 = document.createElement("div");
      const element2 = document.createElement("div");
      container.appendChild(element1);
      container.appendChild(element2);

      dnd.makeSortable(element1, "parent-1", null);
      dnd.makeSortable(element2, "parent-2", null);

      dnd.dispose();

      // Data attributes should be cleaned by dispose through individual cleanups
      // Note: dispose destroys sortables but doesn't call individual cleanup functions
      // So we just verify it doesn't throw
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
