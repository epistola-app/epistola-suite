import { describe, it, expect } from "vitest";
import {
  textBlockPlugin,
  containerBlockPlugin,
  conditionalBlockPlugin,
  loopBlockPlugin,
  columnsBlockPlugin,
  tableBlockPlugin,
  pageBreakBlockPlugin,
  pageHeaderBlockPlugin,
  pageFooterBlockPlugin,
} from "../blocks/plugins";
import { TemplateEditor } from "../editor";
import type { BlockPlugin } from "../types";

describe("render hints in default block plugins", () => {
  const plugins = [
    { plugin: textBlockPlugin, label: "Text", category: "Content" },
    { plugin: containerBlockPlugin, label: "Container", category: "Layout" },
    {
      plugin: conditionalBlockPlugin,
      label: "Conditional",
      category: "Logic",
    },
    { plugin: loopBlockPlugin, label: "Loop", category: "Logic" },
    { plugin: columnsBlockPlugin, label: "Columns", category: "Layout" },
    { plugin: tableBlockPlugin, label: "Table", category: "Layout" },
    { plugin: pageBreakBlockPlugin, label: "Page Break", category: "Layout" },
    {
      plugin: pageHeaderBlockPlugin,
      label: "Page Header",
      category: "Layout",
    },
    {
      plugin: pageFooterBlockPlugin,
      label: "Page Footer",
      category: "Layout",
    },
  ];

  for (const { plugin, label, category } of plugins) {
    it(`${plugin.type} should have label "${label}"`, () => {
      expect(plugin.label).toBe(label);
    });

    it(`${plugin.type} should have a non-empty icon`, () => {
      expect(plugin.icon).toBeTruthy();
      expect(typeof plugin.icon).toBe("string");
    });

    it(`${plugin.type} should have category "${category}"`, () => {
      expect(plugin.category).toBe(category);
    });
  }

  it("all 9 default block plugins should have render hints", () => {
    const defaultPlugins: Record<string, BlockPlugin> = {
      text: textBlockPlugin,
      container: containerBlockPlugin,
      conditional: conditionalBlockPlugin,
      loop: loopBlockPlugin,
      columns: columnsBlockPlugin,
      table: tableBlockPlugin,
      pagebreak: pageBreakBlockPlugin,
      pageheader: pageHeaderBlockPlugin,
      pagefooter: pageFooterBlockPlugin,
    };
    for (const [type, plugin] of Object.entries(defaultPlugins)) {
      expect(plugin.label, `${type} missing label`).toBeTruthy();
      expect(plugin.icon, `${type} missing icon`).toBeTruthy();
      expect(plugin.category, `${type} missing category`).toBeTruthy();
    }
  });
});

describe("custom block plugin without hints", () => {
  it("should be accepted without error", () => {
    const customPlugin: BlockPlugin = {
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
      plugins: [customPlugin],
    });

    // Should not throw
    expect(editor.getBlockDefinition("custom")).toBeDefined();
    expect(editor.getBlockDefinition("custom")!.label).toBeUndefined();
    expect(editor.getBlockDefinition("custom")!.icon).toBeUndefined();
    expect(editor.getBlockDefinition("custom")!.category).toBeUndefined();
  });
});
