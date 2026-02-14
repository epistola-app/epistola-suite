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
 * Tailwind CSS classes work without scoping issues. This is intentional —
 * the editor is not a reusable web component library, it's an application
 * component embedded in a specific Thymeleaf page.
 */
@customElement('epistola-editor')
export class EpistolaEditor extends LitElement {
  /** Disable Shadow DOM — use light DOM for Tailwind compatibility. */
  override createRenderRoot() {
    return this
  }

  private _engine?: EditorEngine
  private _unsubEngine?: () => void
  private _unsubSelection?: () => void

  @state() private _doc?: TemplateDocument
  @state() private _selectedNodeId: NodeId | null = null

  get engine(): EditorEngine | undefined {
    return this._engine
  }

  /**
   * Initialize the engine with a template document.
   */
  initEngine(doc: TemplateDocument, registry?: ComponentRegistry): void {
    // Clean up previous engine
    this._unsubEngine?.()
    this._unsubSelection?.()

    const reg = registry ?? createDefaultRegistry()
    this._engine = new EditorEngine(doc, reg)
    this._doc = this._engine.doc

    this._unsubEngine = this._engine.subscribe((newDoc) => {
      this._doc = newDoc
    })

    this._unsubSelection = this._engine.onSelectionChange((nodeId) => {
      this._selectedNodeId = nodeId
    })
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback()
    this._unsubEngine?.()
    this._unsubSelection?.()
  }

  override render() {
    if (!this._engine || !this._doc) {
      return html`<div class="flex items-center justify-center h-full text-gray-500">No template loaded</div>`
    }

    return html`
      <div class="epistola-editor flex flex-col h-full w-full bg-white text-gray-900">
        <!-- Toolbar -->
        <epistola-toolbar
          .engine=${this._engine}
        ></epistola-toolbar>

        <!-- Main layout: palette | tree | canvas | inspector -->
        <div class="flex flex-1 overflow-hidden">
          <!-- Palette -->
          <epistola-palette
            class="w-48 border-r border-gray-200 overflow-y-auto shrink-0"
            .engine=${this._engine}
          ></epistola-palette>

          <!-- Tree -->
          <epistola-tree
            class="w-56 border-r border-gray-200 overflow-y-auto shrink-0"
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
          ></epistola-tree>

          <!-- Canvas -->
          <epistola-canvas
            class="flex-1 overflow-auto bg-gray-50"
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
          ></epistola-canvas>

          <!-- Inspector -->
          <epistola-inspector
            class="w-72 border-l border-gray-200 overflow-y-auto shrink-0"
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
