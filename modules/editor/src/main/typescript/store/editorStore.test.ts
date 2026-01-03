import { describe, it, expect, beforeEach } from "vitest";
import { useEditorStore } from "./editorStore";
import type { DataExample } from "../types/template";
import type { JsonSchema } from "../types/schema";

// Reset store before each test
beforeEach(() => {
  const { getState } = useEditorStore;
  // Reset to initial state
  getState().setDataExamples([]);
  getState().selectDataExample(null);
  getState().setSchema(null);
});

describe("editorStore - setDataExamples", () => {
  it("sets examples and selects first when none selected", () => {
    const examples: DataExample[] = [
      { id: "ex1", name: "Example 1", data: { name: "John" } },
      { id: "ex2", name: "Example 2", data: { name: "Jane" } },
    ];

    useEditorStore.getState().setDataExamples(examples);

    const state = useEditorStore.getState();
    expect(state.dataExamples).toHaveLength(2);
    expect(state.selectedDataExampleId).toBe("ex1");
    expect(state.testData).toEqual({ name: "John" });
  });

  it("clears selection when setting empty array", () => {
    // First set some examples
    const examples: DataExample[] = [{ id: "ex1", name: "Example 1", data: { name: "John" } }];
    useEditorStore.getState().setDataExamples(examples);

    // Then clear them
    useEditorStore.getState().setDataExamples([]);

    const state = useEditorStore.getState();
    expect(state.dataExamples).toHaveLength(0);
    expect(state.selectedDataExampleId).toBeNull();
    // Should fall back to default test data
    expect(state.testData).toHaveProperty("customer");
  });

  it("selects first example when current selection no longer exists", () => {
    // Set initial examples
    const examples1: DataExample[] = [
      { id: "ex1", name: "Example 1", data: { value: 1 } },
      { id: "ex2", name: "Example 2", data: { value: 2 } },
    ];
    useEditorStore.getState().setDataExamples(examples1);
    useEditorStore.getState().selectDataExample("ex2");

    // Replace with new examples that don't include ex2
    const examples2: DataExample[] = [
      { id: "ex3", name: "Example 3", data: { value: 3 } },
      { id: "ex4", name: "Example 4", data: { value: 4 } },
    ];
    useEditorStore.getState().setDataExamples(examples2);

    const state = useEditorStore.getState();
    expect(state.selectedDataExampleId).toBe("ex3");
    expect(state.testData).toEqual({ value: 3 });
  });

  it("preserves selection when it still exists in new examples", () => {
    const examples1: DataExample[] = [
      { id: "ex1", name: "Example 1", data: { value: 1 } },
      { id: "ex2", name: "Example 2", data: { value: 2 } },
    ];
    useEditorStore.getState().setDataExamples(examples1);
    useEditorStore.getState().selectDataExample("ex2");

    // Replace with examples that still include ex2
    const examples2: DataExample[] = [
      { id: "ex2", name: "Example 2 Updated", data: { value: 20 } },
      { id: "ex3", name: "Example 3", data: { value: 3 } },
    ];
    useEditorStore.getState().setDataExamples(examples2);

    const state = useEditorStore.getState();
    // Selection should be preserved (we don't automatically update testData here,
    // that's only done on setDataExamples when selection changes)
    expect(state.selectedDataExampleId).toBe("ex2");
  });
});

describe("editorStore - selectDataExample", () => {
  const examples: DataExample[] = [
    { id: "ex1", name: "Example 1", data: { name: "John" } },
    { id: "ex2", name: "Example 2", data: { name: "Jane" } },
  ];

  beforeEach(() => {
    useEditorStore.getState().setDataExamples(examples);
  });

  it("selects valid example and updates testData", () => {
    useEditorStore.getState().selectDataExample("ex2");

    const state = useEditorStore.getState();
    expect(state.selectedDataExampleId).toBe("ex2");
    expect(state.testData).toEqual({ name: "Jane" });
  });

  it("deselects when null and falls back to default", () => {
    useEditorStore.getState().selectDataExample("ex1");
    useEditorStore.getState().selectDataExample(null);

    const state = useEditorStore.getState();
    expect(state.selectedDataExampleId).toBeNull();
    // Should fall back to default test data
    expect(state.testData).toHaveProperty("customer");
  });

  it("resets selection when selecting invalid ID", () => {
    useEditorStore.getState().selectDataExample("ex1");
    useEditorStore.getState().selectDataExample("nonexistent");

    const state = useEditorStore.getState();
    expect(state.selectedDataExampleId).toBeNull();
    // Should fall back to default test data
    expect(state.testData).toHaveProperty("customer");
  });
});

describe("editorStore - addDataExample", () => {
  it("adds example to list", () => {
    const example: DataExample = { id: "new", name: "New Example", data: { test: true } };

    useEditorStore.getState().addDataExample(example);

    const state = useEditorStore.getState();
    expect(state.dataExamples).toHaveLength(1);
    expect(state.dataExamples[0]).toEqual(example);
  });

  it("appends to existing examples", () => {
    useEditorStore
      .getState()
      .setDataExamples([{ id: "ex1", name: "Example 1", data: { value: 1 } }]);

    useEditorStore.getState().addDataExample({ id: "ex2", name: "Example 2", data: { value: 2 } });

    const state = useEditorStore.getState();
    expect(state.dataExamples).toHaveLength(2);
  });
});

describe("editorStore - updateDataExample", () => {
  beforeEach(() => {
    useEditorStore.getState().setDataExamples([
      { id: "ex1", name: "Example 1", data: { name: "John" } },
      { id: "ex2", name: "Example 2", data: { name: "Jane" } },
    ]);
  });

  it("updates example name", () => {
    useEditorStore.getState().updateDataExample("ex1", { name: "Updated Name" });

    const state = useEditorStore.getState();
    expect(state.dataExamples.find((e) => e.id === "ex1")?.name).toBe("Updated Name");
  });

  it("updates example data", () => {
    useEditorStore.getState().updateDataExample("ex1", { data: { name: "Updated" } });

    const state = useEditorStore.getState();
    expect(state.dataExamples.find((e) => e.id === "ex1")?.data).toEqual({ name: "Updated" });
  });

  it("updates testData when selected example is updated", () => {
    useEditorStore.getState().selectDataExample("ex1");
    useEditorStore.getState().updateDataExample("ex1", { data: { name: "Updated" } });

    const state = useEditorStore.getState();
    expect(state.testData).toEqual({ name: "Updated" });
  });

  it("does not update testData when non-selected example is updated", () => {
    useEditorStore.getState().selectDataExample("ex1");
    const originalTestData = useEditorStore.getState().testData;

    useEditorStore.getState().updateDataExample("ex2", { data: { name: "Updated" } });

    const state = useEditorStore.getState();
    expect(state.testData).toEqual(originalTestData);
  });
});

describe("editorStore - deleteDataExample", () => {
  beforeEach(() => {
    useEditorStore.getState().setDataExamples([
      { id: "ex1", name: "Example 1", data: { name: "John" } },
      { id: "ex2", name: "Example 2", data: { name: "Jane" } },
      { id: "ex3", name: "Example 3", data: { name: "Bob" } },
    ]);
  });

  it("deletes example from list", () => {
    useEditorStore.getState().deleteDataExample("ex2");

    const state = useEditorStore.getState();
    expect(state.dataExamples).toHaveLength(2);
    expect(state.dataExamples.find((e) => e.id === "ex2")).toBeUndefined();
  });

  it("selects another example when selected is deleted", () => {
    useEditorStore.getState().selectDataExample("ex2");
    useEditorStore.getState().deleteDataExample("ex2");

    const state = useEditorStore.getState();
    expect(state.selectedDataExampleId).toBe("ex1");
    expect(state.testData).toEqual({ name: "John" });
  });

  it("falls back to default when last example is deleted", () => {
    useEditorStore
      .getState()
      .setDataExamples([{ id: "ex1", name: "Only One", data: { solo: true } }]);
    useEditorStore.getState().selectDataExample("ex1");
    useEditorStore.getState().deleteDataExample("ex1");

    const state = useEditorStore.getState();
    expect(state.dataExamples).toHaveLength(0);
    expect(state.selectedDataExampleId).toBeNull();
    expect(state.testData).toHaveProperty("customer");
  });

  it("preserves selection when non-selected example is deleted", () => {
    useEditorStore.getState().selectDataExample("ex1");
    useEditorStore.getState().deleteDataExample("ex2");

    const state = useEditorStore.getState();
    expect(state.selectedDataExampleId).toBe("ex1");
  });
});

describe("editorStore - setSchema", () => {
  it("sets schema", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: { name: { type: "string" } },
    };

    useEditorStore.getState().setSchema(schema);

    const state = useEditorStore.getState();
    expect(state.schema).toEqual(schema);
  });

  it("clears schema with null", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: { name: { type: "string" } },
    };
    useEditorStore.getState().setSchema(schema);
    useEditorStore.getState().setSchema(null);

    const state = useEditorStore.getState();
    expect(state.schema).toBeNull();
  });

  it("deep copies schema to avoid mutation", () => {
    const schema: JsonSchema = {
      type: "object",
      properties: { name: { type: "string" } },
    };
    useEditorStore.getState().setSchema(schema);

    // Mutate original
    schema.properties!.name = { type: "integer" };

    const state = useEditorStore.getState();
    expect(state.schema?.properties?.name.type).toBe("string");
  });
});
