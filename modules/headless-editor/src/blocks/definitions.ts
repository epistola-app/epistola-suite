/**
 * Block Definitions
 *
 * Defines all block types with their constraints and factory functions.
 * Pure logic - no UI or framework dependencies.
 */

import type {
  BlockDefinition,
  Block,
  TextBlock,
  ContainerBlock,
  ConditionalBlock,
  LoopBlock,
  ColumnsBlock,
  TableBlock,
  PageBreakBlock,
  PageHeaderBlock,
  PageFooterBlock,
  Column,
  TableRow,
  TableCell,
} from "../types.js";

/**
 * Generate a simple unique ID
 */
function generateId(): string {
  return `block-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

// ============================================================================
// Text Block
// ============================================================================

export const textBlockDefinition: BlockDefinition = {
  type: "text",

  create: (id: string): TextBlock => ({
    id,
    type: "text",
    content: null,
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "text") {
      const textBlock = block as TextBlock;
      // content can be null or a TipTap JSONContent object
      if (
        textBlock.content !== null &&
        typeof textBlock.content !== "object"
      ) {
        errors.push("Text block content must be null or an object");
      }
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: false,
    allowedChildTypes: [],
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null, // can go anywhere
  },
};

// ============================================================================
// Container Block
// ============================================================================

export const containerBlockDefinition: BlockDefinition = {
  type: "container",

  create: (id: string): ContainerBlock => ({
    id,
    type: "container",
    children: [],
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "container") {
      const containerBlock = block as ContainerBlock;
      if (!Array.isArray(containerBlock.children)) {
        errors.push("Container block must have children array");
      }
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: true,
    allowedChildTypes: null, // accepts any block type
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null, // can go anywhere
  },
};

// ============================================================================
// Conditional Block
// ============================================================================

export const conditionalBlockDefinition: BlockDefinition = {
  type: "conditional",

  create: (id: string): ConditionalBlock => ({
    id,
    type: "conditional",
    condition: { raw: "", language: "jsonata" },
    inverse: false,
    children: [],
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "conditional") {
      const conditionalBlock = block as ConditionalBlock;
      if (
        !conditionalBlock.condition ||
        typeof conditionalBlock.condition.raw !== "string"
      ) {
        errors.push("Conditional block must have a condition expression");
      }
      if (!Array.isArray(conditionalBlock.children)) {
        errors.push("Conditional block must have children array");
      }
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: true,
    allowedChildTypes: null, // accepts any block type
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null, // can go anywhere
  },
};

// ============================================================================
// Loop Block
// ============================================================================

export const loopBlockDefinition: BlockDefinition = {
  type: "loop",

  create: (id: string): LoopBlock => ({
    id,
    type: "loop",
    expression: { raw: "", language: "jsonata" },
    itemAlias: "item",
    children: [],
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "loop") {
      const loopBlock = block as LoopBlock;
      if (
        !loopBlock.expression ||
        typeof loopBlock.expression.raw !== "string"
      ) {
        errors.push("Loop block must have an expression");
      }
      if (!loopBlock.itemAlias || typeof loopBlock.itemAlias !== "string") {
        errors.push("Loop block must have an itemAlias");
      }
      if (!Array.isArray(loopBlock.children)) {
        errors.push("Loop block must have children array");
      }
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: true,
    allowedChildTypes: null, // accepts any block type
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null, // can go anywhere
  },
};

// ============================================================================
// Columns Block
// ============================================================================

/**
 * Create a column helper
 */
function createColumn(): Column {
  return {
    id: generateId(),
    size: 1,
    children: [],
  };
}

export const columnsBlockDefinition: BlockDefinition = {
  type: "columns",

  create: (id: string): ColumnsBlock => ({
    id,
    type: "columns",
    columns: [createColumn(), createColumn()], // Default 2 columns
    gap: 16,
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "columns") {
      const columnsBlock = block as ColumnsBlock;
      if (!Array.isArray(columnsBlock.columns)) {
        errors.push("Columns block must have columns array");
      } else {
        if (columnsBlock.columns.length < 1) {
          errors.push("Columns block must have at least 1 column");
        }
        if (columnsBlock.columns.length > 6) {
          errors.push("Columns block can have at most 6 columns");
        }
        for (const col of columnsBlock.columns) {
          if (!col.id || !Array.isArray(col.children)) {
            errors.push("Each column must have an id and children array");
          }
        }
      }
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: false, // Uses columns[] array instead of children[]
    allowedChildTypes: [],
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null, // can go anywhere
  },
};

// ============================================================================
// Table Block
// ============================================================================

/**
 * Create a table cell helper
 */
function createCell(): TableCell {
  return {
    id: generateId(),
    children: [],
  };
}

/**
 * Create a table row helper
 */
function createRow(cellCount: number, isHeader = false): TableRow {
  return {
    id: generateId(),
    cells: Array.from({ length: cellCount }, () => createCell()),
    isHeader,
  };
}

export const tableBlockDefinition: BlockDefinition = {
  type: "table",

  create: (id: string): TableBlock => ({
    id,
    type: "table",
    rows: [createRow(3, true), createRow(3), createRow(3)], // Default 3x3 with header
    borderStyle: "all",
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "table") {
      const tableBlock = block as TableBlock;
      if (!Array.isArray(tableBlock.rows)) {
        errors.push("Table block must have rows array");
      } else {
        for (const row of tableBlock.rows) {
          if (!row.id || !Array.isArray(row.cells)) {
            errors.push("Each row must have an id and cells array");
          }
          for (const cell of row.cells) {
            if (!cell.id || !Array.isArray(cell.children)) {
              errors.push("Each cell must have an id and children array");
            }
          }
        }
      }
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: false, // Uses rows[]/cells[] instead of children[]
    allowedChildTypes: [],
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null, // can go anywhere
  },
};

// ============================================================================
// Page Break Block
// ============================================================================

export const pageBreakBlockDefinition: BlockDefinition = {
  type: "pagebreak",

  create: (id: string): PageBreakBlock => ({
    id,
    type: "pagebreak",
  }),

  validate: () => ({ valid: true, errors: [] }),

  constraints: {
    canHaveChildren: false,
    allowedChildTypes: [],
    canBeDragged: true,
    canBeNested: false,
    allowedParentTypes: ["root"], // only at root level
  },
};

// ============================================================================
// Page Header Block
// ============================================================================

export const pageHeaderBlockDefinition: BlockDefinition = {
  type: "pageheader",

  create: (id: string): PageHeaderBlock => ({
    id,
    type: "pageheader",
    children: [],
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "pageheader") {
      const headerBlock = block as PageHeaderBlock;
      if (!Array.isArray(headerBlock.children)) {
        errors.push("Page header block must have children array");
      }
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: true,
    allowedChildTypes: null, // accepts any block type
    canBeDragged: true,
    canBeNested: false,
    allowedParentTypes: ["root"], // only at root level
  },
};

// ============================================================================
// Page Footer Block
// ============================================================================

export const pageFooterBlockDefinition: BlockDefinition = {
  type: "pagefooter",

  create: (id: string): PageFooterBlock => ({
    id,
    type: "pagefooter",
    children: [],
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "pagefooter") {
      const footerBlock = block as PageFooterBlock;
      if (!Array.isArray(footerBlock.children)) {
        errors.push("Page footer block must have children array");
      }
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: true,
    allowedChildTypes: null, // accepts any block type
    canBeDragged: true,
    canBeNested: false,
    allowedParentTypes: ["root"], // only at root level
  },
};

// ============================================================================
// Default Block Definitions
// ============================================================================

export const defaultBlockDefinitions: Record<string, BlockDefinition> = {
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

/**
 * Export helper functions for creating specific structures
 */
export const blockHelpers = {
  createColumn,
  createCell,
  createRow,
  generateId,
};
