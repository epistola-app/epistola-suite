/**
 * EditorEngine — the headless core of the editor.
 *
 * Owns the document state, derived indexes, command dispatch,
 * undo/redo, and change notification. Framework-agnostic.
 */

import type { TemplateDocument, NodeId, SlotId, PageSettings } from '../types/index.js'
import { type FieldPath, extractFieldPaths } from './schema-paths.js'
import type { Theme } from '@epistola/template-model/generated/theme.js'
import type { StyleRegistry } from '@epistola/template-model/generated/style-registry.js'
import { type DocumentIndexes, buildIndexes } from './indexes.js'
import { type AnyCommand, type CommandResult, applyCommand } from './commands.js'
import { UndoStack } from './undo.js'
import type { Change, ChangeContext } from './change.js'
import { CommandChange } from './command-change.js'
import { TextChange } from './text-change.js'
import type { TextChangeOps } from './undo.js'
import type { ComponentRegistry } from './registry.js'
import { deepFreeze } from './freeze.js'
import { defaultStyleRegistry } from './style-registry.js'
import { EventEmitter, type EngineEvents } from './events.js'
import {
  getInheritableKeys,
  resolveDocumentStyles,
  resolveNodeStyles,
  resolvePageSettings,
  resolvePresetStyles,
} from './styles.js'

// ---------------------------------------------------------------------------
// Listener type (deprecated — use events.on('doc:change') instead)
// ---------------------------------------------------------------------------

export type EngineListener = (doc: TemplateDocument, indexes: DocumentIndexes) => void

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

export class EditorEngine {
  private _doc: TemplateDocument
  private _indexes: DocumentIndexes
  private _events = new EventEmitter<EngineEvents>()
  private _undoStack: UndoStack
  private _selectedNodeId: NodeId | null = null
  readonly registry: ComponentRegistry
  readonly styleRegistry: StyleRegistry
  private _theme: Theme | undefined
  private _resolvedDocStyles!: Record<string, unknown>
  private _inheritableKeys: Set<string>
  private _resolvedPageSettings!: PageSettings

  private _dataModel: object | undefined
  private _dataExamples: object[] | undefined
  private _currentExampleIndex: number = 0
  private _fieldPathsCache: FieldPath[] | undefined

  /** Generic component state store (e.g. table cell selection). */
  private _componentState = new Map<string, unknown>()

  /** PM EditorState cache for preserving history across delete/undo cycles. */
  private _pmStateCache = new Map<NodeId, unknown>()

  /** ChangeContext instance shared with all Change implementations. */
  private _changeCtx: ChangeContext

  constructor(
    doc: TemplateDocument,
    registry: ComponentRegistry,
    options?: { theme?: Theme; styleRegistry?: StyleRegistry; undoDepth?: number; dataModel?: object; dataExamples?: object[] },
  ) {
    this.registry = registry
    this.styleRegistry = options?.styleRegistry ?? defaultStyleRegistry
    this._theme = options?.theme
    this._doc = deepFreeze(structuredClone(doc))
    this._indexes = buildIndexes(this._doc)
    this._undoStack = new UndoStack(options?.undoDepth ?? 100)
    this._inheritableKeys = getInheritableKeys(this.styleRegistry)
    this._dataModel = options?.dataModel
    this._dataExamples = options?.dataExamples
    this._recomputeStyles()

    // Build the ChangeContext that Change implementations use
    this._changeCtx = {
      stack: this._undoStack,
      applySilent: (command: AnyCommand) => this._dispatchSilent(command),
      syncContent: (nodeId: NodeId, content: unknown) => {
        this.dispatch(
          { type: 'UpdateNodeProps', nodeId, props: { content } },
          { skipUndo: true },
        )
      },
      applySnapshot: (nodeId: NodeId, content: unknown) => {
        this.dispatch(
          { type: 'UpdateNodeProps', nodeId, props: { content: content != null ? structuredClone(content) : null } },
          { skipUndo: true },
        )
      },
      undo: () => this.undo(),
      redo: () => this.redo(),
    }
  }

  // -----------------------------------------------------------------------
  // Events
  // -----------------------------------------------------------------------

  get events(): EventEmitter<EngineEvents> {
    return this._events
  }

  // -----------------------------------------------------------------------
  // Read-only accessors
  // -----------------------------------------------------------------------

  get doc(): TemplateDocument {
    return this._doc
  }

  get indexes(): DocumentIndexes {
    return this._indexes
  }

  get selectedNodeId(): NodeId | null {
    return this._selectedNodeId
  }

  getNode(id: NodeId) {
    return this._doc.nodes[id]
  }

  getSlot(id: SlotId) {
    return this._doc.slots[id]
  }

  get theme(): Theme | undefined {
    return this._theme
  }

  get dataModel(): object | undefined {
    return this._dataModel
  }

  get dataExamples(): object[] | undefined {
    return this._dataExamples
  }

  get currentExampleIndex(): number {
    return this._currentExampleIndex
  }

  /** The currently selected data example, or undefined if none. */
  get currentExample(): object | undefined {
    return this._dataExamples?.[this._currentExampleIndex]
  }

  /** Extracted field paths from the data model, cached lazily. */
  get fieldPaths(): FieldPath[] {
    if (!this._fieldPathsCache) {
      this._fieldPathsCache = this._dataModel
        ? extractFieldPaths(this._dataModel)
        : []
    }
    return this._fieldPathsCache
  }

  /**
   * Get the current example's data, unwrapping the backend DataExample wrapper
   * format `{ id, name, data: {...} }` if present.
   */
  getExampleData(): Record<string, unknown> | undefined {
    const example = this.currentExample as Record<string, unknown> | undefined
    if (!example) return undefined
    // Backend DataExample format: { id: string, name: string, data: {...} }
    if (typeof example.id === 'string' && typeof example.data === 'object' && example.data !== null) {
      return example.data as Record<string, unknown>
    }
    return example
  }

  /** Switch the active data example by index. Notifies example listeners. */
  setCurrentExample(index: number): void {
    if (index === this._currentExampleIndex) return
    if (!this._dataExamples || index < 0 || index >= this._dataExamples.length) return
    this._currentExampleIndex = index
    const example = this._dataExamples[index]
    this._events.emit('example:change', { index, example })
  }

  /**
   * Subscribe to data example changes. Returns unsubscribe function.
   * @deprecated Use `engine.events.on('example:change', ...)` instead.
   */
  onExampleChange(listener: (index: number, example: object | undefined) => void): () => void {
    return this._events.on('example:change', ({ index, example }) => listener(index, example))
  }

  get resolvedDocStyles(): Record<string, unknown> {
    return this._resolvedDocStyles
  }

  get resolvedPageSettings(): PageSettings {
    return this._resolvedPageSettings
  }

  /** Resolve a single node's final styles through the full cascade. */
  getResolvedNodeStyles(nodeId: NodeId): Record<string, unknown> {
    const node = this._doc.nodes[nodeId]
    if (!node) return {}

    const presetStyles = resolvePresetStyles(
      this._theme?.blockStylePresets,
      node.stylePreset,
    )
    return resolveNodeStyles(
      this._resolvedDocStyles,
      this._inheritableKeys,
      presetStyles,
      node.styles,
    )
  }

  /** Update the theme (e.g. when loading a different theme). */
  setTheme(theme: Theme | undefined): void {
    this._theme = theme
    this._recomputeStyles()
    this._notify()
  }

  private _recomputeStyles(): void {
    this._resolvedDocStyles = resolveDocumentStyles(
      this._theme?.documentStyles as Record<string, unknown> | undefined,
      this._doc.documentStylesOverride as Record<string, unknown> | undefined,
    )
    this._resolvedPageSettings = resolvePageSettings(
      this._theme?.pageSettings as PageSettings | undefined,
      this._doc.pageSettingsOverride as PageSettings | undefined,
    )
  }

  // -----------------------------------------------------------------------
  // Selection
  // -----------------------------------------------------------------------

  selectNode(nodeId: NodeId | null): void {
    if (this._selectedNodeId === nodeId) return
    this._selectedNodeId = nodeId
    this._events.emit('selection:change', { nodeId })
  }

  /**
   * Subscribe to selection changes. Returns unsubscribe function.
   * @deprecated Use `engine.events.on('selection:change', ...)` instead.
   */
  onSelectionChange(listener: (nodeId: NodeId | null) => void): () => void {
    return this._events.on('selection:change', ({ nodeId }) => listener(nodeId))
  }

  // -----------------------------------------------------------------------
  // Component state (generic key-value store for component-specific state)
  // -----------------------------------------------------------------------

  /** Set component state and emit a change event. */
  setComponentState(key: string, value: unknown): void {
    this._componentState.set(key, value)
    this._events.emit('component-state:change', { key, value })
  }

  /** Get component state by key. */
  getComponentState<T>(key: string): T | undefined {
    return this._componentState.get(key) as T | undefined
  }

  // -----------------------------------------------------------------------
  // Command dispatch
  // -----------------------------------------------------------------------

  /**
   * Dispatch a command, updating the document state.
   * Returns the result including any inverse command for undo.
   *
   * @param options.skipUndo — if true, do not push to undo stack (used by
   *   external components that manage their own undo, e.g. ProseMirror).
   */
  dispatch(command: AnyCommand, options?: { skipUndo?: boolean }): CommandResult {
    const result = applyCommand(this._doc, this._indexes, command, this.registry)

    if (result.ok) {
      this._doc = deepFreeze(result.doc)
      if (result.structureChanged) {
        this._indexes = buildIndexes(this._doc)
      }
      this._recomputeStyles()

      if (!options?.skipUndo && result.inverse) {
        this._undoStack.push(new CommandChange(result.inverse))
      }

      this._notify()
    }

    return result
  }

  /**
   * Push a TextChange entry onto the undo stack. Called by the text editor
   * on the first doc-changing PM transaction of a new editing session.
   * Clears the redo stack (new action invalidates redo history).
   */
  pushTextChange(entry: TextChange): void {
    this._undoStack.push(entry)
  }

  /** Peek at the top of the undo stack (used by text editor to check current session). */
  peekUndo(): Change | undefined {
    return this._undoStack.peekUndo()
  }

  /**
   * Dispatch a command without recording it in the undo stack.
   * Used internally by undo/redo via ChangeContext.
   */
  private _dispatchSilent(command: AnyCommand): CommandResult {
    const result = applyCommand(this._doc, this._indexes, command, this.registry)

    if (result.ok) {
      this._doc = deepFreeze(result.doc)
      if (result.structureChanged) {
        this._indexes = buildIndexes(this._doc)
      }
      this._recomputeStyles()
      this._notify()
    }

    return result
  }

  // -----------------------------------------------------------------------
  // Undo / redo
  // -----------------------------------------------------------------------

  undo(): boolean {
    const change = this._undoStack.peekUndo()
    if (!change) return false
    return change.undoStep(this._changeCtx)
  }

  redo(): boolean {
    const change = this._undoStack.peekRedo()
    if (!change) return false
    return change.redoStep(this._changeCtx)
  }

  get canUndo(): boolean {
    return this._undoStack.canUndo
  }

  get canRedo(): boolean {
    return this._undoStack.canRedo
  }

  // -----------------------------------------------------------------------
  // PM state cache (Phase 2: preserve history across delete/undo)
  // -----------------------------------------------------------------------

  /** Cache a PM EditorState when a text block is disconnected. */
  cachePmState(nodeId: NodeId, state: unknown): void {
    this._pmStateCache.set(nodeId, state)
  }

  /** Retrieve and consume a cached PM EditorState (one-time use). */
  getCachedPmState(nodeId: NodeId): unknown | undefined {
    const state = this._pmStateCache.get(nodeId)
    if (state) this._pmStateCache.delete(nodeId)
    return state
  }

  /**
   * Revive TextChange entries that reference a given nodeId by reconnecting
   * their ops. Called when a text editor reconnects after a delete/undo cycle.
   */
  reviveTextChangeOps(nodeId: NodeId, ops: TextChangeOps): void {
    for (const entry of this._undoStack.undoEntries()) {
      if (entry instanceof TextChange && entry.nodeId === nodeId && (!entry.ops || !entry.ops.isAlive())) {
        entry.ops = ops
      }
    }
    for (const entry of this._undoStack.redoEntries()) {
      if (entry instanceof TextChange && entry.nodeId === nodeId && (!entry.ops || !entry.ops.isAlive())) {
        entry.ops = ops
      }
    }
  }

  // -----------------------------------------------------------------------
  // Replace document (e.g., loading a new template)
  // -----------------------------------------------------------------------

  replaceDocument(doc: TemplateDocument): void {
    this._doc = deepFreeze(structuredClone(doc))
    this._indexes = buildIndexes(this._doc)
    this._recomputeStyles()
    this._undoStack.clear()
    this._pmStateCache.clear()
    this._componentState.clear()
    this._selectedNodeId = null
    this._notify()
  }

  // -----------------------------------------------------------------------
  // Subscription
  // -----------------------------------------------------------------------

  /**
   * Subscribe to document changes. Returns unsubscribe function.
   * @deprecated Use `engine.events.on('doc:change', ...)` instead.
   */
  subscribe(listener: EngineListener): () => void {
    return this._events.on('doc:change', ({ doc, indexes }) => listener(doc, indexes))
  }

  private _notify(): void {
    this._events.emit('doc:change', { doc: this._doc, indexes: this._indexes })
  }
}
