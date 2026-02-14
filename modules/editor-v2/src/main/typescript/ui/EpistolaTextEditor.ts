/**
 * EpistolaTextEditor — Lit component wrapping ProseMirror for rich text editing.
 *
 * Light DOM (no Shadow DOM) so global CSS applies to ProseMirror content.
 *
 * Content sync:
 * - Internal changes (typing) → dispatch UpdateNodeProps to engine
 * - External changes (undo) → replace ProseMirror state
 * - isSyncing flag + JSON equality check prevents loops
 */

import { LitElement, html } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import { styleMap } from 'lit/directives/style-map.js'
import { EditorState } from 'prosemirror-state'
import { EditorView } from 'prosemirror-view'
import { Node as ProsemirrorNode } from 'prosemirror-model'
import { epistolaSchema } from '../prosemirror/schema.js'
import { createPlugins } from '../prosemirror/plugins.js'
import { ExpressionNodeView } from '../prosemirror/ExpressionNodeView.js'
import { extractFieldPaths } from '../engine/schema-paths.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { NodeId } from '../types/index.js'

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
  private _isSyncing = false
  private _lastContentJson: string = ''

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
          this._dispatchContentToEngine(newState.doc)
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
      },
    })

    this._lastContentJson = JSON.stringify(this._pmView.state.doc.toJSON())
  }

  private _destroyProseMirror(): void {
    this._pmView?.destroy()
    this._pmView = null
  }

  // ---------------------------------------------------------------------------
  // Content sync
  // ---------------------------------------------------------------------------

  private _dispatchContentToEngine(doc: ProsemirrorNode): void {
    if (!this.engine || !this.nodeId) return

    const json = doc.toJSON()
    const jsonStr = JSON.stringify(json)

    if (jsonStr === this._lastContentJson) return
    this._lastContentJson = jsonStr

    this._isSyncing = true
    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.nodeId,
      props: { content: json },
    })
    this._isSyncing = false
  }

  private _syncFromExternal(): void {
    if (!this._pmView) return

    const newJsonStr = JSON.stringify(this.content)
    if (newJsonStr === this._lastContentJson) return

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
