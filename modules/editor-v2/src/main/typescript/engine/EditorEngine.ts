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
import { UndoStack } from './undo.js'
import type { ComponentRegistry } from './registry.js'
import { deepFreeze } from './freeze.js'
import { defaultStyleRegistry } from './style-registry.js'
import {
  getInheritableKeys,
  resolveDocumentStyles,
  resolveNodeStyles,
  resolvePageSettings,
  resolvePresetStyles,
} from './styles.js'

// ---------------------------------------------------------------------------
// Listener type
// ---------------------------------------------------------------------------

export type EngineListener = (doc: TemplateDocument, indexes: DocumentIndexes) => void

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

export class EditorEngine {
  private _doc: TemplateDocument
  private _indexes: DocumentIndexes
  private _listeners: Set<EngineListener> = new Set()
  private _undoStack: UndoStack
  private _selectedNodeId: NodeId | null = null
  private _selectionListeners: Set<(nodeId: NodeId | null) => void> = new Set()
  readonly registry: ComponentRegistry
  readonly styleRegistry: StyleRegistry
  private _theme: Theme | undefined
  private _resolvedDocStyles!: Record<string, unknown>
  private _inheritableKeys: Set<string>
  private _resolvedPageSettings!: PageSettings

  private _dataModel: object | undefined
  private _dataExamples: object[] | undefined

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
    for (const listener of this._selectionListeners) {
      listener(nodeId)
    }
  }

  onSelectionChange(listener: (nodeId: NodeId | null) => void): () => void {
    this._selectionListeners.add(listener)
    return () => { this._selectionListeners.delete(listener) }
  }

  // -----------------------------------------------------------------------
  // Command dispatch
  // -----------------------------------------------------------------------

  /**
   * Dispatch a command, updating the document state.
   * Returns the result including any inverse command for undo.
   */
  dispatch(command: Command): CommandResult {
    const result = applyCommand(this._doc, this._indexes, command, this.registry)

    if (result.ok) {
      this._doc = deepFreeze(result.doc)
      this._indexes = buildIndexes(this._doc)
      this._recomputeStyles()

      if (result.inverse) {
        this._undoStack.push(result.inverse)
      }

      this._notify()
    }

    return result
  }

  /**
   * Dispatch a command without recording it in the undo stack.
   * Used internally by undo/redo.
   */
  private _dispatchSilent(command: Command): CommandResult {
    const result = applyCommand(this._doc, this._indexes, command, this.registry)

    if (result.ok) {
      this._doc = deepFreeze(result.doc)
      this._indexes = buildIndexes(this._doc)
      this._recomputeStyles()
      this._notify()
    }

    return result
  }

  // -----------------------------------------------------------------------
  // Undo / redo
  // -----------------------------------------------------------------------

  undo(): boolean {
    const command = this._undoStack.undo()
    if (!command) return false

    const result = this._dispatchSilent(command)
    if (!result.ok) {
      // If the inverse command fails, we can't undo — discard it
      return false
    }

    // Push the inverse of the inverse (i.e., the redo command)
    if (result.inverse) {
      this._undoStack.pushRedo(result.inverse)
    }

    return true
  }

  redo(): boolean {
    const command = this._undoStack.redo()
    if (!command) return false

    const result = this._dispatchSilent(command)
    if (!result.ok) {
      return false
    }

    if (result.inverse) {
      this._undoStack.pushUndo(result.inverse)
    }

    return true
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

  subscribe(listener: EngineListener): () => void {
    this._listeners.add(listener)
    return () => { this._listeners.delete(listener) }
  }

  private _notify(): void {
    for (const listener of this._listeners) {
      listener(this._doc, this._indexes)
    }
  }
}
