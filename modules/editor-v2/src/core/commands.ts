/**
 * Command pattern for all state mutations.
 *
 * All mutations go through commands, enabling:
 * - Built-in undo/redo support
 * - Serializable operations for debugging
 * - Centralized validation
 */

import type {
  Block,
  Template,
  DocumentStyles,
  PageSettings,
  Column,
  TableRow,
} from "../types/template.ts";

// ============================================================================
// Command Interface
// ============================================================================

/**
 * Base command interface.
 * Commands are immutable - execute and undo return new state.
 */
export interface Command<T = Template> {
  /** Unique identifier for command type */
  readonly type: string;

  /** Execute the command, returning new state */
  execute(state: T): T;

  /** Undo the command, returning previous state */
  undo(state: T): T;

  /** Optional description for debugging/logging */
  description?: string;
}

// ============================================================================
// Block Tree Operations
// ============================================================================

/**
 * Get all children from a block, regardless of container type.
 */
export function getBlockChildren(block: Block): Block[] {
  if ("children" in block && Array.isArray(block.children)) {
    return block.children;
  }
  if ("columns" in block && Array.isArray(block.columns)) {
    return block.columns.flatMap((col) => col.children);
  }
  if ("rows" in block && Array.isArray(block.rows)) {
    return block.rows.flatMap((row) => row.cells.flatMap((cell) => cell.children));
  }
  return [];
}

/**
 * Find a block by ID in the tree.
 */
export function findBlock(blocks: Block[], id: string): Block | undefined {
  for (const block of blocks) {
    if (block.id === id) {
      return block;
    }
    const children = getBlockChildren(block);
    const found = findBlock(children, id);
    if (found) {
      return found;
    }
  }
  return undefined;
}

/**
 * Find a block and its parent information.
 */
export interface BlockLocation {
  block: Block;
  parent: Block | null;
  /** For columns/tables, the container ID (columnId, rowId::cellId) */
  containerId: string | null;
  index: number;
}

export function findBlockLocation(
  blocks: Block[],
  id: string,
  parent: Block | null = null,
  containerId: string | null = null,
): BlockLocation | undefined {
  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i];
    if (block.id === id) {
      return { block, parent, containerId, index: i };
    }

    // Search in regular children
    if ("children" in block && Array.isArray(block.children)) {
      const found = findBlockLocation(block.children, id, block, null);
      if (found) return found;
    }

    // Search in columns
    if ("columns" in block && Array.isArray(block.columns)) {
      for (const col of block.columns) {
        const found = findBlockLocation(col.children, id, block, col.id);
        if (found) return found;
      }
    }

    // Search in table cells
    if ("rows" in block && Array.isArray(block.rows)) {
      for (const row of block.rows) {
        for (const cell of row.cells) {
          const found = findBlockLocation(
            cell.children,
            id,
            block,
            `${row.id}::${cell.id}`,
          );
          if (found) return found;
        }
      }
    }
  }
  return undefined;
}

/**
 * Update a block in the tree by ID.
 * Returns a new tree with the updated block.
 */
export function updateBlockInTree(
  blocks: Block[],
  id: string,
  updater: (block: Block) => Block | null,
): Block[] {
  return blocks.reduce<Block[]>((acc, block) => {
    if (block.id === id) {
      const updated = updater(block);
      if (updated) acc.push(updated);
      return acc;
    }

    // Clone the block
    let updatedBlock: Block;

    // Handle regular children
    if ("children" in block && Array.isArray(block.children)) {
      const newChildren = updateBlockInTree(block.children, id, updater);
      updatedBlock = { ...block, children: newChildren } as Block;
    }
    // Handle columns
    else if ("columns" in block && Array.isArray(block.columns)) {
      const newColumns: Column[] = block.columns.map((col) => ({
        ...col,
        children: updateBlockInTree(col.children, id, updater),
      }));
      updatedBlock = { ...block, columns: newColumns } as Block;
    }
    // Handle table rows and cells
    else if ("rows" in block && Array.isArray(block.rows)) {
      const newRows: TableRow[] = block.rows.map((row) => ({
        ...row,
        cells: row.cells.map((cell) => ({
          ...cell,
          children: updateBlockInTree(cell.children, id, updater),
        })),
      }));
      updatedBlock = { ...block, rows: newRows } as Block;
    }
    // Leaf block, no children
    else {
      updatedBlock = { ...block } as Block;
    }

    acc.push(updatedBlock);
    return acc;
  }, []);
}

/**
 * Insert a block at a specific location.
 */
export function insertBlock(
  blocks: Block[],
  block: Block,
  parentId: string | null,
  index: number,
): Block[] {
  // Insert at root level
  if (parentId === null) {
    const newBlocks = [...blocks];
    newBlocks.splice(index, 0, block);
    return newBlocks;
  }

  // Handle composite IDs for columns (parentId::columnId)
  if (parentId.includes("::") && !parentId.includes(":::")) {
    const parts = parentId.split("::");
    if (parts.length === 2) {
      const [blockId, columnId] = parts;
      return updateBlockInTree(blocks, blockId, (parent) => {
        if (parent.type === "columns" && "columns" in parent) {
          return {
            ...parent,
            columns: parent.columns.map((col) =>
              col.id === columnId
                ? {
                    ...col,
                    children: [
                      ...col.children.slice(0, index),
                      block,
                      ...col.children.slice(index),
                    ],
                  }
                : col,
            ),
          };
        }
        return parent;
      });
    }

    // Handle table cells (parentId::rowId::cellId)
    if (parts.length === 3) {
      const [blockId, rowId, cellId] = parts;
      return updateBlockInTree(blocks, blockId, (parent) => {
        if (parent.type === "table" && "rows" in parent) {
          return {
            ...parent,
            rows: parent.rows.map((row) =>
              row.id === rowId
                ? {
                    ...row,
                    cells: row.cells.map((cell) =>
                      cell.id === cellId
                        ? {
                            ...cell,
                            children: [
                              ...cell.children.slice(0, index),
                              block,
                              ...cell.children.slice(index),
                            ],
                          }
                        : cell,
                    ),
                  }
                : row,
            ),
          };
        }
        return parent;
      });
    }
  }

  // Insert into regular children container
  return updateBlockInTree(blocks, parentId, (parent) => {
    if ("children" in parent && Array.isArray(parent.children)) {
      const newChildren = [...parent.children];
      newChildren.splice(index, 0, block);
      return { ...parent, children: newChildren } as Block;
    }
    return parent;
  });
}

/**
 * Remove a block from the tree.
 */
export function removeBlock(blocks: Block[], id: string): Block[] {
  return updateBlockInTree(blocks, id, () => null);
}

// ============================================================================
// Block Commands
// ============================================================================

/**
 * Command to add a block.
 */
export class AddBlockCommand implements Command<Template> {
  readonly type = "ADD_BLOCK";
  readonly description: string;
  private block: Block;
  private parentId: string | null;
  private index: number;

  constructor(block: Block, parentId: string | null, index: number) {
    this.block = block;
    this.parentId = parentId;
    this.index = index;
    this.description = `Add ${block.type} block`;
  }

  execute(state: Template): Template {
    return {
      ...state,
      blocks: insertBlock(state.blocks, this.block, this.parentId, this.index),
    };
  }

  undo(state: Template): Template {
    return {
      ...state,
      blocks: removeBlock(state.blocks, this.block.id),
    };
  }
}

/**
 * Command to update a block.
 */
export class UpdateBlockCommand implements Command<Template> {
  readonly type = "UPDATE_BLOCK";
  readonly description: string;
  private blockId: string;
  private updates: Partial<Block>;
  private previousBlock: Block | undefined;

  constructor(blockId: string, updates: Partial<Block>) {
    this.blockId = blockId;
    this.updates = updates;
    this.description = `Update block ${blockId}`;
  }

  execute(state: Template): Template {
    // Store previous block for undo
    this.previousBlock = findBlock(state.blocks, this.blockId);
    if (!this.previousBlock) {
      return state; // Block not found, no-op
    }

    return {
      ...state,
      blocks: updateBlockInTree(state.blocks, this.blockId, (block) => ({
        ...block,
        ...this.updates,
      }) as Block),
    };
  }

  undo(state: Template): Template {
    if (!this.previousBlock) {
      return state;
    }

    return {
      ...state,
      blocks: updateBlockInTree(state.blocks, this.blockId, () => this.previousBlock!),
    };
  }
}

/**
 * Command to delete a block.
 */
export class DeleteBlockCommand implements Command<Template> {
  readonly type = "DELETE_BLOCK";
  readonly description: string;
  private blockId: string;
  private deletedBlock: Block | undefined;
  private location: BlockLocation | undefined;

  constructor(blockId: string) {
    this.blockId = blockId;
    this.description = `Delete block ${blockId}`;
  }

  execute(state: Template): Template {
    // Store block and location for undo
    this.location = findBlockLocation(state.blocks, this.blockId);
    if (!this.location) {
      return state; // Block not found, no-op
    }
    this.deletedBlock = this.location.block;

    return {
      ...state,
      blocks: removeBlock(state.blocks, this.blockId),
    };
  }

  undo(state: Template): Template {
    if (!this.deletedBlock || !this.location) {
      return state;
    }

    const parentId = this.location.parent
      ? this.location.containerId
        ? `${this.location.parent.id}::${this.location.containerId}`
        : this.location.parent.id
      : null;

    return {
      ...state,
      blocks: insertBlock(
        state.blocks,
        this.deletedBlock,
        parentId,
        this.location.index,
      ),
    };
  }
}

/**
 * Command to move a block.
 */
export class MoveBlockCommand implements Command<Template> {
  readonly type = "MOVE_BLOCK";
  readonly description: string;
  private blockId: string;
  private newParentId: string | null;
  private newIndex: number;
  private originalLocation: BlockLocation | undefined;

  constructor(blockId: string, newParentId: string | null, newIndex: number) {
    this.blockId = blockId;
    this.newParentId = newParentId;
    this.newIndex = newIndex;
    this.description = `Move block ${blockId}`;
  }

  execute(state: Template): Template {
    // Store original location for undo
    this.originalLocation = findBlockLocation(state.blocks, this.blockId);
    if (!this.originalLocation) {
      return state; // Block not found, no-op
    }

    const block = this.originalLocation.block;

    // Remove from current location
    let newBlocks = removeBlock(state.blocks, this.blockId);

    // Insert at new location
    newBlocks = insertBlock(newBlocks, block, this.newParentId, this.newIndex);

    return { ...state, blocks: newBlocks };
  }

  undo(state: Template): Template {
    if (!this.originalLocation) {
      return state;
    }

    const block = findBlock(state.blocks, this.blockId);
    if (!block) {
      return state;
    }

    // Remove from new location
    let newBlocks = removeBlock(state.blocks, this.blockId);

    // Restore to original location
    const parentId = this.originalLocation.parent
      ? this.originalLocation.containerId
        ? `${this.originalLocation.parent.id}::${this.originalLocation.containerId}`
        : this.originalLocation.parent.id
      : null;

    newBlocks = insertBlock(newBlocks, block, parentId, this.originalLocation.index);

    return { ...state, blocks: newBlocks };
  }
}

// ============================================================================
// Document Commands
// ============================================================================

/**
 * Command to update document styles.
 */
export class UpdateDocumentStylesCommand implements Command<Template> {
  readonly type = "UPDATE_DOCUMENT_STYLES";
  readonly description = "Update document styles";
  private styles: Partial<DocumentStyles>;
  private previousStyles: DocumentStyles | undefined;

  constructor(styles: Partial<DocumentStyles>) {
    this.styles = styles;
  }

  execute(state: Template): Template {
    this.previousStyles = { ...state.documentStyles };
    return {
      ...state,
      documentStyles: { ...state.documentStyles, ...this.styles },
    };
  }

  undo(state: Template): Template {
    if (!this.previousStyles) {
      return state;
    }
    return {
      ...state,
      documentStyles: this.previousStyles,
    };
  }
}

/**
 * Command to update page settings.
 */
export class UpdatePageSettingsCommand implements Command<Template> {
  readonly type = "UPDATE_PAGE_SETTINGS";
  readonly description = "Update page settings";
  private settings: Partial<PageSettings>;
  private previousSettings: PageSettings | undefined;

  constructor(settings: Partial<PageSettings>) {
    this.settings = settings;
  }

  execute(state: Template): Template {
    this.previousSettings = { ...state.pageSettings };
    return {
      ...state,
      pageSettings: { ...state.pageSettings, ...this.settings },
    };
  }

  undo(state: Template): Template {
    if (!this.previousSettings) {
      return state;
    }
    return {
      ...state,
      pageSettings: this.previousSettings,
    };
  }
}

/**
 * Command to update theme ID.
 */
export class UpdateThemeCommand implements Command<Template> {
  readonly type = "UPDATE_THEME";
  readonly description = "Update theme";
  private themeId: string | null;
  private previousThemeId: string | null | undefined;

  constructor(themeId: string | null) {
    this.themeId = themeId;
  }

  execute(state: Template): Template {
    this.previousThemeId = state.themeId;
    return {
      ...state,
      themeId: this.themeId,
    };
  }

  undo(state: Template): Template {
    return {
      ...state,
      themeId: this.previousThemeId,
    };
  }
}

/**
 * Composite command that groups multiple commands.
 */
export class CompositeCommand implements Command<Template> {
  readonly type = "COMPOSITE";
  readonly description: string;
  private commands: Command<Template>[];

  constructor(commands: Command<Template>[], description?: string) {
    this.commands = commands;
    this.description =
      description ?? `Composite: ${commands.map((c) => c.type).join(", ")}`;
  }

  execute(state: Template): Template {
    return this.commands.reduce((s, cmd) => cmd.execute(s), state);
  }

  undo(state: Template): Template {
    // Undo in reverse order
    return [...this.commands].reverse().reduce((s, cmd) => cmd.undo(s), state);
  }
}
