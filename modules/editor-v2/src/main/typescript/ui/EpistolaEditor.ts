import { LitElement, html } from 'lit'
import { customElement, state } from 'lit/decorators.js'
import type { TemplateDocument, NodeId } from '../types/index.js'
import { EditorEngine } from '../engine/EditorEngine.js'
import { createDefaultRegistry } from '../engine/registry.js'
import type { ComponentRegistry } from '../engine/registry.js'

import './EpistolaTree.js'
import './EpistolaCanvas.js'
import './EpistolaInspector.js'
import './EpistolaPalette.js'
import './EpistolaToolbar.js'

/**
 * <epistola-editor> — Root editor element.
 *
 * Shadow DOM is disabled (createRenderRoot returns this) so that
 * the global editor.css styles apply without scoping issues. This is
 * intentional — the editor is not a reusable web component library,
 * it's an application component embedded in a specific Thymeleaf page.
 */
@customElement('epistola-editor')
export class EpistolaEditor extends LitElement {
  /** Disable Shadow DOM — use light DOM for global CSS compatibility. */
  override createRenderRoot() {
    return this
  }

  private _engine?: EditorEngine
  private _unsubEngine?: () => void
  private _unsubSelection?: () => void
  private _onKeydown = this._handleKeydown.bind(this)

  @state() private _doc?: TemplateDocument
  @state() private _selectedNodeId: NodeId | null = null

  get engine(): EditorEngine | undefined {
    return this._engine
  }

  /**
   * Initialize the engine with a template document.
   */
  initEngine(
    doc: TemplateDocument,
    registry?: ComponentRegistry,
    options?: { dataModel?: object; dataExamples?: object[] },
  ): void {
    // Clean up previous engine
    this._unsubEngine?.()
    this._unsubSelection?.()

    const reg = registry ?? createDefaultRegistry()
    this._engine = new EditorEngine(doc, reg, {
      dataModel: options?.dataModel,
      dataExamples: options?.dataExamples,
    })
    this._doc = this._engine.doc

    this._unsubEngine = this._engine.events.on('doc:change', ({ doc }) => {
      this._doc = doc
    })

    this._unsubSelection = this._engine.events.on('selection:change', ({ nodeId }) => {
      this._selectedNodeId = nodeId
    })
  }

  override connectedCallback(): void {
    super.connectedCallback()
    this.addEventListener('keydown', this._onKeydown)
  }

  override disconnectedCallback(): void {
    this.removeEventListener('keydown', this._onKeydown)
    super.disconnectedCallback()
    this._unsubEngine?.()
    this._unsubSelection?.()
  }

  /**
   * Global keyboard handler for undo/redo. The engine delegates to the
   * active UndoHandler (e.g. ProseMirror) if one is registered, otherwise
   * falls through to the engine's own UndoStack.
   */
  private _handleKeydown(e: KeyboardEvent): void {
    if (!this._engine) return
    const mod = e.metaKey || e.ctrlKey
    if (!mod) return

    if (e.key === 'z' && !e.shiftKey) {
      e.preventDefault()
      this._engine.undo()
    } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
      e.preventDefault()
      this._engine.redo()
    }
  }

  override render() {
    if (!this._engine || !this._doc) {
      return html`<div class="editor-empty">No template loaded</div>`
    }

    return html`
      <div class="epistola-editor">
        <!-- Toolbar -->
        <epistola-toolbar
          .engine=${this._engine}
        ></epistola-toolbar>

        <!-- Main layout: palette | tree | canvas | inspector -->
        <div class="editor-main">
          <!-- Palette -->
          <epistola-palette
            .engine=${this._engine}
          ></epistola-palette>

          <!-- Tree -->
          <epistola-tree
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
          ></epistola-tree>

          <!-- Canvas -->
          <epistola-canvas
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
          ></epistola-canvas>

          <!-- Inspector -->
          <epistola-inspector
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
          ></epistola-inspector>
        </div>
      </div>
    `
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-editor': EpistolaEditor
  }
}
