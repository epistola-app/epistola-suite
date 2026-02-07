/**
 * Table block definition.
 *
 * Grid-based table with rows and cells.
 * This is a multi-container block - each cell holds its own children.
 */

import type {
  TableBlock,
  TableRow,
  TableCell,
  Block,
} from "../types/template.ts";
import type {
  BlockDefinition,
  MultiContainerBlockDefinition,
  ChildContainerRef,
} from "./types.ts";
import { registerBlock } from "./registry.ts";

/**
 * Create a new table cell.
 */
function createCell(): TableCell {
  return {
    id: crypto.randomUUID(),
    children: [],
  };
}

/**
 * Create a new table row.
 */
function createRow(columnCount: number, isHeader: boolean = false): TableRow {
  return {
    id: crypto.randomUUID(),
    cells: Array.from({ length: columnCount }, () => createCell()),
    isHeader,
  };
}

/**
 * Table block definition.
 */
export const tableBlockDef: MultiContainerBlockDefinition<TableBlock> = {
  type: "table",
  label: "Table",
  category: "data",
  description: "Data table with rows and columns",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
    <line x1="3" y1="9" x2="21" y2="9"/>
    <line x1="3" y1="15" x2="21" y2="15"/>
    <line x1="9" y1="3" x2="9" y2="21"/>
    <line x1="15" y1="3" x2="15" y2="21"/>
  </svg>`,

  createDefault: (): TableBlock => ({
    id: crypto.randomUUID(),
    type: "table",
    rows: [createRow(3, true), createRow(3), createRow(3)],
    borderStyle: "all",
  }),

  // Returns all children flattened across all cells
  getChildren: (block: TableBlock): Block[] =>
    block.rows.flatMap((row) => row.cells.flatMap((cell) => cell.children)),

  // setChildren not implemented for multi-container blocks
  // Use setContainerChildren instead

  canContain: () => true,

  // Multi-container operations
  // Container ID format: "rowId::cellId"
  getContainers: (block: TableBlock): ChildContainerRef[] =>
    block.rows.flatMap((row) =>
      row.cells.map((cell) => ({
        blockId: block.id,
        containerId: `${row.id}::${cell.id}`,
      })),
    ),

  getContainerChildren: (block: TableBlock, containerId: string): Block[] => {
    const [rowId, cellId] = containerId.split("::");
    const row = block.rows.find((r) => r.id === rowId);
    const cell = row?.cells.find((c) => c.id === cellId);
    return cell?.children ?? [];
  },

  setContainerChildren: (
    block: TableBlock,
    containerId: string,
    children: Block[],
  ): TableBlock => {
    const [rowId, cellId] = containerId.split("::");
    return {
      ...block,
      rows: block.rows.map((row) =>
        row.id === rowId
          ? {
              ...row,
              cells: row.cells.map((cell) =>
                cell.id === cellId ? { ...cell, children } : cell,
              ),
            }
          : row,
      ),
    };
  },
};

/**
 * Register the table block.
 */
export function registerTableBlock(): void {
  registerBlock(tableBlockDef as unknown as BlockDefinition<TableBlock>);
}

/**
 * Add a row to a table.
 */
export function addTableRow(
  block: TableBlock,
  isHeader: boolean = false,
): TableBlock {
  const columnCount = block.rows[0]?.cells.length ?? 3;
  return {
    ...block,
    rows: [...block.rows, createRow(columnCount, isHeader)],
  };
}

/**
 * Remove a row from a table.
 */
export function removeTableRow(block: TableBlock, rowId: string): TableBlock {
  return {
    ...block,
    rows: block.rows.filter((row) => row.id !== rowId),
  };
}

/**
 * Add a column to all rows.
 */
export function addTableColumn(block: TableBlock): TableBlock {
  return {
    ...block,
    rows: block.rows.map((row) => ({
      ...row,
      cells: [...row.cells, createCell()],
    })),
  };
}

/**
 * Remove a column from all rows.
 */
export function removeTableColumn(
  block: TableBlock,
  columnIndex: number,
): TableBlock {
  return {
    ...block,
    rows: block.rows.map((row) => ({
      ...row,
      cells: row.cells.filter((_, index) => index !== columnIndex),
    })),
  };
}

/**
 * Get cell at position.
 */
export function getCell(
  block: TableBlock,
  rowIndex: number,
  columnIndex: number,
): TableCell | undefined {
  return block.rows[rowIndex]?.cells[columnIndex];
}
