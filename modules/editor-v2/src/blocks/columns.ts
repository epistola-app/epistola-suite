/**
 * Columns block definition.
 *
 * Multi-column layout with flexible sizing.
 * This is a multi-container block - each column holds its own children.
 */

import type { ColumnsBlock, Column, Block } from "../types/template.ts";
import type {
  BlockDefinition,
  MultiContainerBlockDefinition,
  ChildContainerRef,
} from "./types.ts";
import { registerBlock } from "./registry.ts";

/**
 * Create a new column with default values.
 */
function createColumn(size: number = 1): Column {
  return {
    id: crypto.randomUUID(),
    size,
    children: [],
  };
}

/**
 * Columns block definition.
 */
export const columnsBlockDef: MultiContainerBlockDefinition<ColumnsBlock> = {
  type: "columns",
  label: "Columns",
  category: "structure",
  description: "Multi-column layout",
  icon: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
    <line x1="12" y1="3" x2="12" y2="21"/>
  </svg>`,

  createDefault: (): ColumnsBlock => ({
    id: crypto.randomUUID(),
    type: "columns",
    columns: [createColumn(), createColumn()],
    gap: 16,
  }),

  // Returns all children flattened across all columns
  getChildren: (block: ColumnsBlock): Block[] =>
    block.columns.flatMap((col) => col.children),

  // setChildren not implemented for multi-container blocks
  // Use setContainerChildren instead

  canContain: () => true,

  // Multi-container operations
  getContainers: (block: ColumnsBlock): ChildContainerRef[] =>
    block.columns.map((col) => ({
      blockId: block.id,
      containerId: col.id,
    })),

  getContainerChildren: (block: ColumnsBlock, containerId: string): Block[] => {
    const column = block.columns.find((col) => col.id === containerId);
    return column?.children ?? [];
  },

  setContainerChildren: (
    block: ColumnsBlock,
    containerId: string,
    children: Block[],
  ): ColumnsBlock => ({
    ...block,
    columns: block.columns.map((col) =>
      col.id === containerId ? { ...col, children } : col,
    ),
  }),
};

/**
 * Register the columns block.
 */
export function registerColumnsBlock(): void {
  registerBlock(columnsBlockDef as unknown as BlockDefinition<ColumnsBlock>);
}

/**
 * Add a column to a columns block.
 */
export function addColumn(block: ColumnsBlock, size: number = 1): ColumnsBlock {
  return {
    ...block,
    columns: [...block.columns, createColumn(size)],
  };
}

/**
 * Remove a column from a columns block.
 */
export function removeColumn(
  block: ColumnsBlock,
  columnId: string,
): ColumnsBlock {
  return {
    ...block,
    columns: block.columns.filter((col) => col.id !== columnId),
  };
}

/**
 * Update column size.
 */
export function setColumnSize(
  block: ColumnsBlock,
  columnId: string,
  size: number,
): ColumnsBlock {
  return {
    ...block,
    columns: block.columns.map((col) =>
      col.id === columnId ? { ...col, size } : col,
    ),
  };
}
