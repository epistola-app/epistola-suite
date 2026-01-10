import { describe, it, expect, beforeEach } from "vitest";
import { JsonataEvaluator } from "./JsonataEvaluator";

describe("JsonataEvaluator", () => {
  let evaluator: JsonataEvaluator;

  beforeEach(() => {
    evaluator = new JsonataEvaluator();
  });

  describe("properties", () => {
    it("has correct type", () => {
      expect(evaluator.type).toBe("jsonata");
    });

    it("has correct name", () => {
      expect(evaluator.name).toBe("JSONata");
    });

    it("is marked as sandboxed", () => {
      expect(evaluator.isSandboxed).toBe(true);
    });
  });

  describe("initialize", () => {
    it("completes without error", async () => {
      await expect(evaluator.initialize()).resolves.toBeUndefined();
    });
  });

  describe("dispose", () => {
    it("completes without error", () => {
      expect(() => evaluator.dispose()).not.toThrow();
    });
  });

  describe("evaluate", () => {
    it("returns undefined for empty expression", async () => {
      const result = await evaluator.evaluate("", {});

      expect(result.success).toBe(true);
      expect(result.value).toBeUndefined();
    });

    it("returns undefined for whitespace-only expression", async () => {
      const result = await evaluator.evaluate("   ", {});

      expect(result.success).toBe(true);
      expect(result.value).toBeUndefined();
    });

    it("evaluates simple property access", async () => {
      const context = { name: "John" };

      const result = await evaluator.evaluate("name", context);

      expect(result.success).toBe(true);
      expect(result.value).toBe("John");
    });

    it("evaluates nested property access", async () => {
      const context = { user: { name: "John", age: 30 } };

      const result = await evaluator.evaluate("user.name", context);

      expect(result.success).toBe(true);
      expect(result.value).toBe("John");
    });

    it("evaluates array access", async () => {
      const context = { items: ["a", "b", "c"] };

      const result = await evaluator.evaluate("items[0]", context);

      expect(result.success).toBe(true);
      expect(result.value).toBe("a");
    });

    it("evaluates array mapping", async () => {
      const context = { items: [{ price: 10 }, { price: 20 }, { price: 30 }] };

      const result = await evaluator.evaluate("items.price", context);

      expect(result.success).toBe(true);
      // JSONata returns arrays with a 'sequence' property, so we check values
      expect([...result.value]).toEqual([10, 20, 30]);
    });

    it("evaluates array filtering", async () => {
      const context = { items: [{ active: true }, { active: false }, { active: true }] };

      const result = await evaluator.evaluate("items[active].active", context);

      expect(result.success).toBe(true);
      // JSONata returns arrays with a 'sequence' property, so we check values
      expect([...result.value]).toEqual([true, true]);
    });

    it("evaluates arithmetic expressions", async () => {
      const context = { a: 10, b: 5 };

      const result = await evaluator.evaluate("a + b", context);

      expect(result.success).toBe(true);
      expect(result.value).toBe(15);
    });

    it("evaluates string concatenation", async () => {
      const context = { first: "John", last: "Doe" };

      const result = await evaluator.evaluate('first & " " & last', context);

      expect(result.success).toBe(true);
      expect(result.value).toBe("John Doe");
    });

    it("evaluates conditional expression", async () => {
      const context = { active: true };

      const result = await evaluator.evaluate('active ? "Yes" : "No"', context);

      expect(result.success).toBe(true);
      expect(result.value).toBe("Yes");
    });

    it("evaluates $sum aggregation", async () => {
      const context = { items: [{ price: 10 }, { price: 20 }, { price: 30 }] };

      const result = await evaluator.evaluate("$sum(items.price)", context);

      expect(result.success).toBe(true);
      expect(result.value).toBe(60);
    });

    it("evaluates $count aggregation", async () => {
      const context = { items: [1, 2, 3, 4, 5] };

      const result = await evaluator.evaluate("$count(items)", context);

      expect(result.success).toBe(true);
      expect(result.value).toBe(5);
    });

    it("evaluates $average aggregation", async () => {
      const context = { values: [10, 20, 30] };

      const result = await evaluator.evaluate("$average(values)", context);

      expect(result.success).toBe(true);
      expect(result.value).toBe(20);
    });

    it("returns undefined for missing property", async () => {
      const context = { name: "John" };

      const result = await evaluator.evaluate("missing", context);

      expect(result.success).toBe(true);
      expect(result.value).toBeUndefined();
    });

    it("returns error for invalid syntax", async () => {
      const result = await evaluator.evaluate("invalid[[[", {});

      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
    });

    it("returns error for invalid function call", async () => {
      const result = await evaluator.evaluate("$unknownFunction()", {});

      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
    });

    it("handles complex nested expressions", async () => {
      const context = {
        order: {
          items: [
            { name: "Item 1", price: 100, quantity: 2 },
            { name: "Item 2", price: 50, quantity: 3 },
          ],
        },
      };

      const result = await evaluator.evaluate(
        "$sum(order.items.(price * quantity))",
        context
      );

      expect(result.success).toBe(true);
      expect(result.value).toBe(350);
    });

    it("handles empty context", async () => {
      const result = await evaluator.evaluate("$now()", {});

      expect(result.success).toBe(true);
      // $now() returns a timestamp
      expect(typeof result.value).toBe("string");
    });
  });
});
