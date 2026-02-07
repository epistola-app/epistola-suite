/**
 * Command pattern for all state mutations.
 *
 * All mutations go through commands, enabling:
 * - Built-in undo/redo support
 * - Serializable operations for debugging
 * - Centralized validation
 *
 * Tree operations are delegated to blocks/tree.ts which uses the registry
 * for generic handling of all block types.
 */

import type {
  Block,
  Template,
  DocumentStyles,
  PageSettings,
} from "../types/template.ts";

// Import tree operations from blocks/tree.ts (registry-based, no hardcoded types)
import {
  getChildren,
  findBlock,
  findBlockLocation,
  updateBlock,
  insertBlock as treeInsertBlock,
  removeBlock,
  type BlockLocation,
} from "../blocks/tree.ts";

// Re-export tree functions for backward compatibility
export { getChildren, findBlock, findBlockLocation, updateBlock, removeBlock };
export type { BlockLocation };

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
// Block Tree Operations (Adapters for composite IDs)
// ============================================================================

/**
 * Insert a block at a specific location.
 * Handles composite IDs (parentId::containerId) for multi-container blocks.
 */
export function insertBlock(
  blocks: Block[],
  block: Block,
  parentId: string | null,
  index: number,
): Block[] {
  // Insert at root level
  if (parentId === null) {
    return treeInsertBlock(blocks, block, null, index);
  }

  // Parse composite IDs for multi-container blocks
  if (parentId.includes("::")) {
    const parts = parentId.split("::");
    const blockId = parts[0];
    const containerId = parts.slice(1).join("::");
    return treeInsertBlock(blocks, block, blockId, index, containerId);
  }

  // Simple parent ID
  return treeInsertBlock(blocks, block, parentId, index);
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
      blocks: updateBlock(state.blocks, this.blockId, (block) => ({
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
      blocks: updateBlock(state.blocks, this.blockId, () => this.previousBlock!),
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
