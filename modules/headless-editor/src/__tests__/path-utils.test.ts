import { describe, it, expect } from "vitest";
import {
  extractPaths,
  inferType,
  parsePath,
  extractPathAtCursor,
  getExpressionCompletions,
  buildEvaluationContext,
  resolvePathType,
  resolvePathValue,
  getMethodsForType,
  formatTypeForDisplay,
} from "../expression/path-utils.js";

describe("extractPaths", () => {
  const testData = {
    customer: {
      name: "John",
      email: "john@test.com",
      orders: [
        { id: 1, total: 100 },
        { id: 2, total: 200 },
      ],
    },
    total: 300,
  };

  it("should extract top-level paths", () => {
    const paths = extractPaths(testData);
    const topLevel = paths.filter((p) => !p.path.includes("."));
    expect(topLevel.map((p) => p.path)).toContain("customer");
    expect(topLevel.map((p) => p.path)).toContain("total");
  });

  it("should extract nested paths", () => {
    const paths = extractPaths(testData);
    expect(paths.some((p) => p.path === "customer.name")).toBe(true);
    expect(paths.some((p) => p.path === "customer.email")).toBe(true);
  });

  it("should mark arrays correctly", () => {
    const paths = extractPaths(testData);
    const ordersPath = paths.find((p) => p.path === "customer.orders");
    expect(ordersPath?.isArray).toBe(true);
    expect(ordersPath?.type).toBe("array[2]");
  });

  it("should extract array item paths", () => {
    const paths = extractPaths(testData);
    expect(paths.some((p) => p.path === "customer.orders[0].id")).toBe(true);
    expect(paths.some((p) => p.path === "customer.orders[0].total")).toBe(true);
  });
});

describe("inferType", () => {
  it("should infer string type", () => {
    expect(inferType("hello")).toEqual({ kind: "primitive", type: "string" });
  });

  it("should infer number type", () => {
    expect(inferType(42)).toEqual({ kind: "primitive", type: "number" });
  });

  it("should infer boolean type", () => {
    expect(inferType(true)).toEqual({ kind: "primitive", type: "boolean" });
  });

  it("should infer null type", () => {
    expect(inferType(null)).toEqual({ kind: "primitive", type: "null" });
  });

  it("should infer array type", () => {
    const result = inferType([1, 2, 3]);
    expect(result.kind).toBe("array");
    expect((result as { elementType: unknown }).elementType).toEqual({ kind: "primitive", type: "number" });
  });

  it("should infer object type", () => {
    const result = inferType({ name: "John", age: 30 });
    expect(result.kind).toBe("object");
    expect((result as { properties: Record<string, unknown> }).properties).toEqual({
      name: { kind: "primitive", type: "string" },
      age: { kind: "primitive", type: "number" },
    });
  });
});

describe("parsePath", () => {
  it("should parse simple path", () => {
    expect(parsePath("customer.name")).toEqual(["customer", "name"]);
  });

  it("should parse array access", () => {
    expect(parsePath("items[0].name")).toEqual(["items", "[0]", "name"]);
  });

  it("should parse method calls", () => {
    expect(parsePath("name.toUpperCase()")).toEqual(["name", "toUpperCase()"]);
  });

  it("should parse complex path", () => {
    expect(parsePath("customer.orders[0].total.toFixed(2)")).toEqual([
      "customer",
      "orders",
      "[0]",
      "total",
      "toFixed(2)",
    ]);
  });
});

describe("resolvePathValue", () => {
  const testData = {
    customer: { name: "John", orders: [{ id: 1 }, { id: 2 }] },
    items: [10, 20, 30],
  };

  it("should resolve simple path", () => {
    expect(resolvePathValue(["customer", "name"], testData, [])).toBe("John");
  });

  it("should resolve array index", () => {
    expect(resolvePathValue(["items", "[0]"], testData, [])).toBe(10);
  });

  it("should resolve nested array", () => {
    expect(resolvePathValue(["customer", "orders", "[0]", "id"], testData, [])).toBe(1);
  });

  it("should handle scope variables", () => {
    const scopeVars: Array<{ name: string; type: "loop-item" | "loop-index"; arrayPath: string }> = [
      { name: "order", type: "loop-item", arrayPath: "customer.orders" },
    ];
    const result = resolvePathValue(["order", "id"], testData, scopeVars);
    expect(result).toBe(1);
  });
});

describe("resolvePathType", () => {
  const testData = {
    customer: { name: "John", active: true, orders: [{ id: 1 }] },
    items: [10, 20, 30],
  };

  it("should resolve primitive type", () => {
    const type = resolvePathType(["customer", "name"], testData, []);
    expect(type).toEqual({ kind: "primitive", type: "string" });
  });

  it("should resolve boolean type", () => {
    const type = resolvePathType(["customer", "active"], testData, []);
    expect(type).toEqual({ kind: "primitive", type: "boolean" });
  });

  it("should resolve array type", () => {
    const type = resolvePathType(["items"], testData, []);
    expect(type.kind).toBe("array");
  });

  it("should resolve object type", () => {
    const type = resolvePathType(["customer"], testData, []);
    expect(type.kind).toBe("object");
  });

  it("should handle method calls", () => {
    const type = resolvePathType(["customer", "name", "toUpperCase()"], testData, []);
    expect(type).toEqual({ kind: "primitive", type: "string" });
  });
});

describe("getMethodsForType", () => {
  it("should return string methods", () => {
    const methods = getMethodsForType({ kind: "primitive", type: "string" });
    expect(methods.some((m) => m.label === "toUpperCase")).toBe(true);
    expect(methods.some((m) => m.label === "toLowerCase")).toBe(true);
  });

  it("should return array methods", () => {
    const methods = getMethodsForType({ kind: "array", elementType: { kind: "primitive", type: "number" } });
    expect(methods.some((m) => m.label === "map")).toBe(true);
    expect(methods.some((m) => m.label === "filter")).toBe(true);
  });

  it("should return number methods", () => {
    const methods = getMethodsForType({ kind: "primitive", type: "number" });
    expect(methods.some((m) => m.label === "toFixed")).toBe(true);
  });

  it("should return empty for unknown type", () => {
    const methods = getMethodsForType({ kind: "unknown" });
    expect(methods.length).toBe(0);
  });
});

describe("formatTypeForDisplay", () => {
  it("should format primitive types", () => {
    expect(formatTypeForDisplay({ kind: "primitive", type: "string" })).toBe("string");
    expect(formatTypeForDisplay({ kind: "primitive", type: "number" })).toBe("number");
    expect(formatTypeForDisplay({ kind: "primitive", type: "boolean" })).toBe("boolean");
  });

  it("should format array types", () => {
    const result = formatTypeForDisplay({ kind: "array", elementType: { kind: "primitive", type: "string" } });
    expect(result).toBe("string[]");
  });

  it("should format nested array types", () => {
    const result = formatTypeForDisplay({
      kind: "array",
      elementType: { kind: "array", elementType: { kind: "primitive", type: "number" } },
    });
    expect(result).toBe("number[][]");
  });

  it("should format object type", () => {
    expect(formatTypeForDisplay({ kind: "object", properties: {} })).toBe("object");
  });
});

describe("extractPathAtCursor", () => {
  it("extracts a simple partial at root", () => {
    expect(extractPathAtCursor("cus")).toEqual({ path: [], partial: "cus" });
  });

  it("extracts a nested path after dot", () => {
    expect(extractPathAtCursor("customer.")).toEqual({ path: ["customer"], partial: "" });
  });

  it("extracts path and partial for nested typing", () => {
    expect(extractPathAtCursor("customer.na")).toEqual({ path: ["customer"], partial: "na" });
  });

  it("extracts after operators", () => {
    expect(extractPathAtCursor("count + cus")).toEqual({ path: [], partial: "cus" });
  });

  it("extracts method chain path", () => {
    expect(extractPathAtCursor("customer.name.toLowerCase().")).toEqual({
      path: ["customer", "name", "toLowerCase()"],
      partial: "",
    });
  });
});

describe("getExpressionCompletions", () => {
  const testData = {
    customer: {
      name: "John",
      age: 30,
      email: "john@example.com",
    },
    orders: [
      { id: 1, total: 100 },
      { id: 2, total: 200 },
    ],
    tags: ["vip", "premium"],
    count: 42,
  };

  it("returns top-level completions for empty input", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "",
      testData,
      scopeVars: [],
    });

    expect(result).not.toBeNull();
    expect(result?.options.map((o) => o.label)).toContain("customer");
    expect(result?.options.map((o) => o.label)).toContain("orders");
  });

  it("prioritizes scope variables over data keys", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "",
      testData,
      scopeVars: [
        { name: "order", type: "loop-item", arrayPath: "orders" },
        { name: "idx", type: "loop-index", arrayPath: "orders" },
      ],
    });

    const order = result?.options.find((o) => o.label === "order");
    const customer = result?.options.find((o) => o.label === "customer");
    expect(order).toBeDefined();
    expect(customer).toBeDefined();
    expect((order?.boost ?? 0) > (customer?.boost ?? 0)).toBe(true);
  });

  it("returns object property completions for dotted access", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "customer.",
      testData,
      scopeVars: [],
    });

    expect(result).not.toBeNull();
    expect(result?.options.map((o) => o.label)).toContain("name");
    expect(result?.options.map((o) => o.label)).toContain("age");
  });

  it("returns method completions for string type", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "customer.name.",
      testData,
      scopeVars: [],
    });

    expect(result).not.toBeNull();
    expect(result?.options.map((o) => o.label)).toContain("toLowerCase");
    expect(result?.options.map((o) => o.label)).toContain("trim");
  });

  it("returns property/method completions for array type", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "orders.",
      testData,
      scopeVars: [],
    });

    expect(result).not.toBeNull();
    expect(result?.options.map((o) => o.label)).toContain("[0]");
    expect(result?.options.map((o) => o.label)).toContain("length");
    expect(result?.options.map((o) => o.label)).toContain("map");
  });

  it("returns method completions for number type", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "count.",
      testData,
      scopeVars: [],
    });

    expect(result).not.toBeNull();
    expect(result?.options.map((o) => o.label)).toContain("toFixed");
    expect(result?.options.map((o) => o.label)).toContain("toPrecision");
  });

  it("resolves scoped object completions from loop item", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "order.",
      testData,
      scopeVars: [{ name: "order", type: "loop-item", arrayPath: "orders" }],
    });

    expect(result).not.toBeNull();
    expect(result?.options.map((o) => o.label)).toContain("id");
    expect(result?.options.map((o) => o.label)).toContain("total");
  });

  it("includes replacement range based on partial text", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "customer.na",
      testData,
      scopeVars: [],
    });

    expect(result?.partial).toBe("na");
    expect(result?.from).toBe("customer.".length);
    expect(result?.to).toBe("customer.na".length);
  });

  it("returns null for unknown path with trailing dot", () => {
    const result = getExpressionCompletions({
      textBeforeCursor: "doesnotexist.",
      testData,
      scopeVars: [],
    });

    expect(result).toBeNull();
  });
});

describe("buildEvaluationContext", () => {
  const testData = {
    customer: {
      orders: [
        { id: 1, total: 100 },
        { id: 2, total: 200 },
      ],
    },
  };

  it("merges loop item and index scope values", () => {
    const context = buildEvaluationContext(testData, [
      { name: "order", type: "loop-item", arrayPath: "customer.orders" },
      { name: "idx", type: "loop-index", arrayPath: "customer.orders" },
    ]);

    expect(context.order).toEqual({ id: 1, total: 100 });
    expect(context.idx).toBe(0);
  });

  it("ignores unresolved scope vars", () => {
    const context = buildEvaluationContext(testData, [
      { name: "missing", type: "loop-item", arrayPath: "customer.missing" },
    ]);

    expect("missing" in context).toBe(false);
  });
});
