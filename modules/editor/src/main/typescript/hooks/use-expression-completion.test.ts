import {describe, expect, it} from "vitest";
import {createExpressionCompletionSource} from "./use-expression-completion";
import type {ScopeVariable} from "../context/ScopeContext";
import {Text} from "@codemirror/state";

// Helper to create a mock CompletionContext
function createMockContext(text: string, cursorPos?: number) {
  const pos = cursorPos ?? text.length;
  const doc = Text.of([text]);

  return {
    state: {
      doc,
    },
    pos,
    explicit: false,
    tokenBefore: () => null,
    matchBefore: () => null,
    aborted: false,
    addEventListener: () => {},
  };
}

describe("createExpressionCompletionSource", () => {
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
    active: true,
  };

  describe("top-level completions", () => {
    it("returns all top-level properties for empty input", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("customer");
      expect(result?.options.map((o) => o.label)).toContain("orders");
      expect(result?.options.map((o) => o.label)).toContain("tags");
      expect(result?.options.map((o) => o.label)).toContain("count");
      expect(result?.options.map((o) => o.label)).toContain("active");
    });

    it("filters completions by partial input", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("cus");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("customer");
      expect(result?.options.map((o) => o.label)).not.toContain("orders");
    });

    it("includes scope variables with higher priority", () => {
      const scopeVars: ScopeVariable[] = [
        { name: "order", type: "loop-item", arrayPath: "orders" },
        { name: "idx", type: "loop-index", arrayPath: "orders" },
      ];
      const source = createExpressionCompletionSource(testData, scopeVars);
      const context = createMockContext("");
      const result = source(context as any);

      expect(result).not.toBeNull();
      const orderOption = result?.options.find((o) => o.label === "order");
      const idxOption = result?.options.find((o) => o.label === "idx");
      expect(orderOption).toBeDefined();
      expect(idxOption).toBeDefined();
      expect(orderOption?.boost).toBe(10); // Higher than data properties
    });

    it("shows type information in detail", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("");
      const result = source(context as any);

      const customerOption = result?.options.find((o) => o.label === "customer");
      const ordersOption = result?.options.find((o) => o.label === "orders");
      const countOption = result?.options.find((o) => o.label === "count");

      expect(customerOption?.detail).toBe("object");
      expect(ordersOption?.detail).toMatch(/\[\]/);
      expect(countOption?.detail).toBe("number");
    });
  });

  describe("nested property completions", () => {
    it("shows object properties after dot", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("customer.");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("name");
      expect(result?.options.map((o) => o.label)).toContain("age");
      expect(result?.options.map((o) => o.label)).toContain("email");
    });

    it("filters nested properties by partial", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("customer.na");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("name");
      expect(result?.options.map((o) => o.label)).not.toContain("age");
    });

    it("shows array methods for array properties", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("orders.");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("map");
      expect(result?.options.map((o) => o.label)).toContain("filter");
      expect(result?.options.map((o) => o.label)).toContain("length");
      expect(result?.options.map((o) => o.label)).toContain("[0]");
    });

    it("shows string methods for string properties", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("customer.name.");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("toLowerCase");
      expect(result?.options.map((o) => o.label)).toContain("toUpperCase");
      expect(result?.options.map((o) => o.label)).toContain("trim");
      expect(result?.options.map((o) => o.label)).toContain("length");
    });

    it("shows number methods for number properties", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("count.");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("toFixed");
      expect(result?.options.map((o) => o.label)).toContain("toString");
    });
  });

  describe("method chaining", () => {
    it("shows string methods after string method call", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("customer.name.toLowerCase().");
      const result = source(context as any);

      expect(result).not.toBeNull();
      // toLowerCase() returns string, so show string methods
      expect(result?.options.map((o) => o.label)).toContain("trim");
      expect(result?.options.map((o) => o.label)).toContain("toUpperCase");
    });

    it("shows array methods after split()", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("customer.name.split().");
      const result = source(context as any);

      expect(result).not.toBeNull();
      // split() returns string[], so show array methods
      expect(result?.options.map((o) => o.label)).toContain("map");
      expect(result?.options.map((o) => o.label)).toContain("filter");
      expect(result?.options.map((o) => o.label)).toContain("join");
    });

    it("shows string methods after join()", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("tags.join().");
      const result = source(context as any);

      expect(result).not.toBeNull();
      // join() returns string, so show string methods
      expect(result?.options.map((o) => o.label)).toContain("toLowerCase");
      expect(result?.options.map((o) => o.label)).toContain("split");
    });
  });

  describe("array access", () => {
    it("shows element properties after [0]", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("orders[0].");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("id");
      expect(result?.options.map((o) => o.label)).toContain("total");
    });

    it("shows string methods for primitive array element", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("tags[0].");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("toLowerCase");
      expect(result?.options.map((o) => o.label)).toContain("toUpperCase");
    });
  });

  describe("scope variables", () => {
    it("resolves scope variable type for completions", () => {
      const scopeVars: ScopeVariable[] = [
        { name: "order", type: "loop-item", arrayPath: "orders" },
      ];
      const source = createExpressionCompletionSource(testData, scopeVars);
      const context = createMockContext("order.");
      const result = source(context as any);

      expect(result).not.toBeNull();
      // order is first element of orders array, which has { id, total }
      expect(result?.options.map((o) => o.label)).toContain("id");
      expect(result?.options.map((o) => o.label)).toContain("total");
    });

    it("shows number methods for loop index", () => {
      const scopeVars: ScopeVariable[] = [{ name: "idx", type: "loop-index", arrayPath: "orders" }];
      const source = createExpressionCompletionSource(testData, scopeVars);
      const context = createMockContext("idx.");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("toFixed");
      expect(result?.options.map((o) => o.label)).toContain("toString");
    });
  });

  describe("method completion apply", () => {
    it("adds () to method completions", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("customer.name.");
      const result = source(context as any);

      const toLowerOption = result?.options.find((o) => o.label === "toLowerCase");
      expect(toLowerOption?.apply).toBe("toLowerCase()");
    });

    it("does not add () to property completions", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("customer.name.");
      const result = source(context as any);

      const lengthOption = result?.options.find((o) => o.label === "length");
      expect(lengthOption?.apply).toBe("length");
    });
  });

  describe("edge cases", () => {
    it("returns null for unknown path", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("nonexistent.");
      const result = source(context as any);

      expect(result).toBeNull();
    });

    it("handles deep nesting", () => {
      const deepData = { a: { b: { c: { d: "deep" } } } };
      const source = createExpressionCompletionSource(deepData, []);
      const context = createMockContext("a.b.c.d.");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("toLowerCase");
    });

    it("handles expression after operator", () => {
      const source = createExpressionCompletionSource(testData, []);
      const context = createMockContext("count + cus");
      const result = source(context as any);

      expect(result).not.toBeNull();
      expect(result?.options.map((o) => o.label)).toContain("customer");
    });
  });
});
