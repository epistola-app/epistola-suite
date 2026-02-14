/**
 * TemplateEditor - Headless editor core
 *
 * Pure TypeScript, framework-agnostic.
 * Uses nanostores for reactive state.
 * UI is completely decoupled - bring your own components.
 */

import { defaultBlockDefinitions } from "./blocks/definitions.js";
import type {
  EvaluationContext,
  EvaluationResult,
  ScopeVariable,
} from "./evaluator/index.js";
import type {
  Block,
  BlockCatalogItem,
  BlockDefinition,
  BlockType,
  CSSStyles,
  DataExample,
  DocumentStyles,
  DragDropPort,
  DropPosition,
  DropZone,
  EditorCallbacks,
  EditorConfig,
  EditorState,
  JsonObject,
  JsonSchema,
  PageSettings,
  PreviewOverrides,
  Template,
  ThemeSummary,
  TipTapContent,
  ValidationResult,
} from "./types.js";
import { UndoManager } from "./undo.js";
import { BlockOperationsService } from "./services/block-operations-service.js";
import { BlockRegistry } from "./services/block-registry.js";
import { DragDropService } from "./services/drag-drop-service.js";
import { EditorStateRepository } from "./services/editor-state-repository.js";
import { EditorStateService } from "./services/editor-state-service.js";
import { ExpressionService } from "./services/expression-service.js";
import { TemplateIoService } from "./services/template-io-service.js";
import { TemplateStyleService } from "./services/template-style-service.js";

function generateId(): string {
  return `block-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
}

export class TemplateEditor {
  private stateRepository: EditorStateRepository;
  private blockRegistry: BlockRegistry;
  private blockOperations: BlockOperationsService;
  private dragDropService: DragDropService;
  private expressionService: ExpressionService;
  private templateStyleService: TemplateStyleService;
  private templateIoService: TemplateIoService;
  private editorStateService: EditorStateService;
  private callbacks: EditorCallbacks;
  private undoManager: UndoManager;
  private _batching = false;
  private _batchSnapshotTaken = false;

  readonly $isDirty;

  constructor(config: EditorConfig = {}) {
    const initialTemplate: Template = config.template ?? {
      id: generateId(),
      name: "Untitled",
      blocks: [],
    };

    this.stateRepository = new EditorStateRepository(initialTemplate);
    this.blockRegistry = new BlockRegistry(defaultBlockDefinitions);
    this.callbacks = config.callbacks ?? {};
    this.undoManager = new UndoManager();

    this.editorStateService = new EditorStateService(this.stateRepository);
    this.blockOperations = new BlockOperationsService(
      this.stateRepository,
      this.blockRegistry,
      this.saveToHistory.bind(this),
      () => this.callbacks,
      generateId,
    );
    this.dragDropService = new DragDropService(
      this.stateRepository,
      this.blockRegistry,
      this.blockOperations,
      (error) => this.callbacks.onError?.(error),
    );
    this.expressionService = new ExpressionService(this.stateRepository);
    this.templateStyleService = new TemplateStyleService(
      this.stateRepository,
      this.saveToHistory.bind(this),
    );
    this.templateIoService = new TemplateIoService(
      this.stateRepository,
      this.blockOperations.validateTemplate.bind(this.blockOperations),
      () => this.callbacks,
    );

    this.$isDirty = this.stateRepository.$isDirty;

    this.stateRepository.subscribeTemplate((template) => {
      this.callbacks.onTemplateChange?.(template);
    });

    this.stateRepository.subscribeSelectedBlockId((id) => {
      this.callbacks.onBlockSelect?.(id);
    });
  }

  private saveToHistory(): void {
    if (this._batching) {
      if (!this._batchSnapshotTaken) {
        this.undoManager.push(this.stateRepository.getTemplate());
        this._batchSnapshotTaken = true;
      }
      return;
    }
    this.undoManager.push(this.stateRepository.getTemplate());
  }

  batch(fn: () => void): void {
    if (this._batching) {
      fn();
      return;
    }

    this._batching = true;
    this._batchSnapshotTaken = false;

    const originalCallback = this.callbacks.onTemplateChange;
    this.callbacks.onTemplateChange = undefined;

    try {
      fn();
    } finally {
      this._batching = false;
      this._batchSnapshotTaken = false;
      this.callbacks.onTemplateChange = originalCallback;
      this.callbacks.onTemplateChange?.(this.stateRepository.getTemplate());
    }
  }

  getState(): EditorState {
    return this.stateRepository.getStateSnapshot();
  }

  subscribe(callback: (state: EditorState) => void): () => void {
    return this.stateRepository.subscribeState(callback);
  }

  getStores() {
    const stores = this.stateRepository.getStoreRefs();
    return {
      ...stores,
      $isDirty: this.$isDirty,
      $canUndo: this.undoManager.$canUndo,
      $canRedo: this.undoManager.$canRedo,
    };
  }

  markAsSaved(): void {
    this.stateRepository.markAsSaved();
  }

  isDirty(): boolean {
    return this.stateRepository.isDirty();
  }

  setSchema(schema: JsonSchema | null): void {
    this.editorStateService.setSchema(schema);
  }

  getSchema(): JsonSchema | null {
    return this.editorStateService.getSchema();
  }

  setDataExamples(examples: DataExample[]): void {
    this.editorStateService.setDataExamples(examples);
  }

  addDataExample(example: DataExample): void {
    this.editorStateService.addDataExample(example);
  }

  updateDataExample(id: string, updates: Partial<DataExample>): void {
    this.editorStateService.updateDataExample(id, updates);
  }

  deleteDataExample(id: string): void {
    this.editorStateService.deleteDataExample(id);
  }

  selectDataExample(id: string | null): void {
    this.editorStateService.selectDataExample(id);
  }

  getDataExamples(): DataExample[] {
    return this.editorStateService.getDataExamples();
  }

  getSelectedDataExampleId(): string | null {
    return this.editorStateService.getSelectedDataExampleId();
  }

  getTestData(): JsonObject {
    return this.editorStateService.getTestData();
  }

  setPreviewOverride(
    type: "conditionals" | "loops",
    blockId: string,
    value: "data" | "show" | "hide" | number,
  ): void {
    this.editorStateService.setPreviewOverride(type, blockId, value);
  }

  getPreviewOverrides(): PreviewOverrides {
    return this.editorStateService.getPreviewOverrides();
  }

  clearPreviewOverrides(): void {
    this.editorStateService.clearPreviewOverrides();
  }

  getScopeVariables(blockId: string): ScopeVariable[] {
    return this.expressionService.getScopeVariables(blockId);
  }

  getExpressionContext(blockId: string): EvaluationContext {
    return this.expressionService.getExpressionContext(blockId);
  }

  async evaluateExpression(
    expression: string,
    scope?: EvaluationContext,
  ): Promise<EvaluationResult> {
    return this.expressionService.evaluateExpression(expression, scope);
  }

  async evaluateCondition(
    blockId: string,
    scope?: EvaluationContext,
  ): Promise<boolean> {
    return this.expressionService.evaluateCondition(blockId, scope);
  }

  async evaluateLoopArray(
    blockId: string,
    scope?: EvaluationContext,
  ): Promise<unknown[]> {
    return this.expressionService.evaluateLoopArray(blockId, scope);
  }

  async getLoopIterationCount(
    blockId: string,
    scope?: EvaluationContext,
  ): Promise<number> {
    return this.expressionService.getLoopIterationCount(blockId, scope);
  }

  async buildLoopIterationContext(
    blockId: string,
    index: number,
    scope?: EvaluationContext,
  ): Promise<EvaluationContext> {
    return this.expressionService.buildLoopIterationContext(blockId, index, scope);
  }

  async interpolateText(
    content: TipTapContent,
    scope?: EvaluationContext,
  ): Promise<string> {
    return this.expressionService.interpolateText(content, scope);
  }

  setThemes(themes: ThemeSummary[]): void {
    this.templateStyleService.setThemes(themes);
  }

  setDefaultTheme(theme: ThemeSummary | null): void {
    this.templateStyleService.setDefaultTheme(theme);
  }

  getThemes(): ThemeSummary[] {
    return this.templateStyleService.getThemes();
  }

  getDefaultTheme(): ThemeSummary | null {
    return this.templateStyleService.getDefaultTheme();
  }

  updateThemeId(themeId: string | null): void {
    this.templateStyleService.updateThemeId(themeId);
  }

  getTemplate(): Template {
    return this.templateIoService.getTemplate();
  }

  setTemplate(template: Template): void {
    this.templateIoService.setTemplate(template);
  }

  updateTemplate(updates: Partial<Omit<Template, "blocks">>): void {
    this.templateIoService.updateTemplate(updates);
  }

  updatePageSettings(settings: Partial<PageSettings>): void {
    this.templateStyleService.updatePageSettings(settings);
  }

  updateDocumentStyles(styles: Partial<DocumentStyles>): void {
    this.templateStyleService.updateDocumentStyles(styles);
  }

  getResolvedDocumentStyles(): CSSStyles {
    return this.templateStyleService.getResolvedDocumentStyles();
  }

  getResolvedBlockStyles(blockId: string): CSSStyles {
    return this.templateStyleService.getResolvedBlockStyles(blockId);
  }

  addBlock(
    type: BlockType,
    parentId: string | null = null,
    index: number = -1,
  ): Block | null {
    return this.blockOperations.addBlock(type, parentId, index);
  }

  updateBlock(id: string, updates: Partial<Block>): void {
    this.blockOperations.updateBlock(id, updates);
  }

  deleteBlock(id: string): boolean {
    return this.blockOperations.deleteBlock(id);
  }

  moveBlock(id: string, newParentId: string | null, newIndex: number): void {
    this.blockOperations.moveBlock(id, newParentId, newIndex);
  }

  findBlock(id: string): Block | null {
    return this.blockOperations.findBlock(id);
  }

  addColumn(columnsBlockId: string): void {
    this.blockOperations.addColumn(columnsBlockId);
  }

  removeColumn(columnsBlockId: string, columnId: string): void {
    this.blockOperations.removeColumn(columnsBlockId, columnId);
  }

  addRow(tableBlockId: string, isHeader = false): void {
    this.blockOperations.addRow(tableBlockId, isHeader);
  }

  removeRow(tableBlockId: string, rowId: string): void {
    this.blockOperations.removeRow(tableBlockId, rowId);
  }

  selectBlock(id: string | null): void {
    this.editorStateService.selectBlock(id);
  }

  getSelectedBlock(): Block | null {
    const id = this.editorStateService.getSelectedBlockId();
    return id ? this.blockOperations.findBlock(id) : null;
  }

  getDropContainerIds(): string[] {
    const template = this.stateRepository.getTemplate();
    return this.blockRegistry.getDropContainerIds(template.blocks);
  }

  getBlockDefinition(type: BlockType): BlockDefinition | undefined {
    return this.blockRegistry.getDefinition(type);
  }

  getBlockTypes(): BlockType[] {
    return this.blockRegistry.getTypes();
  }

  getBlockCatalog(): BlockCatalogItem[] {
    return this.blockRegistry.getCatalog();
  }

  validateTemplate(): ValidationResult {
    return this.templateIoService.validateCurrentTemplate();
  }

  exportJSON(): string {
    return this.templateIoService.exportJSON();
  }

  importJSON(json: string): void {
    this.templateIoService.importJSON(json);
  }

  undo(): boolean {
    const previous = this.undoManager.undo(this.stateRepository.getTemplate());
    if (previous) {
      this.stateRepository.setTemplate(previous);
      return true;
    }
    return false;
  }

  redo(): boolean {
    const next = this.undoManager.redo(this.stateRepository.getTemplate());
    if (next) {
      this.stateRepository.setTemplate(next);
      return true;
    }
    return false;
  }

  canUndo(): boolean {
    return this.undoManager.canUndo();
  }

  canRedo(): boolean {
    return this.undoManager.canRedo();
  }

  canDrag(blockId: string): boolean {
    return this.dragDropService.canDrag(blockId);
  }

  canDrop(
    draggedId: string,
    targetId: string | null,
    position: DropPosition,
  ): boolean {
    return this.dragDropService.canDrop(draggedId, targetId, position);
  }

  getDropZones(draggedId: string): DropZone[] {
    return this.dragDropService.getDropZones(draggedId);
  }

  drop(
    draggedId: string,
    targetId: string | null,
    index: number,
    position: DropPosition = "inside",
  ): void {
    this.dragDropService.drop(draggedId, targetId, index, position);
  }

  getDragDropPort(): DragDropPort {
    return this.dragDropService.getPort();
  }
}
