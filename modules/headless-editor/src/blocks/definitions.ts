import type { BlockDefinition, Block, TextBlock, ContainerBlock, ConditionalBlock, LoopBlock, ColumnsBlock, TableBlock, PageBreakBlock, PageHeaderBlock, PageFooterBlock, Column, TableRow, TableCell } from "../types.js";

function generateId(): string {
  return `block-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

function createColumn(): Column {
  return {
    id: generateId(),
    size: 1,
    children: [],
  };
}

function createCell(): TableCell {
  return {
    id: generateId(),
    children: [],
  };
}

function createRow(cellCount: number, isHeader = false): TableRow {
  return {
    id: generateId(),
    cells: Array.from({ length: cellCount }, () => createCell()),
    isHeader,
  };
}

export const textBlockDefinition: BlockDefinition = {
  type: "text",
  label: "Text",
  icon: "text",
  category: "Content",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Content", order: 0, label: "Text", icon: "text" },

  create: (id: string): TextBlock => ({
    id,
    type: "text",
    content: null,
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === "text") {
      const textBlock = block as TextBlock;
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
    allowedParentTypes: null,
  },

  dropContainers: () => [],
};

export const containerBlockDefinition: BlockDefinition = {
  type: "container",
  label: "Container",
  icon: "container",
  category: "Layout",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Layout", order: 10, label: "Container", icon: "container" },

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
    allowedChildTypes: null,
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null,
  },

  dropContainers: (block) => [block.id],
};

export const conditionalBlockDefinition: BlockDefinition = {
  type: "conditional",
  label: "Conditional",
  icon: "conditional",
  category: "Logic",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Logic", order: 0, label: "Conditional", icon: "conditional" },

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
    allowedChildTypes: null,
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null,
  },

  dropContainers: (block) => [block.id],
};

export const loopBlockDefinition: BlockDefinition = {
  type: "loop",
  label: "Loop",
  icon: "loop",
  category: "Logic",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Logic", order: 10, label: "Loop", icon: "loop" },

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
    allowedChildTypes: null,
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null,
  },

  dropContainers: (block) => [block.id],
};

export const columnsBlockDefinition: BlockDefinition = {
  type: "columns",
  label: "Columns",
  icon: "columns",
  category: "Layout",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Layout", order: 20, label: "Columns", icon: "columns" },

  create: (id: string): ColumnsBlock => ({
    id,
    type: "columns",
    columns: [createColumn(), createColumn()],
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
    canHaveChildren: false,
    allowedChildTypes: [],
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null,
  },

  dropContainers: (block) =>
    Array.isArray((block as ColumnsBlock).columns)
      ? (block as ColumnsBlock).columns.map((column) => column.id)
      : [],
};

export const tableBlockDefinition: BlockDefinition = {
  type: "table",
  label: "Table",
  icon: "table",
  category: "Layout",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Layout", order: 30, label: "Table", icon: "table" },

  create: (id: string): TableBlock => ({
    id,
    type: "table",
    rows: [createRow(3, true), createRow(3), createRow(3)],
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
    canHaveChildren: false,
    allowedChildTypes: [],
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null,
  },

  dropContainers: (block) =>
    Array.isArray((block as TableBlock).rows)
      ? (block as TableBlock).rows.flatMap((row) =>
          row.cells.map((cell) => cell.id)
        )
      : [],
};

export const pageBreakBlockDefinition: BlockDefinition = {
  type: "pagebreak",
  label: "Page Break",
  icon: "pagebreak",
  category: "Layout",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Layout", order: 40, label: "Page Break", icon: "pagebreak" },

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
    allowedParentTypes: ["root"],
  },

  dropContainers: () => [],
};

export const pageHeaderBlockDefinition: BlockDefinition = {
  type: "pageheader",
  label: "Page Header",
  icon: "pageheader",
  category: "Layout",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Layout", order: 50, label: "Page Header", icon: "pageheader" },

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
    allowedChildTypes: null,
    canBeDragged: true,
    canBeNested: false,
    allowedParentTypes: ["root"],
  },

  dropContainers: (block) => [block.id],
};

export const pageFooterBlockDefinition: BlockDefinition = {
  type: "pagefooter",
  label: "Page Footer",
  icon: "pagefooter",
  category: "Layout",
  capabilities: { html: true, pdf: true },
  toolbar: { visible: true, group: "Layout", order: 60, label: "Page Footer", icon: "pagefooter" },

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
    allowedChildTypes: null,
    canBeDragged: true,
    canBeNested: false,
    allowedParentTypes: ["root"],
  },

  dropContainers: (block) => [block.id],
};

export const defaultBlockDefinitions: BlockDefinition[] = [
  textBlockDefinition,
  containerBlockDefinition,
  conditionalBlockDefinition,
  loopBlockDefinition,
  columnsBlockDefinition,
  tableBlockDefinition,
  pageBreakBlockDefinition,
  pageHeaderBlockDefinition,
  pageFooterBlockDefinition,
];

export {
  generateId,
  createColumn,
  createCell,
  createRow,
};
