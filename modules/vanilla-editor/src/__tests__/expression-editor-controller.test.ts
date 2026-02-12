import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { TemplateEditor } from "@epistola/headless-editor";
import {
  applySuggestionAtCursor,
  coerceConditionalResult,
  formatExpressionPreviewValue,
  getBlockTypeWarning,
} from "../controllers/expression-editor";

describe("ExpressionEditorController scope variables via core API", () => {
  let editor: TemplateEditor;

  beforeEach(() => {
    editor = new TemplateEditor();
  });

  afterEach(() => {
    // no-op
  });

  it("should return empty scope variables for root-level block", () => {
    const block = editor.addBlock("text")!;
    const scopes = editor.getScopeVariables(block.id);
    expect(scopes).toEqual([]);
  });

  it("should return loop variable for block inside a loop", () => {
    const loop = editor.addBlock("loop")!;
    editor.updateBlock(loop.id, {
      itemAlias: "order",
      expression: { raw: "orders" },
    });
    const text = editor.addBlock("text", loop.id)!;

    const scopes = editor.getScopeVariables(text.id);
    expect(scopes.length).toBeGreaterThanOrEqual(1);
    expect(scopes.some((s) => s.name === "order")).toBe(true);
  });

  it("should return nested loop variables for deeply nested block", () => {
    const outerLoop = editor.addBlock("loop")!;
    editor.updateBlock(outerLoop.id, {
      itemAlias: "customer",
      expression: { raw: "customers" },
    });

    const innerLoop = editor.addBlock("loop", outerLoop.id)!;
    editor.updateBlock(innerLoop.id, {
      itemAlias: "order",
      expression: { raw: "customer.orders" },
    });

    const text = editor.addBlock("text", innerLoop.id)!;

    const scopes = editor.getScopeVariables(text.id);
    expect(scopes.some((s) => s.name === "customer")).toBe(true);
    expect(scopes.some((s) => s.name === "order")).toBe(true);
  });

  it("should access test data from editor state", () => {
    const state = editor.getState();
    expect(state.testData).toBeDefined();
  });
});

describe("expression-editor helper behavior", () => {
  it("formats preview values for primitives and object", () => {
    expect(formatExpressionPreviewValue("hello")).toBe("hello");
    expect(formatExpressionPreviewValue(42)).toBe("42");
    expect(formatExpressionPreviewValue(true)).toBe("true");
    expect(formatExpressionPreviewValue(null)).toBe("null");
    expect(formatExpressionPreviewValue(undefined)).toBe("undefined");

    const objectPreview = formatExpressionPreviewValue({
      id: 1,
      tags: ["a", "b"],
      nested: { active: true },
    });
    expect(objectPreview.startsWith("{")).toBe(true);
  });

  it("coerces conditional values with empty array treated as false", () => {
    expect(coerceConditionalResult([])).toBe(false);
    expect(coerceConditionalResult([1])).toBe(true);
    expect(coerceConditionalResult(0)).toBe(false);
    expect(coerceConditionalResult(1)).toBe(true);
    expect(coerceConditionalResult("text")).toBe(true);
    expect(coerceConditionalResult("")).toBe(false);
  });

  it("returns context-specific warnings for loop and condition blocks", () => {
    expect(getBlockTypeWarning("loop", "not-array")).toBe(
      "Expected an array for loop expression",
    );
    expect(getBlockTypeWarning("loop", [{ id: 1 }])).toBeNull();

    expect(getBlockTypeWarning("conditional", true)).toBeNull();
    expect(getBlockTypeWarning("conditional", "yes")).toBe(
      "Condition coerces to true",
    );
  });

  it("applies completion replacement using shared range", () => {
    const replaced = applySuggestionAtCursor(
      "customer.na",
      " + 1",
      { from: 9, to: 11 },
      "name",
    );
    expect(replaced.text).toBe("customer.name + 1");
    expect(replaced.cursorPos).toBe("customer.name".length);

    const safeStart = applySuggestionAtCursor(
      "cus",
      "",
      { from: -10, to: 3 },
      "customer",
    );
    expect(safeStart.text).toBe("customer");
    expect(safeStart.cursorPos).toBe("customer".length);
  });
});
