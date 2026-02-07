/**
 * Block type registry.
 *
 * Central registry for all block types. Block definitions are registered here
 * and can be looked up by type. This enables:
 *
 * - Dynamic block creation from palette
 * - Generic tree operations without switch statements
 * - Type-safe child access/mutation
 * - Block metadata for UI (labels, icons, categories)
 *
 * @example
 * ```typescript
 * import { registry, registerBlock } from './registry';
 *
 * // Register a block type
 * registerBlock({
 *   type: 'container',
 *   label: 'Container',
 *   category: 'structure',
 *   icon: '<svg>...</svg>',
 *   createDefault: () => ({ id: crypto.randomUUID(), type: 'container', children: [] }),
 *   getChildren: (block) => block.children,
 *   setChildren: (block, children) => ({ ...block, children })
 * });
 *
 * // Look up a block type
 * const def = registry.get('container');
 * const block = def?.createDefault();
 *
 * // Get all blocks in a category
 * const structureBlocks = registry.getByCategory('structure');
 * ```
 */

import type { Block, BlockType } from "../types/template.ts";
import type { BlockDefinition, BlockCategory } from "./types.ts";
import { setRegistryLookup } from "./tree.ts";

// ============================================================================
// Registry Implementation
// ============================================================================

const definitions = new Map<string, BlockDefinition>();
const byCategory = new Map<BlockCategory, BlockDefinition[]>();

/**
 * Block registry singleton.
 */
export const registry = {
  /**
   * Get a block definition by type.
   */
  get(type: string): BlockDefinition | undefined {
    return definitions.get(type);
  },

  /**
   * Get all registered block definitions.
   */
  getAll(): BlockDefinition[] {
    return Array.from(definitions.values());
  },

  /**
   * Get all block definitions in a category.
   */
  getByCategory(category: BlockCategory): BlockDefinition[] {
    return byCategory.get(category) ?? [];
  },

  /**
   * Get all categories with their blocks.
   */
  getAllCategories(): Map<BlockCategory, BlockDefinition[]> {
    return new Map(byCategory);
  },

  /**
   * Check if a block type is registered.
   */
  has(type: string): boolean {
    return definitions.has(type);
  },

  /**
   * Get the number of registered block types.
   */
  get size(): number {
    return definitions.size;
  },

  /**
   * Clear all registered block types.
   * Primarily for testing.
   */
  clear(): void {
    definitions.clear();
    byCategory.clear();
  },
};

// ============================================================================
// Registration
// ============================================================================

/**
 * Register a block type.
 *
 * @param definition The block definition
 * @throws Error if block type is already registered
 *
 * @example
 * ```typescript
 * registerBlock({
 *   type: 'text',
 *   label: 'Text',
 *   category: 'content',
 *   icon: '<svg>...</svg>',
 *   createDefault: () => ({
 *     id: crypto.randomUUID(),
 *     type: 'text',
 *     content: { type: 'doc', content: [] }
 *   })
 * });
 * ```
 */
export function registerBlock<T extends Block>(
  definition: BlockDefinition<T>,
): void {
  if (definitions.has(definition.type)) {
    throw new Error(
      `Block type "${definition.type}" is already registered`,
    );
  }

  // Cast through unknown to avoid strict type checking on contravariant generic
  definitions.set(definition.type, definition as unknown as BlockDefinition);

  // Add to category index
  const categoryBlocks = byCategory.get(definition.category) ?? [];
  categoryBlocks.push(definition as unknown as BlockDefinition);
  byCategory.set(definition.category, categoryBlocks);
}

/**
 * Unregister a block type.
 * Primarily for testing.
 */
export function unregisterBlock(type: string): boolean {
  const def = definitions.get(type);
  if (!def) {
    return false;
  }

  definitions.delete(type);

  // Remove from category index
  const categoryBlocks = byCategory.get(def.category);
  if (categoryBlocks) {
    const index = categoryBlocks.indexOf(def);
    if (index !== -1) {
      categoryBlocks.splice(index, 1);
    }
    if (categoryBlocks.length === 0) {
      byCategory.delete(def.category);
    }
  }

  return true;
}

// ============================================================================
// Utility Functions
// ============================================================================

/**
 * Create a new block of the given type.
 *
 * @param type The block type
 * @returns A new block instance, or undefined if type not registered
 */
export function createBlock(type: BlockType): Block | undefined {
  const def = definitions.get(type);
  return def?.createDefault();
}

/**
 * Get the label for a block type.
 */
export function getBlockLabel(type: string): string {
  return definitions.get(type)?.label ?? type;
}

/**
 * Get the icon for a block type.
 */
export function getBlockIcon(type: string): string {
  return definitions.get(type)?.icon ?? "";
}

/**
 * Check if a parent block can contain a child block type.
 */
export function canContain(parentType: string, childType: BlockType): boolean {
  const def = definitions.get(parentType);
  if (!def) {
    return false;
  }

  // If canContain is defined, use it
  if (def.canContain) {
    return def.canContain(childType);
  }

  // Default: can contain if parent has getChildren defined
  return def.getChildren !== undefined;
}

// ============================================================================
// Registry Initialization
// ============================================================================

// Connect the registry to tree.ts for generic tree operations
setRegistryLookup((type) => definitions.get(type));
