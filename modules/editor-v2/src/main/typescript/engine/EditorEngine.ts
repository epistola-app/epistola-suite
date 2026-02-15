/**
 * EditorEngine — the headless core of the editor.
 *
 * Owns the document state, derived indexes, command dispatch,
 * undo/redo, and change notification. Framework-agnostic.
 */

import type { TemplateDocument, NodeId, SlotId, PageSettings } from '../types/index.js'
import type { Theme } from '@epistola/template-model/generated/theme.js'
import type { StyleRegistry } from '@epistola/template-model/generated/style-registry.js'
import { type DocumentIndexes, buildIndexes } from './indexes.js'
import { type Command, type CommandResult, applyCommand } from './commands.js'
import { UndoStack, type TextChangeEntry, isTextChange } from './undo.js'
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
  // Command dispatch
  // -----------------------------------------------------------------------

  /**
   * Dispatch a command, updating the document state.
   * Returns the result including any inverse command for undo.
   *
   * @param options.skipUndo — if true, do not push to undo stack (used by
   *   external components that manage their own undo, e.g. ProseMirror).
   */
  dispatch(command: Command, options?: { skipUndo?: boolean }): CommandResult {
    const result = applyCommand(this._doc, this._indexes, command, this.registry)

    if (result.ok) {
      this._doc = deepFreeze(result.doc)
      if (result.structureChanged) {
        this._indexes = buildIndexes(this._doc)
      }
      this._recomputeStyles()

      if (!options?.skipUndo && result.inverse) {
        this._undoStack.push(result.inverse)
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
  pushTextChange(entry: TextChangeEntry): void {
    this._undoStack.push(entry)
  }

  /** Peek at the top of the undo stack (used by text editor to check current session). */
  peekUndo(): import('./undo.js').UndoEntry | undefined {
    return this._undoStack.peekUndo()
  }

  /**
   * Dispatch a command without recording it in the undo stack.
   * Used internally by undo/redo.
   */
  private _dispatchSilent(command: Command): CommandResult {
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
    const entry = this._undoStack.peekUndo()
    if (!entry) return false

    if (isTextChange(entry)) {
      return this._undoTextChange(entry)
    }

    // Command entry — pop and apply inverse
    this._undoStack.popUndo()
    const result = this._dispatchSilent(entry)
    if (!result.ok) return false

    if (result.inverse) {
      this._undoStack.pushRedo(result.inverse)
    }
    return true
  }

  redo(): boolean {
    const entry = this._undoStack.peekRedo()
    if (!entry) return false

    if (isTextChange(entry)) {
      return this._redoTextChange(entry)
    }

    // Command entry — pop and apply inverse
    this._undoStack.popRedo()
    const result = this._dispatchSilent(entry)
    if (!result.ok) return false

    if (result.inverse) {
      this._undoStack.pushUndo(result.inverse)
    }
    return true
  }

  private _undoTextChange(entry: TextChangeEntry): boolean {
    const ops = entry.ops

    if (ops && ops.isAlive()) {
      // Lazily capture end-of-session state on first undo
      if (entry.undoDepthAtEnd === undefined) {
        entry.undoDepthAtEnd = ops.undoDepth()
        entry.contentAfter = ops.getContent()
      }

      if (ops.undoDepth() > entry.undoDepthAtStart) {
        ops.undo()
        this._syncTextContent(entry.nodeId, ops.getContent())

        // If session is now exhausted, move entry to redo stack immediately
        if (ops.undoDepth() <= entry.undoDepthAtStart) {
          this._undoStack.popUndo()
          this._undoStack.pushRedo(entry)
        }
        return true
      }

      // Session already exhausted (entry still on undo stack from prior state)
    } else {
      // PM destroyed — apply contentBefore via snapshot restore
      this._undoStack.popUndo()
      this._applyContentSnapshot(entry.nodeId, entry.contentBefore)
      this._undoStack.pushRedo(entry)
      return true
    }

    // Fall through: session exhausted without performing an undo step
    this._undoStack.popUndo()
    this._undoStack.pushRedo(entry)
    return this.undo()
  }

  private _redoTextChange(entry: TextChangeEntry): boolean {
    const ops = entry.ops

    if (ops && ops.isAlive()) {
      if (entry.undoDepthAtEnd !== undefined && ops.undoDepth() < entry.undoDepthAtEnd) {
        ops.redo()
        this._syncTextContent(entry.nodeId, ops.getContent())

        // If session is fully redone, move entry to undo stack immediately
        if (ops.undoDepth() >= entry.undoDepthAtEnd) {
          this._moveRedoToUndo(entry)
        }
        return true
      }

      // Session already fully redone
      this._moveRedoToUndo(entry)
      return this.redo()
    }

    // PM destroyed — apply contentAfter via snapshot restore
    this._undoStack.popRedo()
    if (entry.contentAfter !== undefined) {
      this._applyContentSnapshot(entry.nodeId, entry.contentAfter)
    }
    this._moveRedoToUndo(entry)
    return true
  }

  /**
   * Move a TextChange entry from redo to undo stack, resetting the
   * end-of-session markers so they'll be re-captured if the user
   * continues typing in the same session.
   */
  private _moveRedoToUndo(entry: TextChangeEntry): void {
    this._undoStack.popRedo()
    entry.undoDepthAtEnd = undefined
    entry.contentAfter = undefined
    this._undoStack.pushUndo(entry)
  }

  /** Sync text content from PM to engine without creating undo entries. */
  private _syncTextContent(nodeId: NodeId, content: unknown): void {
    this.dispatch(
      { type: 'UpdateNodeProps', nodeId, props: { content } },
      { skipUndo: true },
    )
  }

  /** Apply a content snapshot (fallback when PM is destroyed). */
  private _applyContentSnapshot(nodeId: NodeId, content: unknown): void {
    this.dispatch(
      { type: 'UpdateNodeProps', nodeId, props: { content: content != null ? structuredClone(content) : null } },
      { skipUndo: true },
    )
  }

  get canUndo(): boolean {
    return this._undoStack.canUndo
  }

  get canRedo(): boolean {
    return this._undoStack.canRedo
  }

  // -----------------------------------------------------------------------
  // Replace document (e.g., loading a new template)
  // -----------------------------------------------------------------------

  replaceDocument(doc: TemplateDocument): void {
    this._doc = deepFreeze(structuredClone(doc))
    this._indexes = buildIndexes(this._doc)
    this._recomputeStyles()
    this._undoStack.clear()
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
