/**
 * Headless Editor Store
 *
 * Framework-agnostic reactive state using nanostores.
 * Includes pure utility functions for manipulating nested block trees.
 */

import { atom, map } from 'nanostores';
import type { Template, Block, Column, TableRow, TableCell, DataExample, JsonObject, JsonSchema, PreviewOverrides, ThemeSummary } from './types.js';
import { DEFAULT_TEST_DATA, DEFAULT_PREVIEW_OVERRIDES } from './types.js';

// ============================================================================
// Editor Store
// ============================================================================

/**
 * Create the editor store using nanostores
 * Framework-agnostic reactive state
 */
export function createEditorStore(initialTemplate: Template) {
  // Atoms for simple values
  const $selectedBlockId = atom<string | null>(null);
  const $dataExamples = atom<DataExample[]>([]);
  const $selectedDataExampleId = atom<string | null>(null);
  const $testData = atom<JsonObject>(JSON.parse(JSON.stringify(DEFAULT_TEST_DATA)) as JsonObject);
  const $schema = atom<JsonSchema | null>(null);
  const $lastSavedTemplate = atom<Template | null>(null);
  const $previewOverrides = atom<PreviewOverrides>({ ...DEFAULT_PREVIEW_OVERRIDES });

  // Theme atoms
  const $themes = atom<ThemeSummary[]>([]);
  const $defaultTheme = atom<ThemeSummary | null>(null);

  // Map for complex objects
  const $template = map<Template>(initialTemplate);

  return {
    $template,
    $selectedBlockId,
    $dataExamples,
    $selectedDataExampleId,
    $testData,
    $schema,
    $lastSavedTemplate,
    $previewOverrides,
    $themes,
    $defaultTheme,

    // Getters
    getTemplate: () => $template.get(),
    getSelectedBlockId: () => $selectedBlockId.get(),
    getDataExamples: () => $dataExamples.get(),
    getSelectedDataExampleId: () => $selectedDataExampleId.get(),
    getTestData: () => $testData.get(),
    getSchema: () => $schema.get(),
    getLastSavedTemplate: () => $lastSavedTemplate.get(),
    getPreviewOverrides: () => JSON.parse(JSON.stringify($previewOverrides.get())) as PreviewOverrides,
    getThemes: () => $themes.get(),
    getDefaultTheme: () => $defaultTheme.get(),

    // Setters
    setTemplate: (template: Template) => $template.set(template),
    setSelectedBlockId: (id: string | null) => $selectedBlockId.set(id),
    setDataExamples: (examples: DataExample[]) => $dataExamples.set(examples),
    setSelectedDataExampleId: (id: string | null) => $selectedDataExampleId.set(id),
    setTestData: (data: JsonObject) => $testData.set(data),
    setSchema: (schema: JsonSchema | null) => $schema.set(schema),
    setLastSavedTemplate: (template: Template | null) => $lastSavedTemplate.set(template),
    setPreviewOverrides: (overrides: PreviewOverrides) => $previewOverrides.set(overrides),
    setThemes: (themes: ThemeSummary[]) => $themes.set(themes),
    setDefaultTheme: (theme: ThemeSummary | null) => $defaultTheme.set(theme),

    // Subscribe to changes
    subscribeTemplate: (cb: (template: Template) => void) => $template.subscribe(cb),
    subscribeSelectedBlockId: (cb: (id: string | null) => void) => $selectedBlockId.subscribe(cb),
    subscribeDataExamples: (cb: (examples: readonly DataExample[]) => void) => $dataExamples.subscribe(cb),
    subscribeSelectedDataExampleId: (cb: (id: string | null) => void) => $selectedDataExampleId.subscribe(cb),
    subscribeTestData: (cb: (data: JsonObject) => void) => $testData.subscribe(cb),
    subscribeSchema: (cb: (schema: JsonSchema | null) => void) => $schema.subscribe(cb),
    subscribeLastSavedTemplate: (cb: (template: Template | null) => void) => $lastSavedTemplate.subscribe(cb),
    subscribePreviewOverrides: (cb: (overrides: PreviewOverrides) => void) => $previewOverrides.subscribe(cb),
    subscribeThemes: (cb: (themes: readonly ThemeSummary[]) => void) => $themes.subscribe(cb),
    subscribeDefaultTheme: (cb: (theme: ThemeSummary | null) => void) => $defaultTheme.subscribe(cb),
  };
}

export type EditorStore = ReturnType<typeof createEditorStore>;

// ============================================================================
// Block Tree Type Guards
// ============================================================================

/** Check if a block has a children array */
function hasChildren(block: Block): block is Block & { children: Block[] } {
  return 'children' in block && Array.isArray((block as { children?: unknown }).children);
}

/** Check if a block is a ColumnsBlock with columns array */
function hasColumns(block: Block): block is Block & { columns: Column[] } {
  return block.type === 'columns' && 'columns' in block && Array.isArray((block as { columns?: unknown }).columns);
}

/** Check if a block is a TableBlock with rows array */
function hasRows(block: Block): block is Block & { rows: TableRow[] } {
  return block.type === 'table' && 'rows' in block && Array.isArray((block as { rows?: unknown }).rows);
}

// ============================================================================
// Block Tree Utilities
// ============================================================================

/**
 * Block tree utilities - pure functions for manipulating nested blocks
 * Handles regular children, columns[], and table rows/cells
 */
export const BlockTree = {
  /**
   * Find a block by ID in a nested structure
   * Searches through children, columns, and table cells
   */
  findBlock(blocks: Block[], id: string): Block | null {
    for (const block of blocks) {
      if (block.id === id) return block;

      // Search in regular children
      if (hasChildren(block)) {
        const found = this.findBlock(block.children, id);
        if (found) return found;
      }

      // Search in columns
      if (hasColumns(block)) {
        for (const column of block.columns) {
          const found = this.findBlock(column.children, id);
          if (found) return found;
        }
      }

      // Search in table cells
      if (hasRows(block)) {
        for (const row of block.rows) {
          for (const cell of row.cells) {
            const found = this.findBlock(cell.children, id);
            if (found) return found;
          }
        }
      }
    }
    return null;
  },

  /**
   * Find parent of a block
   * Returns the parent block, or null if at root level
   */
  findParent(blocks: Block[], id: string, parent: Block | null = null): Block | null {
    for (const block of blocks) {
      if (block.id === id) return parent;

      // Search in regular children
      if (hasChildren(block)) {
        const found = this.findParent(block.children, id, block);
        if (found !== undefined && found !== null) return found;
        // Check if block is direct child
        if (block.children.some((child) => child.id === id)) return block;
      }

      // Search in columns
      if (hasColumns(block)) {
        for (const column of block.columns) {
          if (column.children.some((child) => child.id === id)) return block;
          const found = this.findParent(column.children, id, block);
          if (found !== undefined && found !== null) return found;
        }
      }

      // Search in table cells
      if (hasRows(block)) {
        for (const row of block.rows) {
          for (const cell of row.cells) {
            if (cell.children.some((child) => child.id === id)) return block;
            const found = this.findParent(cell.children, id, block);
            if (found !== undefined && found !== null) return found;
          }
        }
      }
    }
    return null;
  },

  /**
   * Find a column by ID within a ColumnsBlock
   */
  findColumn(blocks: Block[], columnId: string): { block: Block; column: Column } | null {
    for (const block of blocks) {
      if (hasColumns(block)) {
        const column = block.columns.find((col) => col.id === columnId);
        if (column) return { block, column };

        // Recurse into column children (nested ColumnsBlock)
        for (const col of block.columns) {
          const found = this.findColumn(col.children, columnId);
          if (found) return found;
        }
      }

      // Recurse into children
      if (hasChildren(block)) {
        const found = this.findColumn(block.children, columnId);
        if (found) return found;
      }

      // Recurse into table cells
      if (hasRows(block)) {
        for (const row of block.rows) {
          for (const cell of row.cells) {
            const found = this.findColumn(cell.children, columnId);
            if (found) return found;
          }
        }
      }
    }
    return null;
  },

  /**
   * Find a table cell by ID within a TableBlock
   */
  findCell(blocks: Block[], cellId: string): { block: Block; row: TableRow; cell: TableCell } | null {
    for (const block of blocks) {
      if (hasRows(block)) {
        for (const row of block.rows) {
          const cell = row.cells.find((c) => c.id === cellId);
          if (cell) return { block, row, cell };
        }

        // Recurse into cell children (nested TableBlock)
        for (const row of block.rows) {
          for (const cell of row.cells) {
            const found = this.findCell(cell.children, cellId);
            if (found) return found;
          }
        }
      }

      // Recurse into children
      if (hasChildren(block)) {
        const found = this.findCell(block.children, cellId);
        if (found) return found;
      }

      // Recurse into columns
      if (hasColumns(block)) {
        for (const col of block.columns) {
          const found = this.findCell(col.children, cellId);
          if (found) return found;
        }
      }
    }
    return null;
  },

  /**
   * Add a block at a specific location
   * parentId can be a block ID, column ID, or cell ID
   */
  addBlock(blocks: Block[], newBlock: Block, parentId: string | null, index: number): Block[] {
    if (parentId === null) {
      // Add to root
      const result = [...blocks];
      result.splice(index, 0, newBlock);
      return result;
    }

    return blocks.map((block): Block => {
      // Add to block with children
      if (block.id === parentId && hasChildren(block)) {
        const children = [...block.children];
        children.splice(index, 0, newBlock);
        return { ...block, children } as Block;
      }

      // Add to column within ColumnsBlock
      if (hasColumns(block)) {
        const columnIndex = block.columns.findIndex((col) => col.id === parentId);
        if (columnIndex !== -1) {
          const columns = block.columns.map((col, i) => {
            if (i === columnIndex) {
              const children = [...col.children];
              children.splice(index, 0, newBlock);
              return { ...col, children };
            }
            return col;
          });
          return { ...block, columns } as Block;
        }
      }

      // Add to cell within TableBlock
      if (hasRows(block)) {
        const rows = block.rows.map((row) => {
          const cellIndex = row.cells.findIndex((cell) => cell.id === parentId);
          if (cellIndex !== -1) {
            const cells = row.cells.map((cell, i) => {
              if (i === cellIndex) {
                const children = [...cell.children];
                children.splice(index, 0, newBlock);
                return { ...cell, children };
              }
              return cell;
            });
            return { ...row, cells };
          }
          return row;
        });
        return { ...block, rows } as Block;
      }

      // Recurse into children
      if (hasChildren(block)) {
        return { ...block, children: this.addBlock(block.children, newBlock, parentId, index) } as Block;
      }

      return block;
    });
  },

  /**
   * Remove a block by ID
   */
  removeBlock(blocks: Block[], id: string): Block[] {
    return blocks
      .filter((block) => block.id !== id)
      .map((block): Block => {
        // Remove from children
        if (hasChildren(block)) {
          return { ...block, children: this.removeBlock(block.children, id) } as Block;
        }

        // Remove from columns
        if (hasColumns(block)) {
          const columns = block.columns.map((col) => ({
            ...col,
            children: this.removeBlock(col.children, id),
          }));
          return { ...block, columns } as Block;
        }

        // Remove from table cells
        if (hasRows(block)) {
          const rows = block.rows.map((row) => ({
            ...row,
            cells: row.cells.map((cell) => ({
              ...cell,
              children: this.removeBlock(cell.children, id),
            })),
          }));
          return { ...block, rows } as Block;
        }

        return block;
      });
  },

  /**
   * Update a block by ID
   */
  updateBlock(blocks: Block[], id: string, updates: Partial<Block>): Block[] {
    return blocks.map((block): Block => {
      if (block.id === id) {
        return { ...block, ...updates } as Block;
      }

      // Recurse into children
      if (hasChildren(block)) {
        return { ...block, children: this.updateBlock(block.children, id, updates) } as Block;
      }

      // Recurse into columns
      if (hasColumns(block)) {
        const columns = block.columns.map((col) => ({
          ...col,
          children: this.updateBlock(col.children, id, updates),
        }));
        return { ...block, columns } as Block;
      }

      // Recurse into table cells
      if (hasRows(block)) {
        const rows = block.rows.map((row) => ({
          ...row,
          cells: row.cells.map((cell) => ({
            ...cell,
            children: this.updateBlock(cell.children, id, updates),
          })),
        }));
        return { ...block, rows } as Block;
      }

      return block;
    });
  },

  /**
   * Move a block to a new location.
   *
   * Note: newIndex is the insertion position in the POST-removal array.
   * For same-parent forward moves (lower to higher index), the caller
   * should subtract 1 from the visual target position.
   * This matches SortableJS/dnd-kit behavior.
   */
  moveBlock(blocks: Block[], id: string, newParentId: string | null, newIndex: number): Block[] {
    const block = this.findBlock(blocks, id);
    if (!block) return blocks;

    // Remove from current location
    const withoutBlock = this.removeBlock(blocks, id);

    // Add to new location
    return this.addBlock(withoutBlock, block, newParentId, newIndex);
  },

  /**
   * Get the children array for a parent
   * Returns root blocks if parentId is null
   */
  getChildren(blocks: Block[], parentId: string | null): Block[] {
    if (parentId === null) {
      return blocks;
    }

    // Check for block with children
    const block = this.findBlock(blocks, parentId);
    if (block && hasChildren(block)) {
      return block.children;
    }

    // Check for column
    const columnResult = this.findColumn(blocks, parentId);
    if (columnResult) {
      return columnResult.column.children;
    }

    // Check for cell
    const cellResult = this.findCell(blocks, parentId);
    if (cellResult) {
      return cellResult.cell.children;
    }

    return [];
  },

  /**
   * Count children at a location
   */
  getChildCount(blocks: Block[], parentId: string | null): number {
    return this.getChildren(blocks, parentId).length;
  },

  getChildIndex(blocks: Block[], childId: string, parentId: string | null): number {
    const children = this.getChildren(blocks, parentId);
    return children.findIndex((block) => block.id === childId);
  },
};
