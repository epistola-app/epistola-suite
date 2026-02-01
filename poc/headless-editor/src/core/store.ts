import { atom, map } from 'nanostores';
import type { Template, Block } from './types.js';

/**
 * Create the editor store using nanostores
 * Framework-agnostic reactive state
 */
export function createEditorStore(initialTemplate: Template) {
  // Atoms for simple values
  const $selectedBlockId = atom<string | null>(null);

  // Map for complex objects
  const $template = map<Template>(initialTemplate);

  return {
    $template,
    $selectedBlockId,

    // Getters
    getTemplate: () => $template.get(),
    getSelectedBlockId: () => $selectedBlockId.get(),

    // Setters
    setTemplate: (template: Template) => $template.set(template),
    setSelectedBlockId: (id: string | null) => $selectedBlockId.set(id),

    // Subscribe to changes
    subscribeTemplate: (cb: (template: Template) => void) => $template.subscribe(cb),
    subscribeSelectedBlockId: (cb: (id: string | null) => void) => $selectedBlockId.subscribe(cb),
  };
}

export type EditorStore = ReturnType<typeof createEditorStore>;

/** Helper to check if a block has children */
function hasChildren(block: Block): block is Block & { children: Block[] } {
  return 'children' in block && Array.isArray(block.children);
}

/**
 * Block tree utilities - pure functions for manipulating nested blocks
 */
export const BlockTree = {
  /**
   * Find a block by ID in a nested structure
   */
  findBlock(blocks: Block[], id: string): Block | null {
    for (const block of blocks) {
      if (block.id === id) return block;
      if (hasChildren(block)) {
        const found = this.findBlock(block.children, id);
        if (found) return found;
      }
    }
    return null;
  },

  /**
   * Find parent of a block
   */
  findParent(blocks: Block[], id: string, parent: Block | null = null): Block | null {
    for (const block of blocks) {
      if (block.id === id) return parent;
      if (hasChildren(block)) {
        const found = this.findParent(block.children, id, block);
        if (found !== undefined) return found;
      }
    }
    return null;
  },

  /**
   * Add a block at a specific location
   */
  addBlock(blocks: Block[], newBlock: Block, parentId: string | null, index: number): Block[] {
    if (parentId === null) {
      // Add to root
      const result = [...blocks];
      result.splice(index, 0, newBlock);
      return result;
    }

    // Add to a parent with children
    return blocks.map((block) => {
      if (block.id === parentId && hasChildren(block)) {
        const children = [...block.children];
        children.splice(index, 0, newBlock);
        return { ...block, children };
      }
      if (hasChildren(block)) {
        return { ...block, children: this.addBlock(block.children, newBlock, parentId, index) };
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
      .map((block) => {
        if (hasChildren(block)) {
          return { ...block, children: this.removeBlock(block.children, id) };
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
        // Use type assertion since we're doing a partial update
        return { ...block, ...updates } as typeof block;
      }
      if (hasChildren(block)) {
        return { ...block, children: this.updateBlock(block.children, id, updates) } as typeof block;
      }
      return block;
    });
  },

  /**
   * Move a block to a new location
   */
  moveBlock(blocks: Block[], id: string, newParentId: string | null, newIndex: number): Block[] {
    const block = this.findBlock(blocks, id);
    if (!block) return blocks;

    // Remove from current location
    const withoutBlock = this.removeBlock(blocks, id);

    // Add to new location
    return this.addBlock(withoutBlock, block, newParentId, newIndex);
  },
};
