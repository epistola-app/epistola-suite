import { describe, it, expect } from "vitest";
import {
  textBlockDefinition,
  containerBlockDefinition,
  conditionalBlockDefinition,
  loopBlockDefinition,
  columnsBlockDefinition,
  tableBlockDefinition,
  pageBreakBlockDefinition,
  pageHeaderBlockDefinition,
  pageFooterBlockDefinition,
} from "../blocks/definitions";
import { TemplateEditor } from "../editor";
import type { BlockDefinition } from "../types";

describe("render hints in default block definitions", () => {
  const definitions = [
    { def: textBlockDefinition, label: "Text", category: "Content" },
    { def: containerBlockDefinition, label: "Container", category: "Layout" },
    {
      def: conditionalBlockDefinition,
      label: "Conditional",
      category: "Logic",
    },
    { def: loopBlockDefinition, label: "Loop", category: "Logic" },
    { def: columnsBlockDefinition, label: "Columns", category: "Layout" },
    { def: tableBlockDefinition, label: "Table", category: "Layout" },
    { def: pageBreakBlockDefinition, label: "Page Break", category: "Layout" },
    {
      def: pageHeaderBlockDefinition,
      label: "Page Header",
      category: "Layout",
    },
    {
      def: pageFooterBlockDefinition,
      label: "Page Footer",
      category: "Layout",
    },
  ];

  for (const { def, label, category } of definitions) {
    it(`${def.type} should have label "${label}"`, () => {
      expect(def.label).toBe(label);
    });

    it(`${def.type} should have a non-empty icon`, () => {
      expect(def.icon).toBeTruthy();
      expect(typeof def.icon).toBe("string");
    });

    it(`${def.type} should have category "${category}"`, () => {
      expect(def.category).toBe(category);
    });
  }

  it("all 9 default block definitions should have render hints", () => {
    const defaultDefinitions: Record<string, BlockDefinition> = {
      text: textBlockDefinition,
      container: containerBlockDefinition,
      conditional: conditionalBlockDefinition,
      loop: loopBlockDefinition,
      columns: columnsBlockDefinition,
      table: tableBlockDefinition,
      pagebreak: pageBreakBlockDefinition,
      pageheader: pageHeaderBlockDefinition,
      pagefooter: pageFooterBlockDefinition,
    };
    for (const [type, def] of Object.entries(defaultDefinitions)) {
      expect(def.label, `${type} missing label`).toBeTruthy();
      expect(def.icon, `${type} missing icon`).toBeTruthy();
      expect(def.category, `${type} missing category`).toBeTruthy();
    }
  });
});

describe("custom block definition without hints", () => {
  it("should be accepted without error", () => {
    const customDef: BlockDefinition = {
      type: "custom",
      create: (id) => ({ id, type: "custom" as "text", content: null }),
      validate: () => ({ valid: true, errors: [] }),
      constraints: {
        canHaveChildren: false,
        allowedChildTypes: [],
        canBeDragged: true,
        canBeNested: true,
        allowedParentTypes: null,
      },
      // No label, icon, or category
    };

    const editor = new TemplateEditor({
      plugins: [
        {
          type: customDef.type,
          create: customDef.create,
          validate: customDef.validate,
          constraints: customDef.constraints,
        },
      ],
    });

    // Should not throw
    expect(editor.getBlockDefinition("custom")).toBeDefined();
    expect(editor.getBlockDefinition("custom")!.label).toBeUndefined();
    expect(editor.getBlockDefinition("custom")!.icon).toBeUndefined();
    expect(editor.getBlockDefinition("custom")!.category).toBeUndefined();
  });
});
