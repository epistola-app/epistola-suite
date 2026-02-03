import { describe, it, expect, beforeEach } from "vitest";
import { TemplateEditor } from "../editor.js";
import type { ConditionalBlock, LoopBlock, TipTapContent } from "../types.js";
import {
  evaluateJsonata,
  evaluateJsonataBoolean,
  evaluateJsonataArray,
  evaluateJsonataString,
} from "../evaluator/index.js";

describe("JSONata Evaluator", () => {
  const testData = {
    customer: {
      name: "John Doe",
      email: "john@example.com",
      active: true,
    },
    items: [
      { name: "Item 1", price: 10 },
      { name: "Item 2", price: 20 },
      { name: "Item 3", price: 30 },
    ],
    total: 60,
    empty: [],
  };

  describe("evaluateJsonata", () => {
    it("should evaluate simple path", async () => {
      const result = await evaluateJsonata("customer.name", testData);
      expect(result.success).toBe(true);
      expect(result.value).toBe("John Doe");
    });

    it("should evaluate nested path", async () => {
      const result = await evaluateJsonata("customer.email", testData);
      expect(result.success).toBe(true);
      expect(result.value).toBe("john@example.com");
    });

    it("should evaluate array access", async () => {
      const result = await evaluateJsonata("items[0].name", testData);
      expect(result.success).toBe(true);
      expect(result.value).toBe("Item 1");
    });

    it("should evaluate array", async () => {
      const result = await evaluateJsonata("items", testData);
      expect(result.success).toBe(true);
      expect(Array.isArray(result.value)).toBe(true);
      expect((result.value as unknown[]).length).toBe(3);
    });

    it("should handle empty expression", async () => {
      const result = await evaluateJsonata("", testData);
      expect(result.success).toBe(true);
      expect(result.value).toBeUndefined();
    });

    it("should handle whitespace-only expression", async () => {
      const result = await evaluateJsonata("   ", testData);
      expect(result.success).toBe(true);
      expect(result.value).toBeUndefined();
    });

    it("should return error for invalid expression", async () => {
      const result = await evaluateJsonata("invalid.{.path", testData);
      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
    });

    it("should return undefined for non-existent path", async () => {
      const result = await evaluateJsonata("nonexistent.path", testData);
      expect(result.success).toBe(true);
      expect(result.value).toBeUndefined();
    });

    it("should evaluate JSONata functions", async () => {
      const result = await evaluateJsonata("$sum(items.price)", testData);
      expect(result.success).toBe(true);
      expect(result.value).toBe(60);
    });

    it("should evaluate string concatenation", async () => {
      const result = await evaluateJsonata('customer.name & " <" & customer.email & ">"', testData);
      expect(result.success).toBe(true);
      expect(result.value).toBe("John Doe <john@example.com>");
    });
  });

  describe("evaluateJsonataBoolean", () => {
    it("should return true for truthy value", async () => {
      expect(await evaluateJsonataBoolean("customer.active", testData)).toBe(true);
    });

    it("should return false for falsy value", async () => {
      expect(await evaluateJsonataBoolean("customer.inactive", testData)).toBe(false);
    });

    it("should return false for non-existent path", async () => {
      expect(await evaluateJsonataBoolean("nonexistent", testData)).toBe(false);
    });

    it("should coerce non-empty array to true", async () => {
      expect(await evaluateJsonataBoolean("items", testData)).toBe(true);
    });

    it("should coerce empty array to false", async () => {
      expect(await evaluateJsonataBoolean("empty", testData)).toBe(false);
    });

    it("should return false for invalid expression", async () => {
      expect(await evaluateJsonataBoolean("invalid.{.path", testData)).toBe(false);
    });
  });

  describe("evaluateJsonataArray", () => {
    it("should return array for array expression", async () => {
      const result = await evaluateJsonataArray("items", testData);
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBe(3);
    });

    it("should return empty array for non-array value", async () => {
      const result = await evaluateJsonataArray("customer.name", testData);
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBe(0);
    });

    it("should return empty array for invalid expression", async () => {
      const result = await evaluateJsonataArray("invalid.{.path", testData);
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBe(0);
    });

    it("should return empty array for non-existent path", async () => {
      const result = await evaluateJsonataArray("nonexistent", testData);
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBe(0);
    });
  });

  describe("evaluateJsonataString", () => {
    it("should return string value", async () => {
      expect(await evaluateJsonataString("customer.name", testData)).toBe("John Doe");
    });

    it("should coerce number to string", async () => {
      expect(await evaluateJsonataString("total", testData)).toBe("60");
    });

    it("should coerce boolean to string", async () => {
      expect(await evaluateJsonataString("customer.active", testData)).toBe("true");
    });

    it("should return empty string for undefined", async () => {
      expect(await evaluateJsonataString("nonexistent", testData)).toBe("");
    });

    it("should return empty string for invalid expression", async () => {
      expect(await evaluateJsonataString("invalid.{.path", testData)).toBe("");
    });
  });
});

describe("TemplateEditor Expression Evaluation", () => {
  let editor: TemplateEditor;

  const testData = {
    customer: {
      name: "Jane Smith",
      active: true,
      premium: false,
    },
    orders: [
      { id: 1, total: 100 },
      { id: 2, total: 200 },
    ],
  };

  beforeEach(() => {
    editor = new TemplateEditor();
    editor.setDataExamples([
      { id: "test", name: "Test Data", data: testData },
    ]);
  });

  describe("evaluateExpression", () => {
    it("should evaluate expression against test data", async () => {
      const result = await editor.evaluateExpression("customer.name");
      expect(result.success).toBe(true);
      expect(result.value).toBe("Jane Smith");
    });

    it("should include scope variables", async () => {
      const result = await editor.evaluateExpression("item.total", { item: { total: 150 } });
      expect(result.success).toBe(true);
      expect(result.value).toBe(150);
    });

    it("should allow scope to override test data", async () => {
      const result = await editor.evaluateExpression("customer.name", { customer: { name: "Override" } });
      expect(result.success).toBe(true);
      expect(result.value).toBe("Override");
    });
  });

  describe("evaluateCondition", () => {
    it("should evaluate conditional block condition", async () => {
      const block = editor.addBlock("conditional") as ConditionalBlock;
      editor.updateBlock(block.id, {
        condition: { raw: "customer.active", language: "jsonata" },
      });

      expect(await editor.evaluateCondition(block.id)).toBe(true);
    });

    it("should respect inverse flag", async () => {
      const block = editor.addBlock("conditional") as ConditionalBlock;
      editor.updateBlock(block.id, {
        condition: { raw: "customer.premium", language: "jsonata" },
        inverse: true,
      });

      // customer.premium is false, inverse is true, so result should be true
      expect(await editor.evaluateCondition(block.id)).toBe(true);
    });

    it("should respect preview override - show", async () => {
      const block = editor.addBlock("conditional") as ConditionalBlock;
      editor.updateBlock(block.id, {
        condition: { raw: "customer.premium", language: "jsonata" },
      });

      editor.setPreviewOverride("conditionals", block.id, "show");
      expect(await editor.evaluateCondition(block.id)).toBe(true);
    });

    it("should respect preview override - hide", async () => {
      const block = editor.addBlock("conditional") as ConditionalBlock;
      editor.updateBlock(block.id, {
        condition: { raw: "customer.active", language: "jsonata" },
      });

      editor.setPreviewOverride("conditionals", block.id, "hide");
      expect(await editor.evaluateCondition(block.id)).toBe(false);
    });

    it("should return false for non-existent block", async () => {
      expect(await editor.evaluateCondition("nonexistent")).toBe(false);
    });

    it("should return false for non-conditional block", async () => {
      const block = editor.addBlock("text")!;
      expect(await editor.evaluateCondition(block.id)).toBe(false);
    });
  });

  describe("evaluateLoopArray", () => {
    it("should return array from loop expression", async () => {
      const block = editor.addBlock("loop") as LoopBlock;
      editor.updateBlock(block.id, {
        expression: { raw: "orders", language: "jsonata" },
      });

      const result = await editor.evaluateLoopArray(block.id);
      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBe(2);
    });

    it("should return empty array for non-existent block", async () => {
      expect(await editor.evaluateLoopArray("nonexistent")).toEqual([]);
    });

    it("should return empty array for non-loop block", async () => {
      const block = editor.addBlock("text")!;
      expect(await editor.evaluateLoopArray(block.id)).toEqual([]);
    });
  });

  describe("getLoopIterationCount", () => {
    it("should return actual array length", async () => {
      const block = editor.addBlock("loop") as LoopBlock;
      editor.updateBlock(block.id, {
        expression: { raw: "orders", language: "jsonata" },
      });

      expect(await editor.getLoopIterationCount(block.id)).toBe(2);
    });

    it("should respect preview override", async () => {
      const block = editor.addBlock("loop") as LoopBlock;
      editor.updateBlock(block.id, {
        expression: { raw: "orders", language: "jsonata" },
      });

      editor.setPreviewOverride("loops", block.id, 5);
      expect(await editor.getLoopIterationCount(block.id)).toBe(5);
    });
  });

  describe("buildLoopIterationContext", () => {
    it("should build context with item alias", async () => {
      const block = editor.addBlock("loop") as LoopBlock;
      editor.updateBlock(block.id, {
        expression: { raw: "orders", language: "jsonata" },
        itemAlias: "order",
      });

      const context = await editor.buildLoopIterationContext(block.id, 0);
      expect(context.order).toEqual({ id: 1, total: 100 });
    });

    it("should include index alias when defined", async () => {
      const block = editor.addBlock("loop") as LoopBlock;
      editor.updateBlock(block.id, {
        expression: { raw: "orders", language: "jsonata" },
        itemAlias: "order",
        indexAlias: "i",
      });

      const context = await editor.buildLoopIterationContext(block.id, 1);
      expect(context.order).toEqual({ id: 2, total: 200 });
      expect(context.i).toBe(1);
    });

    it("should preserve parent scope", async () => {
      const block = editor.addBlock("loop") as LoopBlock;
      editor.updateBlock(block.id, {
        expression: { raw: "orders", language: "jsonata" },
        itemAlias: "order",
      });

      const parentScope = { parentVar: "test" };
      const context = await editor.buildLoopIterationContext(block.id, 0, parentScope);
      expect(context.parentVar).toBe("test");
      expect(context.order).toEqual({ id: 1, total: 100 });
    });
  });

  describe("interpolateText", () => {
    it("should interpolate simple expression", async () => {
      const content: TipTapContent = {
        type: "doc",
        content: [
          {
            type: "paragraph",
            content: [
              { type: "text", text: "Hello, {{customer.name}}!" },
            ],
          },
        ],
      };

      const result = await editor.interpolateText(content);
      expect(result).toBe("Hello, Jane Smith!");
    });

    it("should interpolate multiple expressions", async () => {
      const content: TipTapContent = {
        type: "doc",
        content: [
          {
            type: "paragraph",
            content: [
              { type: "text", text: "{{customer.name}} has {{$count(orders)}} orders" },
            ],
          },
        ],
      };

      const result = await editor.interpolateText(content);
      expect(result).toBe("Jane Smith has 2 orders");
    });

    it("should use scope variables", async () => {
      const content: TipTapContent = {
        type: "doc",
        content: [
          {
            type: "paragraph",
            content: [
              { type: "text", text: "Order #{{order.id}}: ${{order.total}}" },
            ],
          },
        ],
      };

      const result = await editor.interpolateText(content, { order: { id: 1, total: 100 } });
      expect(result).toBe("Order #1: $100");
    });

    it("should handle null content", async () => {
      expect(await editor.interpolateText(null)).toBe("");
    });

    it("should handle expression nodes from React editor", async () => {
      const content: TipTapContent = {
        type: "doc",
        content: [
          {
            type: "paragraph",
            content: [
              { type: "text", text: "Hello, " },
              { type: "expression", attrs: { expression: "customer.name" } },
              { type: "text", text: "!" },
            ],
          },
        ],
      };

      const result = await editor.interpolateText(content);
      expect(result).toBe("Hello, Jane Smith!");
    });
  });
});
