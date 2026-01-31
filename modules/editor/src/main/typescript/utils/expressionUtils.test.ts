import {describe, expect, it, vi} from "vitest";
import {extractExpressions, getRootPaths, normalizeArrayPath, pathMatchesSchema,} from "./expressionUtils";
import type {Block, ColumnsBlock, ConditionalBlock, LoopBlock, TableBlock, TextBlock,} from "../types/template";

describe("normalizeArrayPath", () => {
  it("normalizes numbered array access", () => {
    expect(normalizeArrayPath("items[0].price")).toBe("items[].price");
    expect(normalizeArrayPath("items[123].name")).toBe("items[].name");
  });

  it("normalizes multiple array accesses", () => {
    expect(normalizeArrayPath("data[0].items[5].price")).toBe("data[].items[].price");
  });

  it("handles paths without array access", () => {
    expect(normalizeArrayPath("customer.name")).toBe("customer.name");
    expect(normalizeArrayPath("total")).toBe("total");
  });

  it("handles empty brackets", () => {
    expect(normalizeArrayPath("items[].price")).toBe("items[].price");
  });

  it("handles whitespace in brackets", () => {
    expect(normalizeArrayPath("items[ 0 ].price")).toBe("items[].price");
  });
});

describe("getRootPaths", () => {
  it("extracts root from simple paths", () => {
    const expressions = new Set(["customer.name", "customer.email", "total"]);
    const roots = getRootPaths(expressions);

    expect(roots.has("customer")).toBe(true);
    expect(roots.has("total")).toBe(true);
    expect(roots.size).toBe(2);
  });

  it("extracts root from array paths", () => {
    const expressions = new Set(["items[].price", "items[].name"]);
    const roots = getRootPaths(expressions);

    expect(roots.has("items")).toBe(true);
    expect(roots.size).toBe(1);
  });

  it("returns empty set for empty input", () => {
    const roots = getRootPaths(new Set());
    expect(roots.size).toBe(0);
  });
});

describe("pathMatchesSchema", () => {
  it("matches exact paths", () => {
    const schemaPaths = new Set(["customer.name", "total"]);

    expect(pathMatchesSchema("customer.name", schemaPaths)).toBe(true);
    expect(pathMatchesSchema("total", schemaPaths)).toBe(true);
  });

  it("matches parent paths", () => {
    const schemaPaths = new Set(["customer"]);

    expect(pathMatchesSchema("customer.name", schemaPaths)).toBe(true);
    expect(pathMatchesSchema("customer.email", schemaPaths)).toBe(true);
  });

  it("matches array paths", () => {
    const schemaPaths = new Set(["items[]", "items[].price"]);

    expect(pathMatchesSchema("items[].price", schemaPaths)).toBe(true);
    expect(pathMatchesSchema("items[]", schemaPaths)).toBe(true);
  });

  it("matches expression path to schema array notation", () => {
    // Schema has items[] but expression uses items.name (without brackets)
    const schemaPaths = new Set(["items[]"]);

    expect(pathMatchesSchema("items.name", schemaPaths)).toBe(true);
  });

  it("matches nested path with array notation in schema", () => {
    const schemaPaths = new Set(["data.items[]"]);

    expect(pathMatchesSchema("data.items.price", schemaPaths)).toBe(true);
  });

  it("returns false for non-matching paths", () => {
    const schemaPaths = new Set(["customer.name"]);

    expect(pathMatchesSchema("unknown.field", schemaPaths)).toBe(false);
  });

  it("handles empty schema paths", () => {
    expect(pathMatchesSchema("customer.name", new Set())).toBe(false);
  });
});

describe("extractExpressions", () => {
  it("returns empty set for empty blocks", () => {
    const expressions = extractExpressions([]);
    expect(expressions.size).toBe(0);
  });

  it("extracts from text block with expression node", () => {
    const textBlock: TextBlock = {
      id: "1",
      type: "text",
      content: {
        type: "doc",
        content: [
          {
            type: "paragraph",
            content: [
              { type: "text", text: "Hello " },
              { type: "expression", attrs: { expression: "customer.name" } },
            ],
          },
        ],
      },
    };

    const expressions = extractExpressions([textBlock]);
    expect(expressions.has("customer.name")).toBe(true);
  });

  it("extracts from conditional block condition", () => {
    const conditionalBlock: ConditionalBlock = {
      id: "1",
      type: "conditional",
      condition: { raw: "customer.active" },
      children: [],
    };

    const expressions = extractExpressions([conditionalBlock]);
    expect(expressions.has("customer.active")).toBe(true);
  });

  it("extracts from loop block expression", () => {
    const loopBlock: LoopBlock = {
      id: "1",
      type: "loop",
      expression: { raw: "items" },
      itemAlias: "item",
      children: [],
    };

    const expressions = extractExpressions([loopBlock]);
    expect(expressions.has("items")).toBe(true);
  });

  it("extracts from nested blocks in container", () => {
    const containerBlock: Block = {
      id: "1",
      type: "container",
      children: [
        {
          id: "2",
          type: "text",
          content: {
            type: "doc",
            content: [
              {
                type: "paragraph",
                content: [{ type: "expression", attrs: { expression: "nested.value" } }],
              },
            ],
          },
        } as TextBlock,
      ],
    };

    const expressions = extractExpressions([containerBlock]);
    expect(expressions.has("nested.value")).toBe(true);
  });

  it("extracts from columns block", () => {
    const columnsBlock: ColumnsBlock = {
      id: "1",
      type: "columns",
      columns: [
        {
          id: "col1",
          size: 50,
          children: [
            {
              id: "2",
              type: "text",
              content: {
                type: "doc",
                content: [
                  {
                    type: "paragraph",
                    content: [{ type: "expression", attrs: { expression: "col1.data" } }],
                  },
                ],
              },
            } as TextBlock,
          ],
        },
        {
          id: "col2",
          size: 50,
          children: [
            {
              id: "3",
              type: "text",
              content: {
                type: "doc",
                content: [
                  {
                    type: "paragraph",
                    content: [{ type: "expression", attrs: { expression: "col2.data" } }],
                  },
                ],
              },
            } as TextBlock,
          ],
        },
      ],
    };

    const expressions = extractExpressions([columnsBlock]);
    expect(expressions.has("col1.data")).toBe(true);
    expect(expressions.has("col2.data")).toBe(true);
  });

  it("extracts from table block", () => {
    const tableBlock: TableBlock = {
      id: "1",
      type: "table",
      rows: [
        {
          id: "row1",
          cells: [
            {
              id: "cell1",
              children: [
                {
                  id: "2",
                  type: "text",
                  content: {
                    type: "doc",
                    content: [
                      {
                        type: "paragraph",
                        content: [{ type: "expression", attrs: { expression: "table.cell" } }],
                      },
                    ],
                  },
                } as TextBlock,
              ],
            },
          ],
        },
      ],
    };

    const expressions = extractExpressions([tableBlock]);
    expect(expressions.has("table.cell")).toBe(true);
  });

  it("normalizes array indices in expressions", () => {
    const textBlock: TextBlock = {
      id: "1",
      type: "text",
      content: {
        type: "doc",
        content: [
          {
            type: "paragraph",
            content: [{ type: "expression", attrs: { expression: "items[0].price" } }],
          },
        ],
      },
    };

    const expressions = extractExpressions([textBlock]);
    expect(expressions.has("items[].price")).toBe(true);
  });

  it("handles null content in text block", () => {
    const textBlock: TextBlock = {
      id: "1",
      type: "text",
      content: {},
    };

    const expressions = extractExpressions([textBlock]);
    expect(expressions.size).toBe(0);
  });

  it("warns for unknown block type", () => {
    const consoleSpy = vi.spyOn(console, "warn").mockImplementation(() => {});

    const unknownBlock = {
      id: "1",
      type: "unknown-type",
    } as unknown as Block;

    extractExpressions([unknownBlock]);

    expect(consoleSpy).toHaveBeenCalledWith("Unknown block type: unknown-type");
    consoleSpy.mockRestore();
  });

  it("handles multiple nested expressions in same text block", () => {
    const textBlock: TextBlock = {
      id: "1",
      type: "text",
      content: {
        type: "doc",
        content: [
          {
            type: "paragraph",
            content: [
              { type: "expression", attrs: { expression: "customer.name" } },
              { type: "text", text: " - " },
              { type: "expression", attrs: { expression: "customer.email" } },
            ],
          },
        ],
      },
    };

    const expressions = extractExpressions([textBlock]);
    expect(expressions.has("customer.name")).toBe(true);
    expect(expressions.has("customer.email")).toBe(true);
    expect(expressions.size).toBe(2);
  });

  it("extracts from conditional block with nested children", () => {
    const conditionalBlock: ConditionalBlock = {
      id: "1",
      type: "conditional",
      condition: { raw: "user.isAdmin" },
      children: [
        {
          id: "2",
          type: "text",
          content: {
            type: "doc",
            content: [
              {
                type: "paragraph",
                content: [{ type: "expression", attrs: { expression: "admin.dashboard" } }],
              },
            ],
          },
        } as TextBlock,
      ],
    };

    const expressions = extractExpressions([conditionalBlock]);
    expect(expressions.has("user.isAdmin")).toBe(true);
    expect(expressions.has("admin.dashboard")).toBe(true);
  });

  it("extracts from loop block with nested children", () => {
    const loopBlock: LoopBlock = {
      id: "1",
      type: "loop",
      expression: { raw: "order.items" },
      itemAlias: "item",
      children: [
        {
          id: "2",
          type: "text",
          content: {
            type: "doc",
            content: [
              {
                type: "paragraph",
                content: [{ type: "expression", attrs: { expression: "item.price" } }],
              },
            ],
          },
        } as TextBlock,
      ],
    };

    const expressions = extractExpressions([loopBlock]);
    expect(expressions.has("order.items")).toBe(true);
    expect(expressions.has("item.price")).toBe(true);
  });
});
