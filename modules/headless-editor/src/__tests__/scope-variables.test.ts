import { describe, it, expect } from "vitest";
import { TemplateEditor } from "../editor";
import type { Template } from "../types";

describe("getScopeVariables()", () => {
  it("should return empty array for root-level block", () => {
    const editor = new TemplateEditor();
    const block = editor.addBlock("text")!;

    const vars = editor.getScopeVariables(block.id);
    expect(vars).toEqual([]);
  });

  it("should return empty array for non-existent block", () => {
    const editor = new TemplateEditor();
    const vars = editor.getScopeVariables("nonexistent");
    expect(vars).toEqual([]);
  });

  it("should collect variables from a single enclosing loop", () => {
    const editor = new TemplateEditor();
    const loop = editor.addBlock("loop")!;
    editor.updateBlock(loop.id, {
      expression: { raw: "items", language: "jsonata" },
      itemAlias: "item",
      indexAlias: "idx",
    });
    const textBlock = editor.addBlock("text", loop.id)!;

    const vars = editor.getScopeVariables(textBlock.id);
    expect(vars).toHaveLength(2);
    expect(vars[0]).toEqual({ name: "item", type: "loop-item", arrayPath: "items" });
    expect(vars[1]).toEqual({ name: "idx", type: "loop-index", arrayPath: "items" });
  });

  it("should collect variables from nested loops (outermost first)", () => {
    const editor = new TemplateEditor();

    const outerLoop = editor.addBlock("loop")!;
    editor.updateBlock(outerLoop.id, {
      expression: { raw: "users", language: "jsonata" },
      itemAlias: "user",
      indexAlias: "userIdx",
    });

    const innerLoop = editor.addBlock("loop", outerLoop.id)!;
    editor.updateBlock(innerLoop.id, {
      expression: { raw: "user.orders", language: "jsonata" },
      itemAlias: "order",
      indexAlias: "orderIdx",
    });

    const textBlock = editor.addBlock("text", innerLoop.id)!;

    const vars = editor.getScopeVariables(textBlock.id);
    expect(vars).toHaveLength(4);
    // Outermost first
    expect(vars[0]!.name).toBe("user");
    expect(vars[1]!.name).toBe("userIdx");
    expect(vars[2]!.name).toBe("order");
    expect(vars[3]!.name).toBe("orderIdx");
  });

  it("should skip loops without indexAlias", () => {
    const editor = new TemplateEditor();
    const loop = editor.addBlock("loop")!;
    editor.updateBlock(loop.id, {
      expression: { raw: "items", language: "jsonata" },
      itemAlias: "item",
      indexAlias: undefined,
    });
    const textBlock = editor.addBlock("text", loop.id)!;

    const vars = editor.getScopeVariables(textBlock.id);
    expect(vars).toHaveLength(1);
    expect(vars[0]).toEqual({ name: "item", type: "loop-item", arrayPath: "items" });
  });

  it("should handle block inside container inside loop", () => {
    const editor = new TemplateEditor();
    const loop = editor.addBlock("loop")!;
    editor.updateBlock(loop.id, {
      expression: { raw: "rows", language: "jsonata" },
      itemAlias: "row",
    });
    const container = editor.addBlock("container", loop.id)!;
    const textBlock = editor.addBlock("text", container.id)!;

    const vars = editor.getScopeVariables(textBlock.id);
    expect(vars).toHaveLength(1);
    expect(vars[0]!.name).toBe("row");
  });
});

describe("getExpressionContext()", () => {
  it("should return test data for root-level block", () => {
    const editor = new TemplateEditor();
    const block = editor.addBlock("text")!;

    const context = editor.getExpressionContext(block.id);
    // Should contain the default test data
    expect(context).toHaveProperty("name");
    expect(context).toHaveProperty("email");
  });

  it("should include scope variables in context", () => {
    const editor = new TemplateEditor();
    const loop = editor.addBlock("loop")!;
    editor.updateBlock(loop.id, {
      expression: { raw: "order.items", language: "jsonata" },
      itemAlias: "item",
      indexAlias: "idx",
    });
    const textBlock = editor.addBlock("text", loop.id)!;

    const context = editor.getExpressionContext(textBlock.id);
    // Has scope variables
    expect(context).toHaveProperty("item");
    expect(context).toHaveProperty("idx", 0);
    // Also has test data
    expect(context).toHaveProperty("name");
  });

  it("should return empty-like context for non-existent block", () => {
    const editor = new TemplateEditor();
    const context = editor.getExpressionContext("nonexistent");
    // Should still have test data
    expect(context).toHaveProperty("name");
  });
});
