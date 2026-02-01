import type { BlockDefinition, TextBlock, ContainerBlock, Block } from './types.js';

/**
 * Text block definition
 */
export const textBlockDefinition: BlockDefinition = {
  type: 'text',

  create: (id: string): TextBlock => ({
    id,
    type: 'text',
    content: '',
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === 'text' && typeof block.content !== 'string') {
      errors.push('Text block content must be a string');
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

/**
 * Container block definition
 */
export const containerBlockDefinition: BlockDefinition = {
  type: 'container',

  create: (id: string): ContainerBlock => ({
    id,
    type: 'container',
    children: [],
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (block.type === 'container' && !Array.isArray(block.children)) {
      errors.push('Container block must have children array');
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

/**
 * Columns block - a layout block that ONLY accepts column children
 * Demonstrates constrained parent-child relationships
 */
export const columnsBlockDefinition: BlockDefinition = {
  type: 'columns',

  create: (id: string) => ({
    id,
    type: 'columns' as const,
    children: [],
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (!('children' in block) || !Array.isArray(block.children)) {
      errors.push('Columns block must have children array');
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: true,
    allowedChildTypes: ['column'], // ONLY accepts column blocks
    maxChildren: 4,
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: null, // can go anywhere at root or in containers
  },
};

/**
 * Column block - can ONLY exist inside a columns block
 * Demonstrates constrained child placement
 */
export const columnBlockDefinition: BlockDefinition = {
  type: 'column',

  create: (id: string) => ({
    id,
    type: 'column' as const,
    children: [],
  }),

  validate: (block: Block) => {
    const errors: string[] = [];
    if (!('children' in block) || !Array.isArray(block.children)) {
      errors.push('Column block must have children array');
    }
    return { valid: errors.length === 0, errors };
  },

  constraints: {
    canHaveChildren: true,
    allowedChildTypes: null, // accepts any block type inside
    canBeDragged: true,
    canBeNested: true,
    allowedParentTypes: ['columns'], // can ONLY be inside columns block
  },
};

/**
 * Default block definitions
 */
export const defaultBlockDefinitions: Record<string, BlockDefinition> = {
  text: textBlockDefinition,
  container: containerBlockDefinition,
  columns: columnsBlockDefinition,
  column: columnBlockDefinition,
};
