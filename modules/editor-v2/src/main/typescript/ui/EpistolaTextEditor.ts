/**
 * EpistolaTextEditor — Lit component wrapping ProseMirror for rich text editing.
 *
 * Light DOM (no Shadow DOM) so global CSS applies to ProseMirror content.
 *
 * Content sync:
 * - Internal changes (typing) → debounced dispatch UpdateNodeProps to engine
 *   with skipUndo (ProseMirror handles character-level undo natively)
 * - On blur / deselect → flush pending content and push a single coalesced
 *   undo entry so engine-level undo reverts the entire editing session
 * - External changes (undo) → replace ProseMirror state
 * - isSyncing flag + JSON equality check prevents loops
 */

import { LitElement, html } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import { styleMap } from 'lit/directives/style-map.js'
import { EditorState } from 'prosemirror-state'
import { EditorView } from 'prosemirror-view'
import { undo, redo } from 'prosemirror-history'
import { Node as ProsemirrorNode } from 'prosemirror-model'
import { epistolaSchema } from '../prosemirror/schema.js'
import { createPlugins } from '../prosemirror/plugins.js'
import { ExpressionNodeView } from '../prosemirror/ExpressionNodeView.js'
import { extractFieldPaths } from '../engine/schema-paths.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { UndoHandler } from '../engine/EditorEngine.js'
import type { NodeId } from '../types/index.js'

const DEBOUNCE_MS = 300

@customElement('epistola-text-editor')
export class EpistolaTextEditor extends LitElement implements UndoHandler {
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
  private _isSyncing = false
  private _lastContentJson: string = ''

  // Debounce + coalesced undo state
  private _debounceTimer: ReturnType<typeof setTimeout> | null = null
  private _contentBeforeEditing: unknown = null
  private _hasPendingFlush = false

  // ---------------------------------------------------------------------------
  // UndoHandler — called by engine.undo()/redo() when this editor has focus
  // ---------------------------------------------------------------------------

  tryUndo(): boolean {
    if (!this._pmView) return false
    return undo(this._pmView.state, this._pmView.dispatch)
  }

  tryRedo(): boolean {
    if (!this._pmView) return false
    return redo(this._pmView.state, this._pmView.dispatch)
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  override firstUpdated(): void {
    this._pmContainer = this.querySelector('.prosemirror-container')
    if (!this._pmContainer) return
    this._createProseMirror()
  }

  override updated(changed: Map<string, unknown>): void {
    // Sync external content changes (e.g., engine undo)
    if (changed.has('content') && this._pmView && !this._isSyncing) {
      this._syncFromExternal()
    }

    // Blur ProseMirror when deselected
    if (changed.has('isSelected') && !this.isSelected && this._pmView) {
      // Remove focus from ProseMirror but don't destroy it
      const pmDom = this._pmView.dom
      if (pmDom.contains(document.activeElement)) {
        ;(document.activeElement as HTMLElement)?.blur()
      }
    }
  }

  override disconnectedCallback(): void {
    this._flushContent()
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

    const plugins = createPlugins(epistolaSchema, {
      expressionNodeViewOptions: { fieldPaths },
    })

    const doc = this._parseContent(this.content)
    const editorState = EditorState.create({
      doc,
      plugins,
    })

    this._pmView = new EditorView(this._pmContainer, {
      state: editorState,
      nodeViews: {
        expression: (node, view, getPos) =>
          new ExpressionNodeView(node, view, getPos, { fieldPaths }),
      },
      dispatchTransaction: (tr) => {
        if (!this._pmView) return
        const newState = this._pmView.state.apply(tr)
        this._pmView.updateState(newState)

        if (tr.docChanged && !this._isSyncing) {
          this._scheduleContentDispatch(newState.doc)
        }
      },
      handleDOMEvents: {
        focus: () => {
          // Ensure the block is selected in the engine when ProseMirror gets focus
          if (this.nodeId && this.engine?.selectedNodeId !== this.nodeId) {
            this.engine?.selectNode(this.nodeId)
          }
          // Register as the active undo handler so engine.undo() delegates to PM
          this.engine?.setActiveUndoHandler(this)
          return false
        },
        blur: () => {
          // Unregister so engine.undo() falls through to its own stack
          this.engine?.setActiveUndoHandler(null)
          this._flushContent()
          return false
        },
        // Block the history plugin's beforeinput handler for undo/redo.
        // The engine owns undo/redo via the strategy pattern — without this,
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
  }

  private _destroyProseMirror(): void {
    this._pmView?.destroy()
    this._pmView = null
  }

  // ---------------------------------------------------------------------------
  // Content sync — debounced dispatch
  // ---------------------------------------------------------------------------

  /**
   * Schedule a debounced content dispatch. On first keystroke of a session,
   * captures the content-before-editing for coalesced undo.
   */
  private _scheduleContentDispatch(pmDoc: ProsemirrorNode): void {
    const json = pmDoc.toJSON()
    const jsonStr = JSON.stringify(json)
    if (jsonStr === this._lastContentJson) return

    // Capture snapshot on first change of editing session
    if (!this._hasPendingFlush) {
      this._contentBeforeEditing = this.content
      this._hasPendingFlush = true
    }

    // Reset debounce timer
    if (this._debounceTimer !== null) {
      clearTimeout(this._debounceTimer)
    }

    this._debounceTimer = setTimeout(() => {
      this._debounceTimer = null
      this._dispatchContentSilent(json, jsonStr)
    }, DEBOUNCE_MS)
  }

  /**
   * Dispatch content to engine with skipUndo (ProseMirror owns character-level undo).
   */
  private _dispatchContentSilent(json: unknown, jsonStr: string): void {
    if (!this.engine || !this.nodeId) return
    if (jsonStr === this._lastContentJson) return

    this._lastContentJson = jsonStr
    this._isSyncing = true
    this.engine.dispatch(
      {
        type: 'UpdateNodeProps',
        nodeId: this.nodeId,
        props: { content: json },
      },
      { skipUndo: true },
    )
    this._isSyncing = false
  }

  /**
   * Flush any pending debounced content and push a single coalesced undo entry.
   * Called on blur and disconnectedCallback.
   */
  private _flushContent(): void {
    if (!this._hasPendingFlush) return

    // Cancel pending debounce
    if (this._debounceTimer !== null) {
      clearTimeout(this._debounceTimer)
      this._debounceTimer = null
    }

    // Dispatch current PM content to engine (if changed)
    if (this._pmView && this.engine && this.nodeId) {
      const json = this._pmView.state.doc.toJSON()
      const jsonStr = JSON.stringify(json)
      this._dispatchContentSilent(json, jsonStr)

      // Only push a coalesced undo entry if the content actually changed
      // (e.g. user PM-undid everything back to original → no useless entry)
      const beforeJson = JSON.stringify(this._contentBeforeEditing)
      if (beforeJson !== jsonStr) {
        const snapshotBefore = this._contentBeforeEditing
        const nodeId = this.nodeId
        this.engine.pushUndoEntry({
          type: 'UpdateNodeProps',
          nodeId,
          props: { content: snapshotBefore != null ? structuredClone(snapshotBefore) : null },
        })
      }
    }

    this._contentBeforeEditing = null
    this._hasPendingFlush = false
  }

  /**
   * Sync from external changes (engine undo, inspector edit).
   * Cancels any pending debounce and resets editing state.
   */
  private _syncFromExternal(): void {
    if (!this._pmView) return

    const newJsonStr = JSON.stringify(this.content)
    if (newJsonStr === this._lastContentJson) return

    // Cancel pending debounce — external change takes precedence
    if (this._debounceTimer !== null) {
      clearTimeout(this._debounceTimer)
      this._debounceTimer = null
    }
    this._contentBeforeEditing = null
    this._hasPendingFlush = false

    this._isSyncing = true
    try {
      const doc = this._parseContent(this.content)
      const newState = EditorState.create({
        doc,
        plugins: this._pmView.state.plugins,
      })
      this._pmView.updateState(newState)
      this._lastContentJson = newJsonStr
    } finally {
      this._isSyncing = false
    }
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
