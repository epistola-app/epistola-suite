/**
 * Tests for block definitions.
 */

import { describe, it, expect, beforeEach } from "vitest";
import {
  registry,
  registerAllBlocks,
  textBlockDef,
  containerBlockDef,
  conditionalBlockDef,
  loopBlockDef,
  columnsBlockDef,
  tableBlockDef,
  pageBreakBlockDef,
  pageHeaderBlockDef,
  pageFooterBlockDef,
  addColumn,
  removeColumn,
  setColumnSize,
  addTableRow,
  removeTableRow,
  addTableColumn,
  removeTableColumn,
  getCell,
} from "./index.ts";
import type { ColumnsBlock, TableBlock, Block } from "../types/template.ts";

describe("Block Definitions", () => {
  beforeEach(() => {
    registry.clear();
    registerAllBlocks();
  });

  describe("Registry", () => {
    it("should register all block types", () => {
      expect(registry.size).toBe(9);
      expect(registry.has("text")).toBe(true);
      expect(registry.has("container")).toBe(true);
      expect(registry.has("conditional")).toBe(true);
      expect(registry.has("loop")).toBe(true);
      expect(registry.has("columns")).toBe(true);
      expect(registry.has("table")).toBe(true);
      expect(registry.has("pagebreak")).toBe(true);
      expect(registry.has("pageheader")).toBe(true);
      expect(registry.has("pagefooter")).toBe(true);
    });

    it("should group blocks by category", () => {
      const content = registry.getByCategory("content");
      const structure = registry.getByCategory("structure");
      const logic = registry.getByCategory("logic");
      const layout = registry.getByCategory("layout");
      const data = registry.getByCategory("data");

      expect(content.map((d) => d.type)).toEqual(["text"]);
      expect(structure.map((d) => d.type)).toEqual(["container", "columns"]);
      expect(logic.map((d) => d.type)).toEqual(["conditional", "loop"]);
      expect(layout.map((d) => d.type)).toEqual([
        "pagebreak",
        "pageheader",
        "pagefooter",
      ]);
      expect(data.map((d) => d.type)).toEqual(["table"]);
    });
  });

  describe("Text Block", () => {
    it("should create default with empty document", () => {
      const block = textBlockDef.createDefault();
      expect(block.type).toBe("text");
      expect(block.id).toBeDefined();
      expect(block.content.type).toBe("doc");
    });

    it("should not have children", () => {
      expect(textBlockDef.getChildren).toBeUndefined();
      expect(textBlockDef.setChildren).toBeUndefined();
    });
  });

  describe("Container Block", () => {
    it("should create default with empty children", () => {
      const block = containerBlockDef.createDefault();
      expect(block.type).toBe("container");
      expect(block.children).toEqual([]);
    });

    it("should get and set children", () => {
      const block = containerBlockDef.createDefault();
      const child = textBlockDef.createDefault();

      expect(containerBlockDef.getChildren!(block)).toEqual([]);

      const updated = containerBlockDef.setChildren!(block, [child]);
      expect(containerBlockDef.getChildren!(updated)).toEqual([child]);
    });

    it("should contain any block type", () => {
      expect(containerBlockDef.canContain!("text")).toBe(true);
      expect(containerBlockDef.canContain!("container")).toBe(true);
      expect(containerBlockDef.canContain!("table")).toBe(true);
    });
  });

  describe("Conditional Block", () => {
    it("should create default with empty condition", () => {
      const block = conditionalBlockDef.createDefault();
      expect(block.type).toBe("conditional");
      expect(block.condition.raw).toBe("");
      expect(block.inverse).toBe(false);
      expect(block.children).toEqual([]);
    });

    it("should get and set children", () => {
      const block = conditionalBlockDef.createDefault();
      const child = textBlockDef.createDefault();

      const updated = conditionalBlockDef.setChildren!(block, [child]);
      expect(conditionalBlockDef.getChildren!(updated)).toEqual([child]);
    });
  });

  describe("Loop Block", () => {
    it("should create default with aliases", () => {
      const block = loopBlockDef.createDefault();
      expect(block.type).toBe("loop");
      expect(block.expression.raw).toBe("");
      expect(block.itemAlias).toBe("item");
      expect(block.indexAlias).toBe("index");
      expect(block.children).toEqual([]);
    });

    it("should get and set children", () => {
      const block = loopBlockDef.createDefault();
      const child = textBlockDef.createDefault();

      const updated = loopBlockDef.setChildren!(block, [child]);
      expect(loopBlockDef.getChildren!(updated)).toEqual([child]);
    });
  });

  describe("Columns Block", () => {
    it("should create default with 2 columns", () => {
      const block = columnsBlockDef.createDefault();
      expect(block.type).toBe("columns");
      expect(block.columns.length).toBe(2);
      expect(block.gap).toBe(16);
    });

    it("should get children from all columns", () => {
      const block = columnsBlockDef.createDefault();
      const child1 = textBlockDef.createDefault();
      const child2 = textBlockDef.createDefault();

      // Add children to different columns
      const updated: ColumnsBlock = {
        ...block,
        columns: [
          { ...block.columns[0], children: [child1] },
          { ...block.columns[1], children: [child2] },
        ],
      };

      const children = columnsBlockDef.getChildren!(updated) as Block[];
      expect(children).toEqual([child1, child2]);
    });

    it("should get container references", () => {
      const block = columnsBlockDef.createDefault();
      const containers = columnsBlockDef.getContainers(block);

      expect(containers.length).toBe(2);
      expect(containers[0].blockId).toBe(block.id);
      expect(containers[0].containerId).toBe(block.columns[0].id);
    });

    it("should get/set container children", () => {
      const block = columnsBlockDef.createDefault();
      const child = textBlockDef.createDefault();
      const columnId = block.columns[0].id;

      const updated = columnsBlockDef.setContainerChildren(block, columnId, [
        child,
      ]);
      const children = columnsBlockDef.getContainerChildren(updated, columnId);

      expect(children).toEqual([child]);
    });

    it("should add column", () => {
      const block = columnsBlockDef.createDefault();
      const updated = addColumn(block, 2);

      expect(updated.columns.length).toBe(3);
      expect(updated.columns[2].size).toBe(2);
    });

    it("should remove column", () => {
      const block = columnsBlockDef.createDefault();
      const columnId = block.columns[0].id;
      const updated = removeColumn(block, columnId);

      expect(updated.columns.length).toBe(1);
      expect(updated.columns[0].id).not.toBe(columnId);
    });

    it("should set column size", () => {
      const block = columnsBlockDef.createDefault();
      const columnId = block.columns[0].id;
      const updated = setColumnSize(block, columnId, 3);

      expect(updated.columns[0].size).toBe(3);
    });
  });

  describe("Table Block", () => {
    it("should create default 3x3 table with header", () => {
      const block = tableBlockDef.createDefault();
      expect(block.type).toBe("table");
      expect(block.rows.length).toBe(3);
      expect(block.rows[0].cells.length).toBe(3);
      expect(block.rows[0].isHeader).toBe(true);
      expect(block.rows[1].isHeader).toBe(false);
      expect(block.borderStyle).toBe("all");
    });

    it("should get children from all cells", () => {
      const block = tableBlockDef.createDefault();
      const child = textBlockDef.createDefault();

      // Add child to first cell
      const updated: TableBlock = {
        ...block,
        rows: [
          {
            ...block.rows[0],
            cells: [
              { ...block.rows[0].cells[0], children: [child] },
              ...block.rows[0].cells.slice(1),
            ],
          },
          ...block.rows.slice(1),
        ],
      };

      const children = tableBlockDef.getChildren!(updated) as Block[];
      expect(children).toContain(child);
    });

    it("should get container references", () => {
      const block = tableBlockDef.createDefault();
      const containers = tableBlockDef.getContainers(block);

      // 3 rows x 3 cells = 9 containers
      expect(containers.length).toBe(9);
      expect(containers[0].containerId).toBe(
        `${block.rows[0].id}::${block.rows[0].cells[0].id}`,
      );
    });

    it("should get/set container children", () => {
      const block = tableBlockDef.createDefault();
      const child = textBlockDef.createDefault();
      const containerId = `${block.rows[0].id}::${block.rows[0].cells[0].id}`;

      const updated = tableBlockDef.setContainerChildren(block, containerId, [
        child,
      ]);
      const children = tableBlockDef.getContainerChildren(updated, containerId);

      expect(children).toEqual([child]);
    });

    it("should add row", () => {
      const block = tableBlockDef.createDefault();
      const updated = addTableRow(block);

      expect(updated.rows.length).toBe(4);
      expect(updated.rows[3].cells.length).toBe(3);
    });

    it("should remove row", () => {
      const block = tableBlockDef.createDefault();
      const rowId = block.rows[1].id;
      const updated = removeTableRow(block, rowId);

      expect(updated.rows.length).toBe(2);
      expect(updated.rows.find((r) => r.id === rowId)).toBeUndefined();
    });

    it("should add column", () => {
      const block = tableBlockDef.createDefault();
      const updated = addTableColumn(block);

      expect(updated.rows[0].cells.length).toBe(4);
      expect(updated.rows[1].cells.length).toBe(4);
    });

    it("should remove column", () => {
      const block = tableBlockDef.createDefault();
      const updated = removeTableColumn(block, 1);

      expect(updated.rows[0].cells.length).toBe(2);
    });

    it("should get cell at position", () => {
      const block = tableBlockDef.createDefault();
      const cell = getCell(block, 1, 2);

      expect(cell).toBe(block.rows[1].cells[2]);
    });
  });

  describe("Page Break Block", () => {
    it("should create default", () => {
      const block = pageBreakBlockDef.createDefault();
      expect(block.type).toBe("pagebreak");
      expect(block.id).toBeDefined();
    });

    it("should not have children", () => {
      expect(pageBreakBlockDef.getChildren).toBeUndefined();
      expect(pageBreakBlockDef.setChildren).toBeUndefined();
    });
  });

  describe("Page Header Block", () => {
    it("should create default with empty children", () => {
      const block = pageHeaderBlockDef.createDefault();
      expect(block.type).toBe("pageheader");
      expect(block.children).toEqual([]);
    });

    it("should get and set children", () => {
      const block = pageHeaderBlockDef.createDefault();
      const child = textBlockDef.createDefault();

      const updated = pageHeaderBlockDef.setChildren!(block, [child]);
      expect(pageHeaderBlockDef.getChildren!(updated)).toEqual([child]);
    });
  });

  describe("Page Footer Block", () => {
    it("should create default with empty children", () => {
      const block = pageFooterBlockDef.createDefault();
      expect(block.type).toBe("pagefooter");
      expect(block.children).toEqual([]);
    });

    it("should get and set children", () => {
      const block = pageFooterBlockDef.createDefault();
      const child = textBlockDef.createDefault();

      const updated = pageFooterBlockDef.setChildren!(block, [child]);
      expect(pageFooterBlockDef.getChildren!(updated)).toEqual([child]);
    });
  });

  describe("Block Icons", () => {
    it("should have SVG icons for all blocks", () => {
      const definitions = [
        textBlockDef,
        containerBlockDef,
        conditionalBlockDef,
        loopBlockDef,
        columnsBlockDef,
        tableBlockDef,
        pageBreakBlockDef,
        pageHeaderBlockDef,
        pageFooterBlockDef,
      ];

      for (const def of definitions) {
        expect(def.icon).toContain("<svg");
        expect(def.icon).toContain("</svg>");
      }
    });
  });

  describe("Block Labels", () => {
    it("should have human-readable labels", () => {
      expect(textBlockDef.label).toBe("Text");
      expect(containerBlockDef.label).toBe("Container");
      expect(conditionalBlockDef.label).toBe("Conditional");
      expect(loopBlockDef.label).toBe("Loop");
      expect(columnsBlockDef.label).toBe("Columns");
      expect(tableBlockDef.label).toBe("Table");
      expect(pageBreakBlockDef.label).toBe("Page Break");
      expect(pageHeaderBlockDef.label).toBe("Page Header");
      expect(pageFooterBlockDef.label).toBe("Page Footer");
    });
  });
});
