/**
 * EpistolaTextEditor — Lit component wrapping ProseMirror for rich text editing.
 *
 * Light DOM (no Shadow DOM) so global CSS applies to ProseMirror content.
 *
 * Undo integration:
 * - On first doc-changing PM transaction, pushes a TextChange onto the
 *   engine's undo stack with ops that delegate to PM's native history.
 * - The engine calls ops.undo()/redo() to walk PM's history one step at a time,
 *   using undoDepth() as the session boundary.
 * - Character-level undo works even after blur (PM's history persists).
 *
 * Content sync:
 * - Internal changes (typing) → debounced dispatch UpdateNodeProps to engine
 *   with skipUndo (PM handles character-level undo natively via TextChange).
 * - External changes (engine undo snapshot, inspector edit) → replace PM state.
 * - JSON equality comparison prevents unnecessary PM state replacement.
 *
 * PM state preservation (Phase 2):
 * - On disconnect, caches PM EditorState in the engine so that if the block
 *   is restored via undo, character-level undo history is preserved.
 * - On connect, checks for a cached state and restores it if available.
 * - Revives ops on TextChange entries so they delegate to PM again.
 */

import { LitElement, html } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import { styleMap } from 'lit/directives/style-map.js'
import { EditorState } from 'prosemirror-state'
import { EditorView } from 'prosemirror-view'
import { undo, redo, undoDepth } from 'prosemirror-history'
import { Node as ProsemirrorNode } from 'prosemirror-model'
import { epistolaSchema } from '../prosemirror/schema.js'
import { createPlugins } from '../prosemirror/plugins.js'
import { ExpressionNodeView } from '../prosemirror/ExpressionNodeView.js'
import { extractFieldPaths } from '../engine/schema-paths.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import { TextChange } from '../engine/text-change.js'
import type { TextChangeOps } from '../engine/undo.js'
import type { NodeId } from '../types/index.js'

const DEBOUNCE_MS = 300

@customElement('epistola-text-editor')
export class EpistolaTextEditor extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) nodeId?: NodeId
  @property({ attribute: false }) content: unknown = null
  @property({ attribute: false }) resolvedStyles: Record<string, string> = {}
  @property({ attribute: false }) engine?: EditorEngine
  @property({ type: Boolean }) isSelected = false

  private _pmView: EditorView | null = null
  private _pmContainer: HTMLDivElement | null = null
  private _lastContentJson: string = ''
  private _debounceTimer: ReturnType<typeof setTimeout> | null = null
  private _unsubExample: (() => void) | null = null

  /** The TextChangeOps for this PM view. Used to identify our session on the undo stack. */
  private _ops: TextChangeOps | null = null
  /** True while the engine is calling undo/redo through our ops. Prevents re-entrant dispatch. */
  private _isEngineOp = false
  /** True while PM has unsent edits (debounce pending). Prevents external sync from overwriting PM. */
  private _hasPendingEdits = false

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  override firstUpdated(): void {
    this._pmContainer = this.querySelector('.prosemirror-container')
    if (!this._pmContainer) return
    this._createProseMirror()

    // Refresh expression chips when the data example changes
    if (this.engine) {
      this._unsubExample = this.engine.events.on('example:change', () => {
        ExpressionNodeView.refreshAll()
      })
    }
  }

  override updated(changed: Map<string, unknown>): void {
    // Sync external content changes (e.g., engine undo snapshot, inspector edit).
    // Skip while PM has unsent edits — user's typing takes precedence.
    if (changed.has('content') && this._pmView && !this._hasPendingEdits) {
      this._syncFromExternal()
    }

    // Blur ProseMirror when deselected
    if (changed.has('isSelected') && !this.isSelected && this._pmView) {
      const pmDom = this._pmView.dom
      if (pmDom.contains(document.activeElement)) {
        ;(document.activeElement as HTMLElement)?.blur()
      }
    }
  }

  override disconnectedCallback(): void {
    // Flush any pending debounce before disconnecting
    this._cancelDebounce()
    if (this._hasPendingEdits && this._pmView) {
      this._dispatchContentToEngine(this._pmView.state.doc)
    }

    // Unsubscribe from example changes
    this._unsubExample?.()
    this._unsubExample = null

    // Cache PM EditorState for history preservation across delete/undo cycles
    if (this._pmView && this.engine && this.nodeId) {
      this.engine.cachePmState(this.nodeId, this._pmView.state)
    }

    // Clear local ops ref. TextChange entries still hold the old ops object,
    // but isAlive() will return false after _destroyProseMirror nullifies _pmView.
    // reviveTextChangeOps reconnects them when a new editor mounts for this nodeId.
    this._ops = null
    this._destroyProseMirror()
    super.disconnectedCallback()
  }

  // ---------------------------------------------------------------------------
  // ProseMirror setup
  // ---------------------------------------------------------------------------

  private _createProseMirror(): void {
    if (!this._pmContainer) return

    const fieldPaths = this.engine?.dataModel
      ? extractFieldPaths(this.engine.dataModel)
      : []

    const engine = this.engine
    const getExampleData = engine
      ? () => {
          const example = engine.currentExample as Record<string, unknown> | undefined
          if (!example) return undefined
          // Backend DataExample format: { id: string, name: string, data: {...} }
          // Dev/flat format: data properties at top level (no id/data wrapper)
          if (typeof example.id === 'string' && typeof example.data === 'object' && example.data !== null) {
            return example.data as Record<string, unknown>
          }
          return example
        }
      : undefined

    const plugins = createPlugins(epistolaSchema, {
      expressionNodeViewOptions: { fieldPaths, getExampleData },
    })

    // Check for cached PM state (preserved across delete/undo cycles)
    const cached = this.nodeId ? this.engine?.getCachedPmState(this.nodeId) : undefined
    let editorState: EditorState

    if (cached) {
      // Verify the cached state's doc matches the current engine content
      const cachedState = cached as EditorState
      const cachedJson = JSON.stringify(cachedState.doc.toJSON())
      const engineJson = JSON.stringify(this.content)
      if (cachedJson === engineJson) {
        // Content matches — restore PM state with history intact
        editorState = cachedState
      } else {
        // Content diverged — create fresh state
        const doc = this._parseContent(this.content)
        editorState = EditorState.create({ doc, plugins })
      }
    } else {
      const doc = this._parseContent(this.content)
      editorState = EditorState.create({ doc, plugins })
    }

    this._pmView = new EditorView(this._pmContainer, {
      state: editorState,
      nodeViews: {
        expression: (node, view, getPos) =>
          new ExpressionNodeView(node, view, getPos, { fieldPaths, getExampleData }),
      },
      dispatchTransaction: (tr) => {
        if (!this._pmView) return
        const newState = this._pmView.state.apply(tr)
        this._pmView.updateState(newState)

        // Only schedule content sync for user-initiated changes.
        // Engine-initiated changes (ops.undo/redo) are synced by the engine itself.
        if (tr.docChanged && !this._isEngineOp) {
          this._onPmDocChanged(newState.doc)
        }
      },
      handleDOMEvents: {
        focus: () => {
          // Ensure the block is selected in the engine when ProseMirror gets focus
          if (this.nodeId && this.engine?.selectedNodeId !== this.nodeId) {
            this.engine?.selectNode(this.nodeId)
          }
          return false
        },
        // Block PM's built-in beforeinput handler for historyUndo/historyRedo.
        // The engine's keydown handler owns undo/redo routing — without this,
        // the browser fires beforeinput(historyUndo) AND our keydown handler
        // routes through engine.undo(), causing a double undo per keystroke.
        beforeinput: (_view: EditorView, e: Event) => {
          const inputType = (e as InputEvent).inputType
          if (inputType === 'historyUndo' || inputType === 'historyRedo') {
            e.preventDefault()
            return true
          }
          return false
        },
      },
    })

    this._lastContentJson = JSON.stringify(this._pmView.state.doc.toJSON())
    this._ops = this._createOps()

    // Revive TextChange entries that reference this nodeId
    if (this.nodeId && this.engine) {
      this.engine.reviveTextChangeOps(this.nodeId, this._ops)
    }
  }

  /** Create TextChangeOps that close over this component's PM view. */
  private _createOps(): TextChangeOps {
    return {
      isAlive: () => this._pmView !== null && this._pmView.dom.isConnected,
      undoDepth: () => (this._pmView ? undoDepth(this._pmView.state) : 0),
      undo: () => {
        if (!this._pmView) return false
        this._isEngineOp = true
        try {
          return undo(this._pmView.state, this._pmView.dispatch)
        } finally {
          this._isEngineOp = false
        }
      },
      redo: () => {
        if (!this._pmView) return false
        this._isEngineOp = true
        try {
          return redo(this._pmView.state, this._pmView.dispatch)
        } finally {
          this._isEngineOp = false
        }
      },
      getContent: () => (this._pmView ? this._pmView.state.doc.toJSON() : null),
    }
  }

  private _destroyProseMirror(): void {
    this._pmView?.destroy()
    this._pmView = null
  }

  // ---------------------------------------------------------------------------
  // TextChange integration
  // ---------------------------------------------------------------------------

  /**
   * Check if the current editing session already has a TextChange entry on
   * top of the undo stack. Compares by ops identity (same PM view instance).
   */
  private _isCurrentSessionOnStack(): boolean {
    const top = this.engine?.peekUndo()
    if (!top || !(top instanceof TextChange)) return false
    return top.ops === this._ops
  }

  /**
   * Called on every PM doc-changing transaction (user typing).
   * Pushes a TextChange on first change of a new session, then schedules
   * a debounced content sync to the engine.
   */
  private _onPmDocChanged(pmDoc: ProsemirrorNode): void {
    if (!this.engine || !this.nodeId || !this._pmView || !this._ops) return

    // Push TextChange entry on first change of a new editing session
    if (!this._isCurrentSessionOnStack()) {
      this.engine.pushTextChange(new TextChange({
        nodeId: this.nodeId,
        ops: this._ops,
        contentBefore: this.content, // current engine content = "before" snapshot
        undoDepthAtStart: undoDepth(this._pmView.state) - 1, // depth was just incremented by this transaction
      }))
    }

    this._scheduleContentDispatch(pmDoc)
  }

  // ---------------------------------------------------------------------------
  // Content sync — debounced dispatch
  // ---------------------------------------------------------------------------

  /**
   * Schedule a debounced content dispatch to the engine.
   * Purely a sync mechanism — no undo logic.
   */
  private _scheduleContentDispatch(pmDoc: ProsemirrorNode): void {
    this._hasPendingEdits = true
    this._cancelDebounce()
    this._debounceTimer = setTimeout(() => {
      this._debounceTimer = null
      this._dispatchContentToEngine(pmDoc)
    }, DEBOUNCE_MS)
  }

  /**
   * Dispatch PM content to engine with skipUndo.
   * The TextChange entry on the undo stack owns undo for this content.
   */
  private _dispatchContentToEngine(pmDoc: ProsemirrorNode): void {
    if (!this.engine || !this.nodeId) return
    const json = pmDoc.toJSON()
    const jsonStr = JSON.stringify(json)
    if (jsonStr === this._lastContentJson) return

    this._lastContentJson = jsonStr
    this._hasPendingEdits = false
    this.engine.dispatch(
      {
        type: 'UpdateNodeProps',
        nodeId: this.nodeId,
        props: { content: json },
      },
      { skipUndo: true },
    )
  }

  private _cancelDebounce(): void {
    if (this._debounceTimer !== null) {
      clearTimeout(this._debounceTimer)
      this._debounceTimer = null
    }
  }

  /**
   * Sync from external changes (engine undo snapshot, inspector edit).
   * Compares PM's current state against the incoming engine content.
   */
  private _syncFromExternal(): void {
    if (!this._pmView) return

    const engineJson = JSON.stringify(this.content)
    const pmJson = JSON.stringify(this._pmView.state.doc.toJSON())
    if (pmJson === engineJson) {
      // Already in sync — update tracking to match
      this._lastContentJson = engineJson
      return
    }

    // Cancel pending debounce — external change takes precedence
    this._cancelDebounce()
    this._hasPendingEdits = false

    const doc = this._parseContent(this.content)
    const newState = EditorState.create({
      doc,
      plugins: this._pmView.state.plugins,
    })
    this._pmView.updateState(newState)
    this._lastContentJson = engineJson
  }

  private _parseContent(content: unknown): ProsemirrorNode {
    if (content && typeof content === 'object' && 'type' in content) {
      try {
        return ProsemirrorNode.fromJSON(epistolaSchema, content as Record<string, unknown>)
      } catch {
        // Fall through to default
      }
    }
    // Default empty doc
    return epistolaSchema.node('doc', null, [epistolaSchema.node('paragraph')])
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  override render() {
    return html`
      <div
        class="prosemirror-container"
        style=${styleMap(this.resolvedStyles)}
      ></div>
    `
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-text-editor': EpistolaTextEditor
  }
}
