/**
 * Generic tree traversal operations for blocks.
 *
 * These operations work with the block registry to handle nested structures
 * generically, without needing switch statements for each block type.
 */

import type { Block } from "../types/template.ts";
import type { BlockDefinition, MultiContainerBlockDefinition } from "./types.ts";

// ============================================================================
// Registry Reference (will be set by registry.ts)
// ============================================================================

let getDefinition: ((type: string) => BlockDefinition | undefined) | null = null;

/**
 * Set the registry lookup function.
 * Called by registry.ts during initialization.
 * @internal
 */
export function setRegistryLookup(
  lookup: (type: string) => BlockDefinition | undefined,
): void {
  getDefinition = lookup;
}

// ============================================================================
// Block Location Types
// ============================================================================

/**
 * Location of a block within the tree.
 */
export interface BlockLocation {
  /** The block itself */
  block: Block;

  /** The parent block (null if at root) */
  parent: Block | null;

  /** For multi-container blocks, the container ID */
  containerId: string | null;

  /** Index within the parent's children array */
  index: number;
}

// ============================================================================
// Tree Traversal
// ============================================================================

/**
 * Get all children from a block using the registry.
 * Returns empty array for leaf blocks.
 */
export function getChildren(block: Block): Block[] {
  const def = getDefinition?.(block.type);
  if (def?.getChildren) {
    return def.getChildren(block) ?? [];
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
    const children = getChildren(block);
    const found = findBlock(children, id);
    if (found) {
      return found;
    }
  }
  return undefined;
}

/**
 * Find a block and its location in the tree.
 */
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

    const def = getDefinition?.(block.type);
    if (!def) continue;

    // Check if this is a multi-container block
    const multiDef = def as MultiContainerBlockDefinition<Block>;
    if (multiDef.getContainers) {
      const containers = multiDef.getContainers(block);
      for (const container of containers) {
        const containerChildren = multiDef.getContainerChildren(
          block,
          container.containerId!,
        );
        const found = findBlockLocation(
          containerChildren,
          id,
          block,
          container.containerId,
        );
        if (found) return found;
      }
    } else if (def.getChildren) {
      // Simple children array
      const children = def.getChildren(block);
      if (children) {
        const found = findBlockLocation(children, id, block, null);
        if (found) return found;
      }
    }
  }
  return undefined;
}

// ============================================================================
// Tree Mutation
// ============================================================================

/**
 * Update a block in the tree by ID.
 * Returns a new tree with the updated block.
 *
 * @param blocks The block tree
 * @param id The block ID to update
 * @param updater Function that receives the block and returns updated block (or null to remove)
 */
export function updateBlock(
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

    const def = getDefinition?.(block.type);
    if (!def) {
      acc.push(block);
      return acc;
    }

    // Check if this is a multi-container block
    const multiDef = def as MultiContainerBlockDefinition<Block>;
    if (multiDef.getContainers && multiDef.setContainerChildren) {
      const containers = multiDef.getContainers(block);
      let updatedBlock = block;
      for (const container of containers) {
        const containerChildren = multiDef.getContainerChildren(
          updatedBlock,
          container.containerId!,
        );
        const newChildren = updateBlock(containerChildren, id, updater);
        if (newChildren !== containerChildren) {
          updatedBlock = multiDef.setContainerChildren(
            updatedBlock,
            container.containerId!,
            newChildren,
          );
        }
      }
      acc.push(updatedBlock);
    } else if (def.getChildren && def.setChildren) {
      // Simple children array
      const children = def.getChildren(block);
      if (children) {
        const newChildren = updateBlock(children, id, updater);
        acc.push(def.setChildren(block, newChildren));
      } else {
        acc.push(block);
      }
    } else {
      // Leaf block, no children
      acc.push(block);
    }

    return acc;
  }, []);
}

/**
 * Insert a block at a specific location.
 *
 * @param blocks The block tree
 * @param block The block to insert
 * @param parentId Parent block ID, or null for root level
 * @param index Position to insert at
 * @param containerId For multi-container blocks, the container ID
 */
export function insertBlock(
  blocks: Block[],
  block: Block,
  parentId: string | null,
  index: number,
  containerId: string | null = null,
): Block[] {
  // Insert at root level
  if (parentId === null) {
    const newBlocks = [...blocks];
    newBlocks.splice(index, 0, block);
    return newBlocks;
  }

  return updateBlock(blocks, parentId, (parent) => {
    const def = getDefinition?.(parent.type);
    if (!def) return parent;

    // Multi-container block with specific container
    const multiDef = def as MultiContainerBlockDefinition<Block>;
    if (containerId && multiDef.getContainerChildren && multiDef.setContainerChildren) {
      const containerChildren = multiDef.getContainerChildren(parent, containerId);
      const newChildren = [...containerChildren];
      newChildren.splice(index, 0, block);
      return multiDef.setContainerChildren(parent, containerId, newChildren);
    }

    // Simple children array
    if (def.getChildren && def.setChildren) {
      const children = def.getChildren(parent) ?? [];
      const newChildren = [...children];
      newChildren.splice(index, 0, block);
      return def.setChildren(parent, newChildren);
    }

    return parent;
  });
}

/**
 * Remove a block from the tree.
 */
export function removeBlock(blocks: Block[], id: string): Block[] {
  return updateBlock(blocks, id, () => null);
}

/**
 * Move a block from one location to another.
 *
 * @param blocks The block tree
 * @param blockId The block to move
 * @param newParentId New parent ID (null for root)
 * @param newIndex New position
 * @param newContainerId For multi-container parents, the container ID
 */
export function moveBlock(
  blocks: Block[],
  blockId: string,
  newParentId: string | null,
  newIndex: number,
  newContainerId: string | null = null,
): Block[] {
  const location = findBlockLocation(blocks, blockId);
  if (!location) {
    return blocks;
  }

  const block = location.block;

  // Remove from current location
  let newBlocks = removeBlock(blocks, blockId);

  // Adjust index if moving within same parent
  let adjustedIndex = newIndex;
  if (
    newParentId === (location.parent?.id ?? null) &&
    newContainerId === location.containerId
  ) {
    if (location.index < newIndex) {
      adjustedIndex--;
    }
  }

  // Insert at new location
  newBlocks = insertBlock(newBlocks, block, newParentId, adjustedIndex, newContainerId);

  return newBlocks;
}

// ============================================================================
// Tree Utilities
// ============================================================================

/**
 * Walk the entire tree, calling a visitor function for each block.
 */
export function walkTree(
  blocks: Block[],
  visitor: (block: Block, parent: Block | null, containerId: string | null) => void,
  parent: Block | null = null,
  containerId: string | null = null,
): void {
  for (const block of blocks) {
    visitor(block, parent, containerId);

    const def = getDefinition?.(block.type);
    if (!def) continue;

    const multiDef = def as MultiContainerBlockDefinition<Block>;
    if (multiDef.getContainers) {
      const containers = multiDef.getContainers(block);
      for (const container of containers) {
        const containerChildren = multiDef.getContainerChildren(
          block,
          container.containerId!,
        );
        walkTree(containerChildren, visitor, block, container.containerId);
      }
    } else if (def.getChildren) {
      const children = def.getChildren(block);
      if (children) {
        walkTree(children, visitor, block, null);
      }
    }
  }
}

/**
 * Count all blocks in the tree.
 */
export function countBlocks(blocks: Block[]): number {
  let count = 0;
  walkTree(blocks, () => count++);
  return count;
}

/**
 * Find all blocks of a specific type.
 */
export function findBlocksByType<T extends Block>(
  blocks: Block[],
  type: T["type"],
): T[] {
  const result: T[] = [];
  walkTree(blocks, (block) => {
    if (block.type === type) {
      result.push(block as T);
    }
  });
  return result;
}

/**
 * Get the path from root to a block.
 * Returns array of block IDs from root to the block (inclusive).
 */
export function getBlockPath(blocks: Block[], id: string): string[] | undefined {
  function search(
    searchBlocks: Block[],
    path: string[],
  ): string[] | undefined {
    for (const block of searchBlocks) {
      const newPath = [...path, block.id];
      if (block.id === id) {
        return newPath;
      }
      const children = getChildren(block);
      const found = search(children, newPath);
      if (found) {
        return found;
      }
    }
    return undefined;
  }

  return search(blocks, []);
}
