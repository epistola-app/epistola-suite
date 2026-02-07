import { createEditorStore, BlockTree, type EditorStore } from './store.js';
import { defaultBlockDefinitions } from './blocks.js';
import type {
  Template,
  Block,
  BlockDefinition,
  EditorConfig,
  EditorCallbacks,
  EditorState,
  ValidationResult,
  DropPosition,
  DropZone,
  DragDropPort,
} from './types.js';

/**
 * Generate a simple unique ID
 */
function generateId(): string {
  return `block-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

/**
 * Deep clone a template (for history snapshots)
 */
function cloneTemplate(template: Template): Template {
  return JSON.parse(JSON.stringify(template));
}

/**
 * Simple undo/redo manager using history stacks
 */
class UndoManager {
  private past: Template[] = [];
  private future: Template[] = [];
  private maxHistory: number;

  constructor(maxHistory = 50) {
    this.maxHistory = maxHistory;
  }

  /** Save current state before a mutation */
  push(state: Template): void {
    this.past.push(cloneTemplate(state));
    this.future = []; // Clear redo stack on new action

    // Limit history size
    if (this.past.length > this.maxHistory) {
      this.past.shift();
    }
  }

  /** Undo: restore previous state */
  undo(current: Template): Template | null {
    if (this.past.length === 0) return null;
    this.future.push(cloneTemplate(current));
    return this.past.pop()!;
  }

  /** Redo: restore next state */
  redo(current: Template): Template | null {
    if (this.future.length === 0) return null;
    this.past.push(cloneTemplate(current));
    return this.future.pop()!;
  }

  canUndo(): boolean {
    return this.past.length > 0;
  }

  canRedo(): boolean {
    return this.future.length > 0;
  }

  clear(): void {
    this.past = [];
    this.future = [];
  }
}

/**
 * TemplateEditor - Headless editor core
 *
 * Pure TypeScript, framework-agnostic.
 * Uses nanostores for reactive state.
 * UI is completely decoupled - bring your own components.
 */
export class TemplateEditor {
  private store: EditorStore;
  private blockDefinitions: Record<string, BlockDefinition>;
  private callbacks: EditorCallbacks;
  private undoManager: UndoManager;

  constructor(config: EditorConfig = {}) {
    const initialTemplate: Template = config.template ?? {
      id: generateId(),
      name: 'Untitled',
      blocks: [],
    };

    this.store = createEditorStore(initialTemplate);
    this.blockDefinitions = config.blocks ?? defaultBlockDefinitions;
    this.callbacks = config.callbacks ?? {};
    this.undoManager = new UndoManager();

    // Wire up change notifications
    this.store.subscribeTemplate((template) => {
      this.callbacks.onTemplateChange?.(template);
    });

    this.store.subscribeSelectedBlockId((id) => {
      this.callbacks.onBlockSelect?.(id);
    });
  }

  /** Save current state to history (call before mutations) */
  private saveToHistory(): void {
    this.undoManager.push(this.store.getTemplate());
  }

  // === STATE ACCESS ===

  /**
   * Get current editor state (snapshot, not reactive)
   */
  getState(): EditorState {
    return {
      template: this.store.getTemplate(),
      selectedBlockId: this.store.getSelectedBlockId(),
    };
  }

  /**
   * Subscribe to all state changes
   */
  subscribe(callback: (state: EditorState) => void): () => void {
    const unsubTemplate = this.store.subscribeTemplate(() => {
      callback(this.getState());
    });
    const unsubSelected = this.store.subscribeSelectedBlockId(() => {
      callback(this.getState());
    });

    // Return unsubscribe function
    return () => {
      unsubTemplate();
      unsubSelected();
    };
  }

  /**
   * Get the nanostores directly (for framework integrations)
   */
  getStores() {
    return {
      $template: this.store.$template,
      $selectedBlockId: this.store.$selectedBlockId,
    };
  }

  // === TEMPLATE OPERATIONS ===

  /**
   * Get current template
   */
  getTemplate(): Template {
    return this.store.getTemplate();
  }

  /**
   * Replace entire template
   */
  setTemplate(template: Template): void {
    this.store.setTemplate(template);
  }

  /**
   * Update template metadata (name, etc.)
   */
  updateTemplate(updates: Partial<Omit<Template, 'blocks'>>): void {
    const current = this.store.getTemplate();
    this.store.setTemplate({ ...current, ...updates });
  }

  // === BLOCK OPERATIONS ===

  /**
   * Add a new block
   */
  addBlock(type: string, parentId: string | null = null, index: number = -1): Block | null {
    const definition = this.blockDefinitions[type];
    if (!definition) {
      this.callbacks.onError?.(new Error(`Unknown block type: ${type}`));
      return null;
    }

    // Check if parent can have children using constraints
    if (parentId !== null) {
      const parent = this.findBlock(parentId);
      if (!parent) {
        this.callbacks.onError?.(new Error(`Parent block not found: ${parentId}`));
        return null;
      }
      const parentDef = this.blockDefinitions[parent.type];
      if (!parentDef?.constraints.canHaveChildren) {
        this.callbacks.onError?.(new Error(`Block type ${parent.type} cannot have children`));
        return null;
      }
      // Check if parent accepts this block type
      if (parentDef.constraints.allowedChildTypes !== null
          && !parentDef.constraints.allowedChildTypes.includes(type)) {
        this.callbacks.onError?.(new Error(`Block type ${parent.type} does not accept ${type} children`));
        return null;
      }
      // Check if this block type can be nested in parent
      if (definition.constraints.allowedParentTypes !== null
          && !definition.constraints.allowedParentTypes.includes(parent.type)) {
        this.callbacks.onError?.(new Error(`Block type ${type} cannot be nested in ${parent.type}`));
        return null;
      }
    }

    const newBlock = definition.create(generateId());

    // Callback check
    if (this.callbacks.onBeforeBlockAdd?.(newBlock, parentId) === false) {
      return null;
    }

    this.saveToHistory();

    const template = this.store.getTemplate();
    const targetIndex = index === -1 ? this.getChildCount(parentId) : index;
    const newBlocks = BlockTree.addBlock(template.blocks, newBlock, parentId, targetIndex);

    this.store.setTemplate({ ...template, blocks: newBlocks });
    return newBlock;
  }

  /**
   * Update a block
   */
  updateBlock(id: string, updates: Partial<Block>): void {
    const template = this.store.getTemplate();
    const newBlocks = BlockTree.updateBlock(template.blocks, id, updates);
    this.store.setTemplate({ ...template, blocks: newBlocks });
  }

  /**
   * Delete a block
   */
  deleteBlock(id: string): boolean {
    // Callback check
    if (this.callbacks.onBeforeBlockDelete?.(id) === false) {
      return false;
    }

    this.saveToHistory();

    const template = this.store.getTemplate();
    const newBlocks = BlockTree.removeBlock(template.blocks, id);
    this.store.setTemplate({ ...template, blocks: newBlocks });

    // Clear selection if deleted block was selected
    if (this.store.getSelectedBlockId() === id) {
      this.store.setSelectedBlockId(null);
    }

    return true;
  }

  /**
   * Move a block to a new location
   */
  moveBlock(id: string, newParentId: string | null, newIndex: number): void {
    this.saveToHistory();

    const template = this.store.getTemplate();
    const newBlocks = BlockTree.moveBlock(template.blocks, id, newParentId, newIndex);
    this.store.setTemplate({ ...template, blocks: newBlocks });
  }

  /**
   * Find a block by ID
   */
  findBlock(id: string): Block | null {
    return BlockTree.findBlock(this.store.getTemplate().blocks, id);
  }

  // === SELECTION ===

  /**
   * Select a block
   */
  selectBlock(id: string | null): void {
    this.store.setSelectedBlockId(id);
  }

  /**
   * Get selected block
   */
  getSelectedBlock(): Block | null {
    const id = this.store.getSelectedBlockId();
    return id ? this.findBlock(id) : null;
  }

  // === BLOCK REGISTRY ===

  /**
   * Register a custom block type
   */
  registerBlock(definition: BlockDefinition): void {
    this.blockDefinitions[definition.type] = definition;
  }

  /**
   * Get block definition
   */
  getBlockDefinition(type: string): BlockDefinition | undefined {
    return this.blockDefinitions[type];
  }

  /**
   * Get all registered block types
   */
  getBlockTypes(): string[] {
    return Object.keys(this.blockDefinitions);
  }

  // === VALIDATION ===

  /**
   * Validate entire template
   */
  validateTemplate(): ValidationResult {
    const errors: string[] = [];
    const template = this.store.getTemplate();

    const validateBlocks = (blocks: Block[]) => {
      for (const block of blocks) {
        const definition = this.blockDefinitions[block.type];
        if (!definition) {
          errors.push(`Unknown block type: ${block.type}`);
          continue;
        }

        const result = definition.validate(block);
        errors.push(...result.errors);

        if ('children' in block && Array.isArray(block.children)) {
          validateBlocks(block.children);
        }
      }
    };

    validateBlocks(template.blocks);
    return { valid: errors.length === 0, errors };
  }

  // === SERIALIZATION ===

  /**
   * Export template to JSON string
   */
  exportJSON(): string {
    return JSON.stringify(this.store.getTemplate(), null, 2);
  }

  /**
   * Import template from JSON string
   */
  importJSON(json: string): void {
    try {
      const template = JSON.parse(json) as Template;
      this.store.setTemplate(template);
    } catch (e) {
      this.callbacks.onError?.(new Error(`Invalid JSON: ${(e as Error).message}`));
    }
  }

  // === UNDO / REDO ===

  /**
   * Undo the last action
   */
  undo(): boolean {
    const previous = this.undoManager.undo(this.store.getTemplate());
    if (previous) {
      this.store.setTemplate(previous);
      return true;
    }
    return false;
  }

  /**
   * Redo the last undone action
   */
  redo(): boolean {
    const next = this.undoManager.redo(this.store.getTemplate());
    if (next) {
      this.store.setTemplate(next);
      return true;
    }
    return false;
  }

  /**
   * Check if undo is available
   */
  canUndo(): boolean {
    return this.undoManager.canUndo();
  }

  /**
   * Check if redo is available
   */
  canRedo(): boolean {
    return this.undoManager.canRedo();
  }

  // === DRAG & DROP PORT ===
  // Editor provides the logic, user's adapter (SortableJS, react-dnd, etc.) consumes it

  /**
   * Can this block be dragged?
   */
  canDrag(blockId: string): boolean {
    const block = this.findBlock(blockId);
    if (!block) return false;

    const definition = this.blockDefinitions[block.type];
    if (!definition) return false;

    return definition.constraints.canBeDragged;
  }

  /**
   * Can this block be dropped at this location?
   * Both the dragged block and target must agree on the relationship.
   */
  canDrop(draggedId: string, targetId: string | null, position: DropPosition): boolean {
    const dragged = this.findBlock(draggedId);
    if (!dragged) return false;

    const draggedDef = this.blockDefinitions[dragged.type];
    if (!draggedDef) return false;

    // Can't drag if not draggable
    if (!draggedDef.constraints.canBeDragged) return false;

    // Can't drop on itself
    if (draggedId === targetId) return false;

    // Dropping at root level
    if (targetId === null) {
      // Check if block allows root as parent
      const allowedParents = draggedDef.constraints.allowedParentTypes;
      return allowedParents === null || allowedParents.includes('root');
    }

    const target = this.findBlock(targetId);
    if (!target) return false;

    const targetDef = this.blockDefinitions[target.type];
    if (!targetDef) return false;

    // Can't drop inside own descendants (would create cycle)
    if (this.isDescendant(draggedId, targetId)) return false;

    if (position === 'inside') {
      // Target must accept children
      if (!targetDef.constraints.canHaveChildren) return false;

      // Target must accept this block type as child
      const allowedChildren = targetDef.constraints.allowedChildTypes;
      if (allowedChildren !== null && !allowedChildren.includes(dragged.type)) {
        return false;
      }

      // Dragged must allow this parent type
      const allowedParents = draggedDef.constraints.allowedParentTypes;
      if (allowedParents !== null && !allowedParents.includes(target.type)) {
        return false;
      }

      // Check max children
      if (targetDef.constraints.maxChildren !== undefined) {
        const currentChildren = 'children' in target ? target.children.length : 0;
        if (currentChildren >= targetDef.constraints.maxChildren) {
          return false;
        }
      }

      return true;
    }

    // position === 'before' or 'after' means sibling
    // Find target's parent and check if dragged can be sibling
    const targetParent = BlockTree.findParent(this.store.getTemplate().blocks, targetId, null);

    if (targetParent === null) {
      // Target is at root, check if dragged allows root
      const allowedParents = draggedDef.constraints.allowedParentTypes;
      return allowedParents === null || allowedParents.includes('root');
    }

    const targetParentDef = this.blockDefinitions[targetParent.type];
    if (!targetParentDef) return false;

    // Parent must accept dragged as child
    const allowedChildren = targetParentDef.constraints.allowedChildTypes;
    if (allowedChildren !== null && !allowedChildren.includes(dragged.type)) {
      return false;
    }

    // Dragged must allow this parent type
    const allowedParents = draggedDef.constraints.allowedParentTypes;
    if (allowedParents !== null && !allowedParents.includes(targetParent.type)) {
      return false;
    }

    return true;
  }

  /**
   * Get all valid drop zones for a dragged block (for UI hints)
   */
  getDropZones(draggedId: string): DropZone[] {
    const zones: DropZone[] = [];
    const template = this.store.getTemplate();

    // Check root level
    if (this.canDrop(draggedId, null, 'inside')) {
      zones.push({ targetId: null, position: 'inside', targetType: null });
    }

    // Recursively check all blocks
    const checkBlock = (block: Block) => {
      // Check dropping inside this block
      if (this.canDrop(draggedId, block.id, 'inside')) {
        zones.push({ targetId: block.id, position: 'inside', targetType: block.type });
      }

      // Check dropping before/after this block
      if (this.canDrop(draggedId, block.id, 'before')) {
        zones.push({ targetId: block.id, position: 'before', targetType: block.type });
      }
      if (this.canDrop(draggedId, block.id, 'after')) {
        zones.push({ targetId: block.id, position: 'after', targetType: block.type });
      }

      // Recurse into children
      if ('children' in block && Array.isArray(block.children)) {
        for (const child of block.children) {
          checkBlock(child);
        }
      }
    };

    for (const block of template.blocks) {
      checkBlock(block);
    }

    return zones;
  }

  /**
   * Execute a drop operation
   */
  drop(draggedId: string, targetId: string | null, index: number): void {
    if (!this.canDrop(draggedId, targetId, 'inside')) {
      this.callbacks.onError?.(new Error('Invalid drop target'));
      return;
    }
    this.moveBlock(draggedId, targetId, index);
  }

  /**
   * Get the DragDropPort interface for adapter integration
   */
  getDragDropPort(): DragDropPort {
    return {
      canDrag: this.canDrag.bind(this),
      canDrop: this.canDrop.bind(this),
      getDropZones: this.getDropZones.bind(this),
      drop: this.drop.bind(this),
    };
  }

  // === HELPERS ===

  private getChildCount(parentId: string | null): number {
    if (parentId === null) {
      return this.store.getTemplate().blocks.length;
    }
    const parent = this.findBlock(parentId);
    if (parent && 'children' in parent && Array.isArray(parent.children)) {
      return parent.children.length;
    }
    return 0;
  }

  /**
   * Check if potentialDescendantId is a descendant of ancestorId
   */
  private isDescendant(ancestorId: string, potentialDescendantId: string): boolean {
    const ancestor = this.findBlock(ancestorId);
    if (!ancestor || !('children' in ancestor)) return false;

    const checkChildren = (children: Block[]): boolean => {
      for (const child of children) {
        if (child.id === potentialDescendantId) return true;
        if ('children' in child && Array.isArray(child.children)) {
          if (checkChildren(child.children)) return true;
        }
      }
      return false;
    };

    return checkChildren(ancestor.children);
  }
}
