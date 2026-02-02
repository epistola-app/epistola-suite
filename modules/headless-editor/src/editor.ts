/**
 * TemplateEditor - Headless editor core
 *
 * Pure TypeScript, framework-agnostic.
 * Uses nanostores for reactive state.
 * UI is completely decoupled - bring your own components.
 */

import { createEditorStore, BlockTree, type EditorStore } from './store.js';
import { defaultBlockDefinitions } from './blocks/definitions.js';
import { UndoManager } from './undo.js';
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
  ColumnsBlock,
  TableBlock,
  DataExample,
  JsonObject,
} from './types.js';
import { DEFAULT_TEST_DATA } from './types.js';

/**
 * Generate a simple unique ID
 */
function generateId(): string {
  return `block-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

/**
 * TemplateEditor - Headless editor core
 *
 * Manages template state, block operations, undo/redo, and drag-drop validation.
 * Completely UI-agnostic - pair with any rendering adapter.
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

  // =========================================================================
  // STATE ACCESS
  // =========================================================================

  /**
   * Get current editor state (snapshot, not reactive)
   */
  getState(): EditorState {
    return {
      template: this.store.getTemplate(),
      selectedBlockId: this.store.getSelectedBlockId(),
      dataExamples: this.store.getDataExamples(),
      selectedDataExampleId: this.store.getSelectedDataExampleId(),
      testData: this.store.getTestData(),
    };
  }

  /**
   * Subscribe to all state changes
   * Returns an unsubscribe function
   */
  subscribe(callback: (state: EditorState) => void): () => void {
    const unsubTemplate = this.store.subscribeTemplate(() => {
      callback(this.getState());
    });
    const unsubSelected = this.store.subscribeSelectedBlockId(() => {
      callback(this.getState());
    });
    const unsubDataExamples = this.store.subscribeDataExamples(() => {
      callback(this.getState());
    });
    const unsubSelectedDataExampleId = this.store.subscribeSelectedDataExampleId(() => {
      callback(this.getState());
    });
    const unsubTestData = this.store.subscribeTestData(() => {
      callback(this.getState());
    });

    return () => {
      unsubTemplate();
      unsubSelected();
      unsubDataExamples();
      unsubSelectedDataExampleId();
      unsubTestData();
    };
  }

  /**
   * Get the nanostores directly (for framework integrations)
   */
  getStores() {
    return {
      $template: this.store.$template,
      $selectedBlockId: this.store.$selectedBlockId,
      $dataExamples: this.store.$dataExamples,
      $selectedDataExampleId: this.store.$selectedDataExampleId,
      $testData: this.store.$testData,
    };
  }

  // =========================================================================
  // DATA EXAMPLES
  // =========================================================================

  /**
   * Set all data examples with auto-select logic
   * If examples is non-empty and no example is selected, auto-select the first one
   */
  setDataExamples(examples: DataExample[]): void {
    const currentSelectedId = this.store.getSelectedDataExampleId();

    this.store.setDataExamples(examples);

    if (examples.length > 0 && !currentSelectedId) {
      // Auto-select first example
      this.selectDataExample(examples[0]!.id);
    } else if (examples.length === 0) {
      // Clear selection and restore default test data
      this.store.setSelectedDataExampleId(null);
      this.store.setTestData(JSON.parse(JSON.stringify(DEFAULT_TEST_DATA)) as JsonObject);
    }
  }

  /**
   * Add a single data example
   */
  addDataExample(example: DataExample): void {
    const current = this.store.getDataExamples();
    this.store.setDataExamples([...current, example]);
  }

  /**
   * Update a data example by ID
   * If the updated example is selected, sync its data to testData
   */
  updateDataExample(id: string, updates: Partial<DataExample>): void {
    const current = this.store.getDataExamples();
    const updatedExamples = current.map((ex) => {
      if (ex.id === id) {
        const updated = { ...ex, ...updates };
        return updated;
      }
      return ex;
    });

    this.store.setDataExamples(updatedExamples);

    // If the updated example is currently selected, sync testData
    if (this.store.getSelectedDataExampleId() === id && updates.data) {
      this.store.setTestData(JSON.parse(JSON.stringify(updates.data)) as JsonObject);
    }
  }

  /**
   * Delete a data example by ID
   * If the deleted example was selected, select another or restore defaults
   */
  deleteDataExample(id: string): void {
    const current = this.store.getDataExamples();
    const wasSelected = this.store.getSelectedDataExampleId() === id;

    const filtered = current.filter((ex) => ex.id !== id);
    this.store.setDataExamples(filtered);

    if (wasSelected) {
      if (filtered.length > 0) {
        // Select first remaining example
        this.selectDataExample(filtered[0]!.id);
      } else {
        // Clear selection and restore default test data
        this.store.setSelectedDataExampleId(null);
        this.store.setTestData(JSON.parse(JSON.stringify(DEFAULT_TEST_DATA)) as JsonObject);
      }
    }
  }

  /**
   * Select a data example by ID (or null to deselect)
   * Copies the example's data to testData
   */
  selectDataExample(id: string | null): void {
    this.store.setSelectedDataExampleId(id);

    if (id === null) {
      // Restore default test data
      this.store.setTestData(JSON.parse(JSON.stringify(DEFAULT_TEST_DATA)) as JsonObject);
    } else {
      const examples = this.store.getDataExamples();
      const selected = examples.find((ex) => ex.id === id);
      if (selected) {
        // Deep copy to avoid mutation issues
        this.store.setTestData(JSON.parse(JSON.stringify(selected.data)) as JsonObject);
      }
    }
  }

  /**
   * Get all data examples
   */
  getDataExamples(): DataExample[] {
    return this.store.getDataExamples();
  }

  /**
   * Get the currently selected data example ID
   */
  getSelectedDataExampleId(): string | null {
    return this.store.getSelectedDataExampleId();
  }

  /**
   * Get current test data
   */
  getTestData(): JsonObject {
    return this.store.getTestData();
  }

  // =========================================================================
  // TEMPLATE OPERATIONS
  // =========================================================================

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

  // =========================================================================
  // BLOCK OPERATIONS
  // =========================================================================

  /**
   * Add a new block
   * Returns the created block, or null if creation failed
   */
  addBlock(type: string, parentId: string | null = null, index: number = -1): Block | null {
    const definition = this.blockDefinitions[type];
    if (!definition) {
      this.callbacks.onError?.(new Error(`Unknown block type: ${type}`));
      return null;
    }

    // Check if parent can have children using constraints
    if (parentId !== null) {
      const validationResult = this.validateParentForChild(parentId, type);
      if (!validationResult.valid) {
        this.callbacks.onError?.(new Error(validationResult.errors[0]));
        return null;
      }
    } else {
      // Adding to root - check if block type allows root
      if (
        definition.constraints.allowedParentTypes !== null &&
        !definition.constraints.allowedParentTypes.includes('root')
      ) {
        this.callbacks.onError?.(new Error(`Block type ${type} cannot be placed at root level`));
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
   * Validate if a parent can accept a child of given type
   */
  private validateParentForChild(parentId: string, childType: string): ValidationResult {
    const template = this.store.getTemplate();
    const errors: string[] = [];

    // Check if parent is a regular block with children
    const parent = BlockTree.findBlock(template.blocks, parentId);
    if (parent) {
      const parentDef = this.blockDefinitions[parent.type];
      if (!parentDef) {
        errors.push(`Unknown parent block type: ${parent.type}`);
        return { valid: false, errors };
      }

      if (!parentDef.constraints.canHaveChildren) {
        errors.push(`Block type ${parent.type} cannot have children`);
        return { valid: false, errors };
      }

      if (
        parentDef.constraints.allowedChildTypes !== null &&
        !parentDef.constraints.allowedChildTypes.includes(childType)
      ) {
        errors.push(`Block type ${parent.type} does not accept ${childType} children`);
        return { valid: false, errors };
      }

      const childDef = this.blockDefinitions[childType];
      if (
        childDef &&
        childDef.constraints.allowedParentTypes !== null &&
        !childDef.constraints.allowedParentTypes.includes(parent.type)
      ) {
        errors.push(`Block type ${childType} cannot be nested in ${parent.type}`);
        return { valid: false, errors };
      }

      return { valid: true, errors: [] };
    }

    // Check if parent is a column
    const columnResult = BlockTree.findColumn(template.blocks, parentId);
    if (columnResult) {
      // Columns can accept any block type
      const childDef = this.blockDefinitions[childType];
      if (
        childDef &&
        childDef.constraints.allowedParentTypes !== null &&
        !childDef.constraints.allowedParentTypes.includes('columns')
      ) {
        errors.push(`Block type ${childType} cannot be nested in a column`);
        return { valid: false, errors };
      }
      return { valid: true, errors: [] };
    }

    // Check if parent is a table cell
    const cellResult = BlockTree.findCell(template.blocks, parentId);
    if (cellResult) {
      // Cells can accept any block type
      const childDef = this.blockDefinitions[childType];
      if (
        childDef &&
        childDef.constraints.allowedParentTypes !== null &&
        !childDef.constraints.allowedParentTypes.includes('table')
      ) {
        errors.push(`Block type ${childType} cannot be nested in a table cell`);
        return { valid: false, errors };
      }
      return { valid: true, errors: [] };
    }

    errors.push(`Parent not found: ${parentId}`);
    return { valid: false, errors };
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

  // =========================================================================
  // COLUMNS & TABLE HELPERS
  // =========================================================================

  /**
   * Add a column to a ColumnsBlock
   */
  addColumn(columnsBlockId: string): void {
    const block = this.findBlock(columnsBlockId);
    if (!block || block.type !== 'columns') {
      this.callbacks.onError?.(new Error('Target is not a columns block'));
      return;
    }

    const columnsBlock = block as ColumnsBlock;
    if (columnsBlock.columns.length >= 6) {
      this.callbacks.onError?.(new Error('Maximum 6 columns allowed'));
      return;
    }

    this.saveToHistory();

    const newColumn = {
      id: generateId(),
      size: 1,
      children: [],
    };

    this.updateBlock(columnsBlockId, {
      columns: [...columnsBlock.columns, newColumn],
    } as Partial<ColumnsBlock>);
  }

  /**
   * Remove a column from a ColumnsBlock
   */
  removeColumn(columnsBlockId: string, columnId: string): void {
    const block = this.findBlock(columnsBlockId);
    if (!block || block.type !== 'columns') {
      this.callbacks.onError?.(new Error('Target is not a columns block'));
      return;
    }

    const columnsBlock = block as ColumnsBlock;
    if (columnsBlock.columns.length <= 1) {
      this.callbacks.onError?.(new Error('Cannot remove last column'));
      return;
    }

    this.saveToHistory();

    this.updateBlock(columnsBlockId, {
      columns: columnsBlock.columns.filter((col) => col.id !== columnId),
    } as Partial<ColumnsBlock>);
  }

  /**
   * Add a row to a TableBlock
   */
  addRow(tableBlockId: string, isHeader = false): void {
    const block = this.findBlock(tableBlockId);
    if (!block || block.type !== 'table') {
      this.callbacks.onError?.(new Error('Target is not a table block'));
      return;
    }

    const tableBlock = block as TableBlock;
    const cellCount = tableBlock.rows[0]?.cells.length ?? 3;

    this.saveToHistory();

    const newRow = {
      id: generateId(),
      cells: Array.from({ length: cellCount }, () => ({
        id: generateId(),
        children: [],
      })),
      isHeader,
    };

    this.updateBlock(tableBlockId, {
      rows: [...tableBlock.rows, newRow],
    } as Partial<TableBlock>);
  }

  /**
   * Remove a row from a TableBlock
   */
  removeRow(tableBlockId: string, rowId: string): void {
    const block = this.findBlock(tableBlockId);
    if (!block || block.type !== 'table') {
      this.callbacks.onError?.(new Error('Target is not a table block'));
      return;
    }

    const tableBlock = block as TableBlock;
    if (tableBlock.rows.length <= 1) {
      this.callbacks.onError?.(new Error('Cannot remove last row'));
      return;
    }

    this.saveToHistory();

    this.updateBlock(tableBlockId, {
      rows: tableBlock.rows.filter((row) => row.id !== rowId),
    } as Partial<TableBlock>);
  }

  // =========================================================================
  // SELECTION
  // =========================================================================

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

  // =========================================================================
  // BLOCK REGISTRY
  // =========================================================================

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

  // =========================================================================
  // VALIDATION
  // =========================================================================

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

        if (block.type === 'columns') {
          const columnsBlock = block as ColumnsBlock;
          for (const col of columnsBlock.columns) {
            validateBlocks(col.children);
          }
        }

        if (block.type === 'table') {
          const tableBlock = block as TableBlock;
          for (const row of tableBlock.rows) {
            for (const cell of row.cells) {
              validateBlocks(cell.children);
            }
          }
        }
      }
    };

    validateBlocks(template.blocks);
    return { valid: errors.length === 0, errors };
  }

  // =========================================================================
  // SERIALIZATION
  // =========================================================================

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

  // =========================================================================
  // UNDO / REDO
  // =========================================================================

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

  // =========================================================================
  // DRAG & DROP PORT
  // =========================================================================

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

      // Recurse into columns
      if (block.type === 'columns') {
        const columnsBlock = block as ColumnsBlock;
        for (const col of columnsBlock.columns) {
          for (const child of col.children) {
            checkBlock(child);
          }
        }
      }

      // Recurse into table cells
      if (block.type === 'table') {
        const tableBlock = block as TableBlock;
        for (const row of tableBlock.rows) {
          for (const cell of row.cells) {
            for (const child of cell.children) {
              checkBlock(child);
            }
          }
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

  // =========================================================================
  // HELPERS
  // =========================================================================

  private getChildCount(parentId: string | null): number {
    return BlockTree.getChildCount(this.store.getTemplate().blocks, parentId);
  }

  /**
   * Check if potentialDescendantId is a descendant of ancestorId
   */
  private isDescendant(ancestorId: string, potentialDescendantId: string): boolean {
    const ancestor = this.findBlock(ancestorId);
    if (!ancestor) return false;

    const checkInBlocks = (blocks: Block[]): boolean => {
      for (const block of blocks) {
        if (block.id === potentialDescendantId) return true;
        if ('children' in block && Array.isArray(block.children)) {
          if (checkInBlocks(block.children)) return true;
        }
        if (block.type === 'columns') {
          const columnsBlock = block as ColumnsBlock;
          for (const col of columnsBlock.columns) {
            if (checkInBlocks(col.children)) return true;
          }
        }
        if (block.type === 'table') {
          const tableBlock = block as TableBlock;
          for (const row of tableBlock.rows) {
            for (const cell of row.cells) {
              if (checkInBlocks(cell.children)) return true;
            }
          }
        }
      }
      return false;
    };

    // Check in ancestor's children/columns/cells
    if ('children' in ancestor && Array.isArray(ancestor.children)) {
      if (checkInBlocks(ancestor.children)) return true;
    }
    if (ancestor.type === 'columns') {
      const columnsBlock = ancestor as ColumnsBlock;
      for (const col of columnsBlock.columns) {
        if (checkInBlocks(col.children)) return true;
      }
    }
    if (ancestor.type === 'table') {
      const tableBlock = ancestor as TableBlock;
      for (const row of tableBlock.rows) {
        for (const cell of row.cells) {
          if (checkInBlocks(cell.children)) return true;
        }
      }
    }

    return false;
  }
}
