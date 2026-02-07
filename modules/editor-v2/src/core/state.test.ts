import { describe, it, expect, vi, beforeEach } from "vitest";
import { createState, createComputed } from "./state.ts";

interface TestState {
  count: number;
  name: string;
  items: string[];
}

describe("createState", () => {
  let initialState: TestState;

  beforeEach(() => {
    initialState = {
      count: 0,
      name: "test",
      items: ["a", "b"],
    };
  });

  describe("getState", () => {
    it("should return the current state", () => {
      const store = createState(initialState);
      expect(store.getState()).toEqual(initialState);
    });

    it("should return a reference to the current state object", () => {
      const store = createState(initialState);
      const state1 = store.getState();
      const state2 = store.getState();
      expect(state1).toBe(state2);
    });
  });

  describe("setState", () => {
    it("should update state with partial object", () => {
      const store = createState(initialState);
      store.setState({ count: 5 });
      expect(store.getState().count).toBe(5);
      expect(store.getState().name).toBe("test");
    });

    it("should update state with updater function", () => {
      const store = createState(initialState);
      store.setState((s) => ({ count: s.count + 1 }));
      expect(store.getState().count).toBe(1);
    });

    it("should notify listeners on update", () => {
      const store = createState(initialState);
      const listener = vi.fn();
      store.subscribe(listener);

      store.setState({ count: 5 });

      expect(listener).toHaveBeenCalledTimes(1);
      expect(listener).toHaveBeenCalledWith(
        expect.objectContaining({ count: 5 }),
        expect.objectContaining({ count: 0 }),
      );
    });
  });

  describe("replaceState", () => {
    it("should replace the entire state", () => {
      const store = createState(initialState);
      const newState: TestState = { count: 10, name: "new", items: [] };
      store.replaceState(newState);
      expect(store.getState()).toEqual(newState);
    });

    it("should notify listeners on replace", () => {
      const store = createState(initialState);
      const listener = vi.fn();
      store.subscribe(listener);

      store.replaceState({ count: 10, name: "new", items: [] });

      expect(listener).toHaveBeenCalledTimes(1);
    });
  });

  describe("subscribe", () => {
    it("should call listener on every state change", () => {
      const store = createState(initialState);
      const listener = vi.fn();
      store.subscribe(listener);

      store.setState({ count: 1 });
      store.setState({ count: 2 });
      store.setState({ name: "changed" });

      expect(listener).toHaveBeenCalledTimes(3);
    });

    it("should return unsubscribe function", () => {
      const store = createState(initialState);
      const listener = vi.fn();
      const unsubscribe = store.subscribe(listener);

      store.setState({ count: 1 });
      expect(listener).toHaveBeenCalledTimes(1);

      unsubscribe();
      store.setState({ count: 2 });
      expect(listener).toHaveBeenCalledTimes(1);
    });

    it("should handle multiple listeners", () => {
      const store = createState(initialState);
      const listener1 = vi.fn();
      const listener2 = vi.fn();
      store.subscribe(listener1);
      store.subscribe(listener2);

      store.setState({ count: 1 });

      expect(listener1).toHaveBeenCalledTimes(1);
      expect(listener2).toHaveBeenCalledTimes(1);
    });

    it("should continue notifying other listeners if one throws", () => {
      const store = createState(initialState);
      const errorListener = vi.fn().mockImplementation(() => {
        throw new Error("Test error");
      });
      const normalListener = vi.fn();

      // Suppress console.error during this test
      const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});

      store.subscribe(errorListener);
      store.subscribe(normalListener);

      store.setState({ count: 1 });

      expect(errorListener).toHaveBeenCalledTimes(1);
      expect(normalListener).toHaveBeenCalledTimes(1);

      consoleSpy.mockRestore();
    });
  });

  describe("subscribeKey", () => {
    it("should only notify when specific key changes", () => {
      const store = createState(initialState);
      const countListener = vi.fn();
      store.subscribeKey("count", countListener);

      store.setState({ name: "changed" }); // Should not trigger
      expect(countListener).not.toHaveBeenCalled();

      store.setState({ count: 5 }); // Should trigger
      expect(countListener).toHaveBeenCalledTimes(1);
      expect(countListener).toHaveBeenCalledWith(5, 0);
    });

    it("should return unsubscribe function", () => {
      const store = createState(initialState);
      const listener = vi.fn();
      const unsubscribe = store.subscribeKey("count", listener);

      store.setState({ count: 1 });
      expect(listener).toHaveBeenCalledTimes(1);

      unsubscribe();
      store.setState({ count: 2 });
      expect(listener).toHaveBeenCalledTimes(1);
    });

    it("should handle multiple key listeners", () => {
      const store = createState(initialState);
      const countListener = vi.fn();
      const nameListener = vi.fn();
      store.subscribeKey("count", countListener);
      store.subscribeKey("name", nameListener);

      store.setState({ count: 1, name: "new" });

      expect(countListener).toHaveBeenCalledTimes(1);
      expect(nameListener).toHaveBeenCalledTimes(1);
    });
  });

  describe("snapshot", () => {
    it("should return a deep clone of state", () => {
      const store = createState(initialState);
      const snapshot = store.snapshot();

      expect(snapshot).toEqual(initialState);
      expect(snapshot).not.toBe(store.getState());
    });

    it("should create independent copies", () => {
      const store = createState(initialState);
      const snapshot1 = store.snapshot();

      store.setState({ count: 10 });
      const snapshot2 = store.snapshot();

      expect(snapshot1.count).toBe(0);
      expect(snapshot2.count).toBe(10);
    });
  });

  describe("batch", () => {
    it("should batch multiple updates into single notification", () => {
      const store = createState(initialState);
      const listener = vi.fn();
      store.subscribe(listener);

      store.batch(() => {
        store.setState({ count: 1 });
        store.setState({ count: 2 });
        store.setState({ name: "new" });
      });

      expect(listener).toHaveBeenCalledTimes(1);
      expect(store.getState().count).toBe(2);
      expect(store.getState().name).toBe("new");
    });

    it("should use first previous state for listener", () => {
      const store = createState(initialState);
      const listener = vi.fn();
      store.subscribe(listener);

      store.batch(() => {
        store.setState({ count: 1 });
        store.setState({ count: 2 });
      });

      expect(listener).toHaveBeenCalledWith(
        expect.objectContaining({ count: 2 }),
        expect.objectContaining({ count: 0 }), // Original state
      );
    });

    it("should not notify if no changes in batch", () => {
      const store = createState(initialState);
      const listener = vi.fn();
      store.subscribe(listener);

      store.batch(() => {
        // No changes
      });

      expect(listener).not.toHaveBeenCalled();
    });
  });
});

describe("createComputed", () => {
  it("should compute derived value from state", () => {
    const store = createState({ items: [1, 2, 3, 4, 5] });
    const computed = createComputed(store, (s) => s.items.filter((i) => i > 2));

    expect(computed.get()).toEqual([3, 4, 5]);
  });

  it("should update when state changes", () => {
    const store = createState({ items: [1, 2, 3] });
    const onChange = vi.fn();
    const computed = createComputed(
      store,
      (s) => s.items.length,
      onChange,
    );

    expect(computed.get()).toBe(3);

    store.setState({ items: [1, 2, 3, 4] });

    expect(computed.get()).toBe(4);
    expect(onChange).toHaveBeenCalledWith(4, 3);
  });

  it("should not call onChange if value unchanged", () => {
    const store = createState({ items: [1, 2, 3], name: "test" });
    const onChange = vi.fn();
    createComputed(store, (s) => s.items.length, onChange);

    store.setState({ name: "changed" }); // items unchanged

    expect(onChange).not.toHaveBeenCalled();
  });

  it("should clean up on unsubscribe", () => {
    const store = createState({ count: 0 });
    const onChange = vi.fn();
    const computed = createComputed(store, (s) => s.count * 2, onChange);

    store.setState({ count: 1 });
    expect(onChange).toHaveBeenCalledTimes(1);

    computed.unsubscribe();

    store.setState({ count: 2 });
    expect(onChange).toHaveBeenCalledTimes(1); // No additional calls
  });
});
