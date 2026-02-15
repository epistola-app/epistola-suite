import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { TemplateDocument, NodeId } from '../types/index.js'
import { EditorEngine } from '../engine/EditorEngine.js'
import { createDefaultRegistry } from '../engine/registry.js'
import type { ComponentRegistry } from '../engine/registry.js'
import type { FetchPreviewFn } from './preview-service.js'
import { EpistolaResizeHandle } from './EpistolaResizeHandle.js'

import './EpistolaSidebar.js'
import './EpistolaCanvas.js'
import './EpistolaToolbar.js'
import './EpistolaPreview.js'
import './EpistolaResizeHandle.js'

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

  @property({ attribute: false }) fetchPreview?: FetchPreviewFn
  @state() private _doc?: TemplateDocument
  @state() private _selectedNodeId: NodeId | null = null
  @state() private _previewOpen = false

  private static readonly PREVIEW_OPEN_KEY = 'ep:preview-open'

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
    this.addEventListener('toggle-preview', this._handleTogglePreview)

    // Restore preview open state from localStorage
    try {
      this._previewOpen = localStorage.getItem(EpistolaEditor.PREVIEW_OPEN_KEY) === 'true'
    } catch {
      // localStorage may be unavailable
    }
  }

  override disconnectedCallback(): void {
    this.removeEventListener('keydown', this._onKeydown)
    this.removeEventListener('toggle-preview', this._handleTogglePreview)
    super.disconnectedCallback()
    this._unsubEngine?.()
    this._unsubSelection?.()
  }

  /**
   * Global keyboard handler for undo/redo. The engine processes entries
   * from its undo stack — TextChange entries delegate to ProseMirror's
   * native history, Command entries are applied directly.
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

  private _handleTogglePreview = () => {
    this._previewOpen = !this._previewOpen
    try {
      localStorage.setItem(EpistolaEditor.PREVIEW_OPEN_KEY, String(this._previewOpen))
    } catch {
      // localStorage may be unavailable
    }
  }

  override render() {
    if (!this._engine || !this._doc) {
      return html`<div class="editor-empty">No template loaded</div>`
    }

    const hasPreview = !!this.fetchPreview
    const showPreview = hasPreview && this._previewOpen
    const previewWidth = showPreview ? EpistolaResizeHandle.getPersistedWidth() : undefined

    return html`
      <div class="epistola-editor">
        <!-- Toolbar -->
        <epistola-toolbar
          .engine=${this._engine}
          .previewOpen=${this._previewOpen}
          .hasPreview=${hasPreview}
        ></epistola-toolbar>

        <!-- Main layout: sidebar | canvas | [resize-handle | preview] -->
        <div
          class="editor-main"
          style=${showPreview ? `--ep-preview-width: ${previewWidth}px` : ''}
        >
          <epistola-sidebar
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
          ></epistola-sidebar>

          <epistola-canvas
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
          ></epistola-canvas>

          ${showPreview
            ? html`
                <epistola-resize-handle></epistola-resize-handle>
                <epistola-preview
                  .engine=${this._engine}
                  .fetchPreview=${this.fetchPreview}
                ></epistola-preview>
              `
            : nothing}
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
