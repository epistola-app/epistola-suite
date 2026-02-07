/**
 * Block definition types for the registry.
 *
 * This module defines the interface for registering block types.
 * Each block type provides:
 * - Metadata (type, label, icon, category)
 * - Factory function to create default instances
 * - Child accessor/mutator for container blocks
 * - Render function (for Phase 3)
 */

import type { Block, BlockType } from "../types/template.ts";

// ============================================================================
// Block Definition Interface
// ============================================================================

/**
 * Context passed to block render functions.
 * Will be expanded in Phase 3 when DOM rendering is implemented.
 */
export interface RenderContext {
  /** The root element to render into */
  container: HTMLElement;

  /** Current data context for expression evaluation */
  data: Record<string, unknown>;

  /** Whether in edit mode (vs preview mode) */
  isEditing: boolean;

  /** Callback to select this block */
  onSelect?: (blockId: string) => void;

  /** Callback when block content changes */
  onChange?: (blockId: string, updates: Partial<Block>) => void;
}

/**
 * Category for grouping blocks in the palette.
 */
export type BlockCategory =
  | "content" // Text, images, etc.
  | "structure" // Container, columns, etc.
  | "logic" // Conditional, loop
  | "layout" // Page break, header, footer
  | "data"; // Table, list

/**
 * Definition for a block type.
 *
 * Block definitions are registered with the registry to enable:
 * - Dynamic block creation from palette
 * - Generic tree operations without switch statements
 * - Type-safe child access/mutation
 *
 * @template T The specific block type this definition handles
 *
 * @example
 * ```typescript
 * const containerDef: BlockDefinition<ContainerBlock> = {
 *   type: 'container',
 *   label: 'Container',
 *   category: 'structure',
 *   icon: '<svg>...</svg>',
 *   createDefault: () => ({
 *     id: crypto.randomUUID(),
 *     type: 'container',
 *     children: []
 *   }),
 *   getChildren: (block) => block.children,
 *   setChildren: (block, children) => ({ ...block, children })
 * };
 * ```
 */
export interface BlockDefinition<T extends Block = Block> {
  /** The block type identifier (must match T['type']) */
  type: T["type"];

  /** Human-readable label for the block */
  label: string;

  /** Category for palette grouping */
  category: BlockCategory;

  /** SVG icon string for the block */
  icon: string;

  /** Optional description for tooltips */
  description?: string;

  /**
   * Create a new instance with default values.
   * Called when adding a block from the palette.
   */
  createDefault: () => T;

  /**
   * Get children from this block type.
   * Returns undefined for leaf blocks (no children).
   * For blocks with multiple child containers (columns, tables),
   * returns a flattened array of all children.
   */
  getChildren?: (block: T) => Block[] | undefined;

  /**
   * Set children on this block type.
   * Only defined for blocks that have a simple children array.
   * Blocks with complex child structures (columns, tables) don't define this.
   */
  setChildren?: (block: T, children: Block[]) => T;

  /**
   * Check if this block can contain another block type.
   * Defaults to true for container blocks.
   */
  canContain?: (childType: BlockType) => boolean;

  /**
   * Render the block to DOM.
   * Will be implemented in Phase 3.
   */
  render?: (block: T, context: RenderContext) => HTMLElement;
}

// ============================================================================
// Child Container Types
// ============================================================================

/**
 * Identifies a child container within a block.
 *
 * For simple blocks with `children`, containerId is null.
 * For columns: containerId is the column ID.
 * For tables: containerId is `rowId::cellId`.
 */
export interface ChildContainerRef {
  /** The parent block ID */
  blockId: string;

  /** The container ID within the block (null for simple children) */
  containerId: string | null;
}

/**
 * Operations for blocks with multiple child containers.
 * Used for columns and tables.
 */
export interface MultiContainerOps<T extends Block> {
  /**
   * Get all container references for this block.
   */
  getContainers: (block: T) => ChildContainerRef[];

  /**
   * Get children from a specific container.
   */
  getContainerChildren: (block: T, containerId: string) => Block[];

  /**
   * Set children in a specific container.
   */
  setContainerChildren: (
    block: T,
    containerId: string,
    children: Block[],
  ) => T;
}

/**
 * Extended block definition for multi-container blocks.
 */
export interface MultiContainerBlockDefinition<T extends Block>
  extends BlockDefinition<T>,
    MultiContainerOps<T> {}
