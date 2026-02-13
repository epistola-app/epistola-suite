/**
 * Block Factory Functions
 *
 * Helper functions to create block objects for testing.
 * These create the raw data structures, NOT the editor API.
 */

export interface TextBlock {
  type: 'text';
  id: string;
  content: {
    type: 'doc';
    content: Array<{
      type: 'paragraph';
      content: Array<{ type: 'text'; text: string }>;
    }>;
  };
  styles?: Record<string, string>;
}

export interface ContainerBlock {
  type: 'container';
  id: string;
  styles?: Record<string, string>;
  children: Array<TextBlock | ContainerBlock | ConditionalBlock | LoopBlock>;
}

export interface ConditionalBlock {
  type: 'conditional';
  id: string;
  condition: {
    language: 'jsonata';
    raw: string;
  };
  children: Array<TextBlock>;
}

export interface LoopBlock {
  type: 'loop';
  id: string;
  expression: { raw: string };
  itemAlias: string;
  indexAlias?: string;
  children: Array<TextBlock>;
}

export interface ColumnsBlock {
  type: 'columns';
  id: string;
  gap: number;
  columns: Array<{
    id: string;
    size: number;
    children: Array<TextBlock>;
  }>;
}

export interface TableBlock {
  type: 'table';
  id: string;
  rows: Array<{
    id: string;
    cells: Array<{
      id: string;
      styles?: Record<string, string>;
      children: Array<TextBlock>;
    }>;
  }>;
}

export interface PageBreakBlock {
  type: 'pagebreak';
  id: string;
}

export interface PageHeaderBlock {
  type: 'pageheader';
  id: string;
  children: Array<TextBlock>;
}

export interface PageFooterBlock {
  type: 'pagefooter';
  id: string;
  children: Array<TextBlock>;
}

export type AnyBlock =
  | TextBlock
  | ContainerBlock
  | ConditionalBlock
  | LoopBlock
  | ColumnsBlock
  | TableBlock
  | PageBreakBlock
  | PageHeaderBlock
  | PageFooterBlock;

export function createTextBlock(content = 'Test content'): TextBlock {
  return {
    type: 'text',
    id: `text-${Date.now()}-${Math.random().toString(36).substring(7)}`,
    content: {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [{ type: 'text', text: content }],
        },
      ],
    },
  };
}

export function createContainerBlock(children: Array<TextBlock> = []): ContainerBlock {
  return {
    type: 'container',
    id: `container-${Date.now()}-${Math.random().toString(36).substring(7)}`,
    children,
  };
}

export function createConditionalBlock(condition = 'true'): ConditionalBlock {
  return {
    type: 'conditional',
    id: `conditional-${Date.now()}-${Math.random().toString(36).substring(7)}`,
    condition: {
      language: 'jsonata',
      raw: condition,
    },
    children: [],
  };
}

export function createLoopBlock(
  expression = 'items',
  alias = 'item'
): LoopBlock {
  return {
    type: 'loop',
    id: `loop-${Date.now()}-${Math.random().toString(36).substring(7)}`,
    expression: { raw: expression },
    itemAlias: alias,
    children: [],
  };
}

export function createColumnsBlock(columnCount = 2): ColumnsBlock {
  const columns = Array.from({ length: columnCount }, (_, i) => ({
    id: `col-${Date.now()}-${i}`,
    size: 1,
    children: [] as TextBlock[],
  }));

  return {
    type: 'columns',
    id: `columns-${Date.now()}`,
    gap: 20,
    columns,
  };
}

export function createTableBlock(rows = 2, cols = 3): TableBlock {
  const tableRows = Array.from({ length: rows }, (_, rowIndex) => ({
    id: `row-${Date.now()}-${rowIndex}`,
    cells: Array.from({ length: cols }, (_, colIndex) => ({
      id: `cell-${Date.now()}-${rowIndex}-${colIndex}`,
      children: [] as TextBlock[],
    })),
  }));

  return {
    type: 'table',
    id: `table-${Date.now()}`,
    rows: tableRows,
  };
}

export function createPageBreakBlock(): PageBreakBlock {
  return {
    type: 'pagebreak',
    id: `pagebreak-${Date.now()}`,
  };
}

export function createPageHeaderBlock(): PageHeaderBlock {
  return {
    type: 'pageheader',
    id: `pageheader-${Date.now()}`,
    children: [],
  };
}

export function createPageFooterBlock(): PageFooterBlock {
  return {
    type: 'pagefooter',
    id: `pagefooter-${Date.now()}`,
    children: [],
  };
}
