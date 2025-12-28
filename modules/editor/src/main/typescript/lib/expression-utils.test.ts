import { describe, it, expect } from "vitest";
import {
  extractPaths,
  resolveScopeVariable,
  buildEvaluationContext,
  formatPreviewValue,
} from "./expression-utils";
import type { ScopeVariable } from "../context/ScopeContext";

describe("extractPaths", () => {
  it("extracts paths from simple object", () => {
    const obj = { name: "John", age: 30 };
    const paths = extractPaths(obj);

    expect(paths).toContainEqual({ path: "name", isArray: false, type: "string" });
    expect(paths).toContainEqual({ path: "age", isArray: false, type: "number" });
  });

  it("extracts paths from nested object", () => {
    const obj = { customer: { name: "John" } };
    const paths = extractPaths(obj);

    expect(paths).toContainEqual({ path: "customer", isArray: false, type: "object" });
    expect(paths).toContainEqual({ path: "customer.name", isArray: false, type: "string" });
  });

  it("extracts paths from arrays", () => {
    const obj = { tags: ["a", "b", "c"] };
    const paths = extractPaths(obj);

    expect(paths).toContainEqual({ path: "tags", isArray: true, type: "array[3]" });
    // Primitive arrays don't add [0] as standalone path
    expect(paths).toContainEqual({ path: "tags.length", isArray: false, type: "number" });
  });

  it("extracts paths from array of objects", () => {
    const obj = {
      orders: [{ id: 1, name: "Order 1" }],
    };
    const paths = extractPaths(obj);

    expect(paths).toContainEqual({ path: "orders", isArray: true, type: "array[1]" });
    // Object arrays add nested paths with [0] prefix
    expect(paths).toContainEqual({ path: "orders[0].id", isArray: false, type: "number" });
    expect(paths).toContainEqual({ path: "orders[0].name", isArray: false, type: "string" });
    expect(paths).toContainEqual({ path: "orders.length", isArray: false, type: "number" });
  });

  it("handles empty arrays", () => {
    const obj = { items: [] };
    const paths = extractPaths(obj);

    expect(paths).toContainEqual({ path: "items", isArray: true, type: "array[0]" });
    // No [0] paths for empty array
    expect(paths.find((p) => p.path === "items[0]")).toBeUndefined();
  });

  it("handles deeply nested objects", () => {
    const obj = { a: { b: { c: { d: "deep" } } } };
    const paths = extractPaths(obj);

    expect(paths).toContainEqual({ path: "a.b.c.d", isArray: false, type: "string" });
  });

  it("returns empty array for null", () => {
    expect(extractPaths(null)).toEqual([]);
  });

  it("returns empty array for undefined", () => {
    expect(extractPaths(undefined)).toEqual([]);
  });

  it("returns empty array for primitives", () => {
    expect(extractPaths("string")).toEqual([]);
    expect(extractPaths(42)).toEqual([]);
  });

  it("uses prefix correctly", () => {
    const obj = { name: "John" };
    const paths = extractPaths(obj, "customer");

    expect(paths).toContainEqual({ path: "customer.name", isArray: false, type: "string" });
  });

  it("handles nested arrays", () => {
    const obj = {
      matrix: [
        [1, 2],
        [3, 4],
      ],
    };
    const paths = extractPaths(obj);

    expect(paths).toContainEqual({ path: "matrix", isArray: true, type: "array[2]" });
    expect(paths).toContainEqual({ path: "matrix[0]", isArray: true, type: "array[2]" });
    expect(paths).toContainEqual({ path: "matrix.length", isArray: false, type: "number" });
    expect(paths).toContainEqual({ path: "matrix[0].length", isArray: false, type: "number" });
  });

  it("handles boolean values", () => {
    const obj = { active: true, disabled: false };
    const paths = extractPaths(obj);

    expect(paths).toContainEqual({ path: "active", isArray: false, type: "boolean" });
    expect(paths).toContainEqual({ path: "disabled", isArray: false, type: "boolean" });
  });
});

describe("resolveScopeVariable", () => {
  const testData = {
    customer: {
      name: "John",
      orders: [
        { id: 1, total: 100 },
        { id: 2, total: 200 },
      ],
    },
    items: ["apple", "banana"],
  };

  it("returns undefined for non-existent variable", () => {
    expect(resolveScopeVariable("nonexistent", [], testData)).toBeUndefined();
  });

  it("returns 0 for loop-index variable", () => {
    const scopeVars: ScopeVariable[] = [{ name: "idx", type: "loop-index", arrayPath: "items" }];
    expect(resolveScopeVariable("idx", scopeVars, testData)).toBe(0);
  });

  it("resolves loop-item to first array element", () => {
    const scopeVars: ScopeVariable[] = [
      { name: "order", type: "loop-item", arrayPath: "customer.orders" },
    ];
    expect(resolveScopeVariable("order", scopeVars, testData)).toEqual({ id: 1, total: 100 });
  });

  it("resolves simple array loop-item", () => {
    const scopeVars: ScopeVariable[] = [{ name: "item", type: "loop-item", arrayPath: "items" }];
    expect(resolveScopeVariable("item", scopeVars, testData)).toBe("apple");
  });

  it("returns undefined for loop-item with non-existent array path", () => {
    const scopeVars: ScopeVariable[] = [
      { name: "x", type: "loop-item", arrayPath: "nonexistent.path" },
    ];
    expect(resolveScopeVariable("x", scopeVars, testData)).toBeUndefined();
  });

  it("returns undefined for loop-item with empty array", () => {
    const data = { emptyList: [] };
    const scopeVars: ScopeVariable[] = [{ name: "x", type: "loop-item", arrayPath: "emptyList" }];
    expect(resolveScopeVariable("x", scopeVars, data)).toBeUndefined();
  });
});

describe("buildEvaluationContext", () => {
  const testData = {
    customer: { name: "John" },
    orders: [{ id: 1 }, { id: 2 }],
  };

  it("returns copy of data when no scope vars", () => {
    const context = buildEvaluationContext(testData, []);
    expect(context).toEqual(testData);
    expect(context).not.toBe(testData); // Should be a copy
  });

  it("adds resolved scope variables to context", () => {
    const scopeVars: ScopeVariable[] = [
      { name: "order", type: "loop-item", arrayPath: "orders" },
      { name: "idx", type: "loop-index", arrayPath: "orders" },
    ];
    const context = buildEvaluationContext(testData, scopeVars);

    expect(context.order).toEqual({ id: 1 });
    expect(context.idx).toBe(0);
    expect(context.customer).toEqual({ name: "John" });
  });

  it("does not add undefined scope variables", () => {
    const scopeVars: ScopeVariable[] = [{ name: "x", type: "loop-item", arrayPath: "nonexistent" }];
    const context = buildEvaluationContext(testData, scopeVars);

    expect("x" in context).toBe(false);
  });

  it("scope variables do not modify original data", () => {
    const scopeVars: ScopeVariable[] = [{ name: "order", type: "loop-item", arrayPath: "orders" }];
    buildEvaluationContext(testData, scopeVars);

    expect("order" in testData).toBe(false);
  });
});

describe("formatPreviewValue", () => {
  it("formats undefined", () => {
    expect(formatPreviewValue(undefined)).toBe("undefined");
  });

  it("formats null", () => {
    expect(formatPreviewValue(null)).toBe("null");
  });

  it("formats strings", () => {
    expect(formatPreviewValue("hello")).toBe("hello");
    expect(formatPreviewValue("")).toBe("");
  });

  it("formats numbers", () => {
    expect(formatPreviewValue(42)).toBe("42");
    expect(formatPreviewValue(3.14)).toBe("3.14");
    expect(formatPreviewValue(0)).toBe("0");
    expect(formatPreviewValue(-1)).toBe("-1");
  });

  it("formats booleans", () => {
    expect(formatPreviewValue(true)).toBe("true");
    expect(formatPreviewValue(false)).toBe("false");
  });

  it("formats small objects as JSON", () => {
    expect(formatPreviewValue({ a: 1 })).toBe('{"a":1}');
    expect(formatPreviewValue([1, 2, 3])).toBe("[1,2,3]");
  });

  it("truncates long objects", () => {
    const longObject = {
      name: "This is a very long name that exceeds the limit",
      value: 12345,
    };
    const result = formatPreviewValue(longObject);
    expect(result.length).toBeLessThanOrEqual(53); // 50 + "..."
    expect(result.endsWith("...")).toBe(true);
  });

  it("truncates long arrays", () => {
    const longArray = Array(20).fill("item");
    const result = formatPreviewValue(longArray);
    expect(result.endsWith("...")).toBe(true);
  });

  it("formats empty object", () => {
    expect(formatPreviewValue({})).toBe("{}");
  });

  it("formats empty array", () => {
    expect(formatPreviewValue([])).toBe("[]");
  });
});
