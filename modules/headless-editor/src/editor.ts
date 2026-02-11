/**
 * TemplateEditor - Headless editor core
 *
 * Pure TypeScript, framework-agnostic.
 * Uses nanostores for reactive state.
 * UI is completely decoupled - bring your own components.
 */

import { computed } from 'nanostores';
import { createEditorStore, BlockTree, type EditorStore } from './store.js';
import { defaultBlockDefinitions } from './blocks/definitions.js';
import { UndoManager } from './undo.js';
import {
  resolveBlockStylesWithAncestors,
  resolveDocumentStyles,
} from './styles/cascade.js';
import {
  evaluateJsonata,
  evaluateJsonataBoolean,
  evaluateJsonataArray,
  evaluateJsonataString,
} from './evaluator/index.js';
import type { EvaluationResult, EvaluationContext, ScopeVariable } from './evaluator/index.js';
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
  ConditionalBlock,
  LoopBlock,
  TextBlock,
  TipTapContent,
  DataExample,
  JsonObject,
  JsonSchema,
  PreviewOverrides,
  PageSettings,
  DocumentStyles,
  CSSStyles,
  ThemeSummary,
} from './types.js';
import { DEFAULT_TEST_DATA, DEFAULT_PREVIEW_OVERRIDES } from './types.js';

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
  private _batching = false;
  private _batchSnapshotTaken = false;

  /**
   * Reactive computed store that indicates whether the current template
   * differs from the last saved state. Subscribers are notified only when
   * the dirty state transitions (not on every template mutation).
   */
  readonly $isDirty;

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

    // Derived store: dirty when template differs from last saved state
    this.$isDirty = computed(
      [this.store.$template, this.store.$lastSavedTemplate],
      (template, lastSaved) => {
        if (lastSaved === null) {
          // Never saved — dirty if there's any content
          return template.blocks.length > 0 || template.name !== 'Untitled';
        }
        return JSON.stringify(template) !== JSON.stringify(lastSaved);
      }
    );

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
    if (this._batching) {
      // During batch: take only one snapshot (the state before the first mutation)
      if (!this._batchSnapshotTaken) {
        this.undoManager.push(this.store.getTemplate());
        this._batchSnapshotTaken = true;
      }
      return;
    }
    this.undoManager.push(this.store.getTemplate());
  }

  /**
   * Group multiple mutations into a single undo entry and a single
   * `onTemplateChange` callback notification.
   *
   * @example
   * ```ts
   * editor.batch(() => {
   *   editor.addBlock('text');
   *   editor.addBlock('container');
   * });
   * // Single undo() reverts both additions
   * ```
   *
   * @param fn - Function containing mutations to batch
   */
  batch(fn: () => void): void {
    if (this._batching) {
      // Nested batch — just run inline
      fn();
      return;
    }

    this._batching = true;
    this._batchSnapshotTaken = false;

    // Suppress onTemplateChange during batch
    const originalCallback = this.callbacks.onTemplateChange;
    this.callbacks.onTemplateChange = undefined;

    try {
      fn();
    } finally {
      this._batching = false;
      this._batchSnapshotTaken = false;

      // Restore callback and fire once
      this.callbacks.onTemplateChange = originalCallback;
      this.callbacks.onTemplateChange?.(this.store.getTemplate());
    }
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
   * Get the nanostores directly (for framework integrations).
   * Includes reactive computed stores: `$isDirty`, `$canUndo`, `$canRedo`.
   */
  getStores() {
    return {
      $template: this.store.$template,
      $selectedBlockId: this.store.$selectedBlockId,
      $dataExamples: this.store.$dataExamples,
      $selectedDataExampleId: this.store.$selectedDataExampleId,
      $testData: this.store.$testData,
      $schema: this.store.$schema,
      $lastSavedTemplate: this.store.$lastSavedTemplate,
      $previewOverrides: this.store.$previewOverrides,
      $themes: this.store.$themes,
      $defaultTheme: this.store.$defaultTheme,
      $isDirty: this.$isDirty,
      $canUndo: this.undoManager.$canUndo,
      $canRedo: this.undoManager.$canRedo,
    };
  }

  /**
   * Mark current template as saved (snapshot for dirty tracking)
   */
  markAsSaved(): void {
    const currentTemplate = this.store.getTemplate();
    // Deep copy to prevent accidental mutation of saved state
    this.store.setLastSavedTemplate(JSON.parse(JSON.stringify(currentTemplate)) as Template);
  }

  /**
   * Check if template has unsaved changes
   * Returns true if never saved and has content, or if current differs from saved
   */
  isDirty(): boolean {
    const currentTemplate = this.store.getTemplate();
    const lastSaved = this.store.getLastSavedTemplate();

    // Never saved - check if there's any content
    if (lastSaved === null) {
      // Consider dirty if there are blocks (not empty/default)
      return currentTemplate.blocks.length > 0 || currentTemplate.name !== 'Untitled';
    }

    // Compare using JSON.stringify (simple deep equality)
    return JSON.stringify(currentTemplate) !== JSON.stringify(lastSaved);
  }

  /**
   * Set the JSON schema for data model
   */
  setSchema(schema: JsonSchema | null): void {
    this.store.setSchema(schema);
  }

  /**
   * Get the current JSON schema
   */
  getSchema(): JsonSchema | null {
    return this.store.getSchema();
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
  // PREVIEW OVERRIDES
  // =========================================================================

  /**
   * Set a single preview override for a conditional or loop block
   */
  setPreviewOverride(
    type: 'conditionals' | 'loops',
    blockId: string,
    value: 'data' | 'show' | 'hide' | number
  ): void {
    const current = this.store.getPreviewOverrides();
    const updated: PreviewOverrides = {
      conditionals: { ...current.conditionals },
      loops: { ...current.loops },
    };

    if (type === 'conditionals') {
      updated.conditionals[blockId] = value as 'data' | 'show' | 'hide';
    } else {
      updated.loops[blockId] = value as number | 'data';
    }

    this.store.setPreviewOverrides(updated);
  }

  /**
   * Get all preview overrides
   */
  getPreviewOverrides(): PreviewOverrides {
    return this.store.getPreviewOverrides();
  }

  /**
   * Clear all preview overrides (reset to empty state)
   */
  clearPreviewOverrides(): void {
    this.store.setPreviewOverrides({ ...DEFAULT_PREVIEW_OVERRIDES });
  }

  // =========================================================================
  // SCOPE VARIABLES & EXPRESSION CONTEXT
  // =========================================================================

  /**
   * Walk up the block tree from the given block and collect all enclosing
   * loop blocks' `itemAlias` and `indexAlias` as scope variables.
   *
   * Returns variables with the outermost loop's variables first.
   * Returns an empty array if the block is not inside any loop or not found.
   *
   * @param blockId - The block to start from
   * @returns Array of scope variables from enclosing loops
   */
  getScopeVariables(blockId: string): ScopeVariable[] {
    const template = this.store.getTemplate();
    const block = BlockTree.findBlock(template.blocks, blockId);
    if (!block) return [];

    const variables: ScopeVariable[] = [];
    let currentId: string | null = blockId;

    while (currentId !== null) {
      const parent = BlockTree.findParent(template.blocks, currentId, null);
      if (parent === null) break;

      if (parent.type === 'loop') {
        const loopBlock = parent as LoopBlock;
        // Prepend (outermost first)
        const loopVars: ScopeVariable[] = [
          { name: loopBlock.itemAlias, type: 'loop-item', arrayPath: loopBlock.expression.raw },
        ];
        if (loopBlock.indexAlias) {
          loopVars.push({ name: loopBlock.indexAlias, type: 'loop-index', arrayPath: loopBlock.expression.raw });
        }
        variables.unshift(...loopVars);
      }

      currentId = parent.id;
    }

    return variables;
  }

  /**
   * Merge the current test data with scope variables for a given block
   * into a single evaluation context object.
   *
   * Scope variables from enclosing loops are included as placeholder values
   * so that expressions referencing them don't fail during preview.
   *
   * @param blockId - The block to build context for
   * @returns Merged evaluation context
   */
  getExpressionContext(blockId: string): EvaluationContext {
    const testData = this.store.getTestData();
    const scopeVars = this.getScopeVariables(blockId);

    if (scopeVars.length === 0) {
      return { ...testData };
    }

    const context: EvaluationContext = { ...testData };
    for (const v of scopeVars) {
      if (v.type === 'loop-item') {
        // Placeholder — the actual value depends on which iteration is being rendered
        context[v.name] = `<${v.name}>`;
      } else if (v.type === 'loop-index') {
        context[v.name] = 0;
      }
    }

    return context;
  }

  // =========================================================================
  // EXPRESSION EVALUATION
  // =========================================================================

  /**
   * Evaluate an expression against current test data with optional scope variables
   * @param expression - Raw expression string (JSONata syntax)
   * @param scope - Additional scope variables (e.g., loop item/index)
   */
  async evaluateExpression(expression: string, scope?: EvaluationContext): Promise<EvaluationResult> {
    const testData = this.store.getTestData();
    const context: EvaluationContext = { ...testData, ...scope };
    return evaluateJsonata(expression, context);
  }

  /**
   * Evaluate a conditional block's condition
   * Returns true if condition passes, respecting inverse flag and preview overrides
   * @param blockId - ID of the conditional block
   * @param scope - Additional scope variables
   */
  async evaluateCondition(blockId: string, scope?: EvaluationContext): Promise<boolean> {
    const block = this.findBlock(blockId);
    if (!block || block.type !== 'conditional') return false;

    const conditionalBlock = block as ConditionalBlock;

    // Check for preview override
    const overrides = this.store.getPreviewOverrides();
    const override = overrides.conditionals[blockId];
    if (override === 'show') return true;
    if (override === 'hide') return false;

    // Evaluate the actual condition
    const testData = this.store.getTestData();
    const context: EvaluationContext = { ...testData, ...scope };
    let result = await evaluateJsonataBoolean(conditionalBlock.condition.raw, context);

    // Apply inverse if set
    if (conditionalBlock.inverse) {
      result = !result;
    }

    return result;
  }

  /**
   * Evaluate a loop block's expression to get the array
   * Returns the array or empty array if evaluation fails
   * @param blockId - ID of the loop block
   * @param scope - Additional scope variables
   */
  async evaluateLoopArray(blockId: string, scope?: EvaluationContext): Promise<unknown[]> {
    const block = this.findBlock(blockId);
    if (!block || block.type !== 'loop') return [];

    const loopBlock = block as LoopBlock;

    const testData = this.store.getTestData();
    const context: EvaluationContext = { ...testData, ...scope };
    return evaluateJsonataArray(loopBlock.expression.raw, context);
  }

  /**
   * Get the number of iterations for a loop in preview
   * Respects preview overrides
   * @param blockId - ID of the loop block
   * @param scope - Additional scope variables
   */
  async getLoopIterationCount(blockId: string, scope?: EvaluationContext): Promise<number> {
    // Check for preview override
    const overrides = this.store.getPreviewOverrides();
    const override = overrides.loops[blockId];
    if (typeof override === 'number') return override;

    // Use actual array length
    const array = await this.evaluateLoopArray(blockId, scope);
    return array.length;
  }

  /**
   * Build evaluation context for a loop iteration
   * @param blockId - ID of the loop block
   * @param index - Current iteration index
   * @param scope - Parent scope variables
   */
  async buildLoopIterationContext(
    blockId: string,
    index: number,
    scope?: EvaluationContext
  ): Promise<EvaluationContext> {
    const block = this.findBlock(blockId);
    if (!block || block.type !== 'loop') return { ...scope };

    const loopBlock = block as LoopBlock;
    const array = await this.evaluateLoopArray(blockId, scope);
    const item = array[index];

    const iterationScope: EvaluationContext = {
      ...scope,
      [loopBlock.itemAlias]: item,
    };

    if (loopBlock.indexAlias) {
      iterationScope[loopBlock.indexAlias] = index;
    }

    return iterationScope;
  }

  /**
   * Interpolate expressions in text content (TipTap JSONContent)
   * Replaces {{expression}} patterns with evaluated values
   * @param content - TipTap JSONContent
   * @param scope - Evaluation scope
   */
  async interpolateText(content: TipTapContent, scope?: EvaluationContext): Promise<string> {
    if (!content) return '';

    const testData = this.store.getTestData();
    const context: EvaluationContext = { ...testData, ...scope };

    // Extract text from TipTap content
    const rawText = this.extractTextFromTipTap(content);

    // Find all {{expression}} patterns and evaluate them
    const matches = rawText.matchAll(/\{\{([^}]+)\}\}/g);
    const replacements: Array<{ match: string; value: string }> = [];

    for (const match of matches) {
      const expr = match[1].trim();
      const value = await evaluateJsonataString(expr, context);
      replacements.push({ match: match[0], value });
    }

    // Apply replacements
    let result = rawText;
    for (const { match, value } of replacements) {
      result = result.replace(match, value);
    }

    return result;
  }

  /**
   * Extract plain text from TipTap JSONContent structure
   * Also handles expression nodes from the React editor
   */
  private extractTextFromTipTap(content: TipTapContent): string {
    if (!content || typeof content !== 'object') return '';

    const extractFromNode = (node: Record<string, unknown>): string => {
      if (!node) return '';

      // Handle expression nodes (from React editor's TipTap extension)
      if (node.type === 'expression' && node.attrs) {
        const attrs = node.attrs as Record<string, unknown>;
        if (attrs.expression) {
          return `{{${attrs.expression}}}`;
        }
      }

      // Handle regular text nodes
      if (node.type === 'text' && typeof node.text === 'string') {
        return node.text;
      }

      // Recurse into content array
      if (Array.isArray(node.content)) {
        return node.content.map((child) => extractFromNode(child as Record<string, unknown>)).join('');
      }

      return '';
    };

    return extractFromNode(content as Record<string, unknown>);
  }

  // =========================================================================
  // THEMES
  // =========================================================================

  /**
   * Set available themes
   */
  setThemes(themes: ThemeSummary[]): void {
    this.store.setThemes(themes);
  }

  /**
   * Set the default theme (from parent template)
   */
  setDefaultTheme(theme: ThemeSummary | null): void {
    this.store.setDefaultTheme(theme);
  }

  /**
   * Get available themes
   */
  getThemes(): ThemeSummary[] {
    return this.store.getThemes();
  }

  /**
   * Get the default theme
   */
  getDefaultTheme(): ThemeSummary | null {
    return this.store.getDefaultTheme();
  }

  /**
   * Update the template's selected theme ID with undo support
   */
  updateThemeId(themeId: string | null): void {
    this.saveToHistory();

    const current = this.store.getTemplate();
    this.store.setTemplate({
      ...current,
      themeId,
    });
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

  /**
   * Update page settings (format, orientation, margins)
   * Merges partial settings into existing template.pageSettings
   */
  updatePageSettings(settings: Partial<PageSettings>): void {
    this.saveToHistory();

    const current = this.store.getTemplate();
    const currentPageSettings = current.pageSettings ?? {
      format: 'A4',
      orientation: 'portrait',
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    };

    // Merge margins if provided
    const mergedMargins = settings.margins
      ? { ...currentPageSettings.margins, ...settings.margins }
      : currentPageSettings.margins;

    const updatedPageSettings: PageSettings = {
      ...currentPageSettings,
      ...settings,
      margins: mergedMargins,
    };

    this.store.setTemplate({
      ...current,
      pageSettings: updatedPageSettings,
    });
  }

  /**
   * Update document styles (fontFamily, fontSize, color, etc.)
   * Merges partial styles into existing template.documentStyles
   */
  updateDocumentStyles(styles: Partial<DocumentStyles>): void {
    this.saveToHistory();

    const current = this.store.getTemplate();
    const currentDocumentStyles = current.documentStyles ?? {};

    const updatedDocumentStyles: DocumentStyles = {
      ...currentDocumentStyles,
      ...styles,
    };

    this.store.setTemplate({
      ...current,
      documentStyles: updatedDocumentStyles,
    });
  }

  /**
   * Get resolved document styles for rendering.
   */
  getResolvedDocumentStyles(): CSSStyles {
    const current = this.store.getTemplate();
    return resolveDocumentStyles(current.documentStyles);
  }

  /**
   * Get resolved styles for a block by ID.
   * Applies hierarchical cascade: document → ancestors → block.
   */
  getResolvedBlockStyles(blockId: string): CSSStyles {
    const current = this.store.getTemplate();
    const block = BlockTree.findBlock(current.blocks, blockId);
    if (!block) return {};

    // Build ancestor chain from root to parent
    const ancestors: Block[] = [];
    let currentParent = BlockTree.findParent(current.blocks, blockId);
    while (currentParent) {
      ancestors.unshift(currentParent); // Add to front (root first)
      currentParent = BlockTree.findParent(current.blocks, currentParent.id);
    }

    const ancestorStyles = ancestors
      .map((ancestor) => ancestor.styles)
      .filter((styles): styles is CSSStyles => Boolean(styles));

    return resolveBlockStylesWithAncestors(
      current.documentStyles,
      ancestorStyles,
      block.styles,
    );
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
    this.saveToHistory();

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
   * Validates that the JSON has required Template structure
   */
  importJSON(json: string): void {
    try {
      const parsed: unknown = JSON.parse(json);

      // Basic structure validation
      if (typeof parsed !== 'object' || parsed === null) {
        throw new Error('Template must be an object');
      }

      const obj = parsed as Record<string, unknown>;

      if (typeof obj.id !== 'string' || obj.id.length === 0) {
        throw new Error('Template must have a non-empty string id');
      }

      if (typeof obj.name !== 'string') {
        throw new Error('Template must have a string name');
      }

      if (!Array.isArray(obj.blocks)) {
        throw new Error('Template must have a blocks array');
      }

      const template = parsed as Template;
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
   * @param draggedId - The block being dragged
   * @param targetId - The target block (or null for root)
   * @param index - The insertion index
   * @param position - The drop position for validation (defaults to 'inside')
   */
  drop(draggedId: string, targetId: string | null, index: number, position: DropPosition = 'inside'): void {
    if (!this.canDrop(draggedId, targetId, position)) {
      this.callbacks.onError?.(new Error('Invalid drop target'));
      return;
    }

    if (position === 'inside') {
      this.moveBlock(draggedId, targetId, index);
    } else {
      const target = this.findBlock(targetId!);
      if (!target) return;

      const targetParent = BlockTree.findParent(this.store.getTemplate().blocks, targetId!, null);
      const actualIndex = position === 'after'
        ? BlockTree.getChildIndex(this.store.getTemplate().blocks, targetId!, null) + 1
        : BlockTree.getChildIndex(this.store.getTemplate().blocks, targetId!, null);

      this.moveBlock(draggedId, targetParent?.id ?? null, actualIndex);
    }
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
