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
    { definition: textBlockDefinition, label: "Text", category: "Content" },
    {
      definition: containerBlockDefinition,
      label: "Container",
      category: "Layout",
    },
    {
      definition: conditionalBlockDefinition,
      label: "Conditional",
      category: "Logic",
    },
    { definition: loopBlockDefinition, label: "Loop", category: "Logic" },
    {
      definition: columnsBlockDefinition,
      label: "Columns",
      category: "Layout",
    },
    { definition: tableBlockDefinition, label: "Table", category: "Layout" },
    {
      definition: pageBreakBlockDefinition,
      label: "Page Break",
      category: "Layout",
    },
    {
      definition: pageHeaderBlockDefinition,
      label: "Page Header",
      category: "Layout",
    },
    {
      definition: pageFooterBlockDefinition,
      label: "Page Footer",
      category: "Layout",
    },
  ];

  for (const { definition, label, category } of definitions) {
    it(`${definition.type} should have label "${label}"`, () => {
      expect(definition.label).toBe(label);
    });

    it(`${definition.type} should have a non-empty icon`, () => {
      expect(definition.icon).toBeTruthy();
      expect(typeof definition.icon).toBe("string");
    });

    it(`${definition.type} should have category "${category}"`, () => {
      expect(definition.category).toBe(category);
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
    for (const [type, definition] of Object.entries(defaultDefinitions)) {
      expect(definition.label, `${type} missing label`).toBeTruthy();
      expect(definition.icon, `${type} missing icon`).toBeTruthy();
      expect(definition.category, `${type} missing category`).toBeTruthy();
    }
  });
});

describe("built-in block definitions", () => {
  it("should be available from editor registry", () => {
    const editor = new TemplateEditor();

    expect(editor.getBlockDefinition("text")).toBeDefined();
    expect(editor.getBlockDefinition("text")!.label).toBe("Text");
    expect(editor.getBlockDefinition("text")!.icon).toBe("text");
    expect(editor.getBlockDefinition("text")!.category).toBe("Content");
  });
});
