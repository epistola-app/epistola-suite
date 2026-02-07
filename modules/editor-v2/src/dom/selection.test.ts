import { describe, it, expect, vi } from "vitest";
import {
  createSelectionManager,
  applySelectionClasses,
  SELECTION_CLASSES,
} from "./selection.ts";

describe("createSelectionManager", () => {
  describe("initial state", () => {
    it("should start with empty selection", () => {
      const selection = createSelectionManager();
      expect(selection.isEmpty()).toBe(true);
      expect(selection.count()).toBe(0);
    });

    it("should have null anchor and focus", () => {
      const selection = createSelectionManager();
      const state = selection.getState();
      expect(state.anchorId).toBeNull();
      expect(state.focusId).toBeNull();
    });
  });

  describe("select", () => {
    it("should select a single block", () => {
      const selection = createSelectionManager();
      selection.select("block-1");

      expect(selection.isSelected("block-1")).toBe(true);
      expect(selection.count()).toBe(1);
    });

    it("should set anchor and focus to the selected block", () => {
      const selection = createSelectionManager();
      selection.select("block-1");

      const state = selection.getState();
      expect(state.anchorId).toBe("block-1");
      expect(state.focusId).toBe("block-1");
    });

    it("should clear previous selection", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.select("block-2");

      expect(selection.isSelected("block-1")).toBe(false);
      expect(selection.isSelected("block-2")).toBe(true);
      expect(selection.count()).toBe(1);
    });
  });

  describe("toggle", () => {
    it("should add unselected block to selection", () => {
      const selection = createSelectionManager();
      selection.toggle("block-1");

      expect(selection.isSelected("block-1")).toBe(true);
    });

    it("should remove selected block from selection", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.toggle("block-1");

      expect(selection.isSelected("block-1")).toBe(false);
      expect(selection.isEmpty()).toBe(true);
    });

    it("should maintain multi-selection", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.toggle("block-2");
      selection.toggle("block-3");

      expect(selection.isSelected("block-1")).toBe(true);
      expect(selection.isSelected("block-2")).toBe(true);
      expect(selection.isSelected("block-3")).toBe(true);
      expect(selection.count()).toBe(3);
    });

    it("should update focus to newly toggled block", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.toggle("block-2");

      const state = selection.getState();
      expect(state.anchorId).toBe("block-1");
      expect(state.focusId).toBe("block-2");
    });
  });

  describe("extendTo", () => {
    it("should select range from anchor to target", () => {
      const selection = createSelectionManager();
      const orderedIds = ["block-1", "block-2", "block-3", "block-4", "block-5"];

      selection.select("block-2");
      selection.extendTo("block-4", orderedIds);

      expect(selection.isSelected("block-1")).toBe(false);
      expect(selection.isSelected("block-2")).toBe(true);
      expect(selection.isSelected("block-3")).toBe(true);
      expect(selection.isSelected("block-4")).toBe(true);
      expect(selection.isSelected("block-5")).toBe(false);
      expect(selection.count()).toBe(3);
    });

    it("should work in reverse direction", () => {
      const selection = createSelectionManager();
      const orderedIds = ["block-1", "block-2", "block-3", "block-4", "block-5"];

      selection.select("block-4");
      selection.extendTo("block-2", orderedIds);

      expect(selection.isSelected("block-2")).toBe(true);
      expect(selection.isSelected("block-3")).toBe(true);
      expect(selection.isSelected("block-4")).toBe(true);
      expect(selection.count()).toBe(3);
    });

    it("should keep anchor but update focus", () => {
      const selection = createSelectionManager();
      const orderedIds = ["block-1", "block-2", "block-3"];

      selection.select("block-1");
      selection.extendTo("block-3", orderedIds);

      const state = selection.getState();
      expect(state.anchorId).toBe("block-1");
      expect(state.focusId).toBe("block-3");
    });

    it("should fallback to single select if no anchor", () => {
      const selection = createSelectionManager();
      const orderedIds = ["block-1", "block-2", "block-3"];

      selection.extendTo("block-2", orderedIds);

      expect(selection.count()).toBe(1);
      expect(selection.isSelected("block-2")).toBe(true);
    });
  });

  describe("add", () => {
    it("should add a block to selection", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.add("block-2");

      expect(selection.isSelected("block-1")).toBe(true);
      expect(selection.isSelected("block-2")).toBe(true);
    });

    it("should not duplicate already selected blocks", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.add("block-1");

      expect(selection.count()).toBe(1);
    });
  });

  describe("remove", () => {
    it("should remove a block from selection", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.toggle("block-2");
      selection.remove("block-1");

      expect(selection.isSelected("block-1")).toBe(false);
      expect(selection.isSelected("block-2")).toBe(true);
    });

    it("should be no-op for unselected blocks", () => {
      const selection = createSelectionManager();
      selection.select("block-1");

      const listener = vi.fn();
      selection.subscribe(listener);
      listener.mockClear();

      selection.remove("block-2");

      expect(listener).not.toHaveBeenCalled();
    });
  });

  describe("clear", () => {
    it("should clear all selection", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.toggle("block-2");
      selection.clear();

      expect(selection.isEmpty()).toBe(true);
      expect(selection.count()).toBe(0);
    });

    it("should reset anchor and focus", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.clear();

      const state = selection.getState();
      expect(state.anchorId).toBeNull();
      expect(state.focusId).toBeNull();
    });

    it("should not notify if already empty", () => {
      const selection = createSelectionManager();

      const listener = vi.fn();
      selection.subscribe(listener);
      listener.mockClear();

      selection.clear();

      expect(listener).not.toHaveBeenCalled();
    });
  });

  describe("subscribe", () => {
    it("should notify immediately with current state", () => {
      const selection = createSelectionManager();
      selection.select("block-1");

      const listener = vi.fn();
      selection.subscribe(listener);

      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener).toHaveBeenCalledWith(selection.getState());
    });

    it("should notify on selection changes", () => {
      const selection = createSelectionManager();

      const listener = vi.fn();
      selection.subscribe(listener);
      listener.mockClear();

      selection.select("block-1");

      expect(listener).toHaveBeenCalledTimes(1);
    });

    it("should return unsubscribe function", () => {
      const selection = createSelectionManager();

      const listener = vi.fn();
      const unsubscribe = selection.subscribe(listener);
      listener.mockClear();

      unsubscribe();
      selection.select("block-1");

      expect(listener).not.toHaveBeenCalled();
    });
  });

  describe("dispose", () => {
    it("should clear all state", () => {
      const selection = createSelectionManager();
      selection.select("block-1");
      selection.dispose();

      expect(selection.isEmpty()).toBe(true);
    });

    it("should stop notifying listeners", () => {
      const selection = createSelectionManager();

      const listener = vi.fn();
      selection.subscribe(listener);
      listener.mockClear();

      selection.dispose();
      selection.select("block-1");

      expect(listener).not.toHaveBeenCalled();
    });
  });
});

describe("applySelectionClasses", () => {
  it("should add selected class when selected", () => {
    const element = document.createElement("div");
    applySelectionClasses(element, "block-1", {
      selectedIds: new Set(["block-1"]),
      anchorId: "block-1",
      focusId: "block-1",
    });

    expect(element.classList.contains(SELECTION_CLASSES.selected)).toBe(true);
  });

  it("should remove selected class when not selected", () => {
    const element = document.createElement("div");
    element.classList.add(SELECTION_CLASSES.selected);

    applySelectionClasses(element, "block-1", {
      selectedIds: new Set(),
      anchorId: null,
      focusId: null,
    });

    expect(element.classList.contains(SELECTION_CLASSES.selected)).toBe(false);
  });

  it("should add anchor class when anchor", () => {
    const element = document.createElement("div");
    applySelectionClasses(element, "block-1", {
      selectedIds: new Set(["block-1"]),
      anchorId: "block-1",
      focusId: "block-2",
    });

    expect(element.classList.contains(SELECTION_CLASSES.anchor)).toBe(true);
    expect(element.classList.contains(SELECTION_CLASSES.focus)).toBe(false);
  });

  it("should add focus class when focus", () => {
    const element = document.createElement("div");
    applySelectionClasses(element, "block-2", {
      selectedIds: new Set(["block-1", "block-2"]),
      anchorId: "block-1",
      focusId: "block-2",
    });

    expect(element.classList.contains(SELECTION_CLASSES.anchor)).toBe(false);
    expect(element.classList.contains(SELECTION_CLASSES.focus)).toBe(true);
  });
});
