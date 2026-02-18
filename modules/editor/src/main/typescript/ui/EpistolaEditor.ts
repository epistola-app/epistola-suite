import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { TemplateDocument, NodeId } from '../types/index.js'
import { EditorEngine } from '../engine/EditorEngine.js'
import { createDefaultRegistry } from '../engine/registry.js'
import type { ComponentRegistry } from '../engine/registry.js'
import type { FetchPreviewFn } from './preview-service.js'
import { SaveService, type SaveState, type SaveFn } from './save-service.js'
import { EpistolaResizeHandle } from './EpistolaResizeHandle.js'
import type { EditorPlugin, PluginContext, PluginDisposeFn, SidebarTabContribution, ToolbarAction } from '../plugins/types.js'

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
  private _saveService?: SaveService
  private _pluginDisposers: PluginDisposeFn[] = []
  private _onKeydown = this._handleKeydown.bind(this)
  private _onBeforeUnload = this._handleBeforeUnload.bind(this)

  @property({ attribute: false }) fetchPreview?: FetchPreviewFn
  @property({ attribute: false }) onSave?: SaveFn
  @property({ attribute: false }) plugins?: EditorPlugin[]
  @state() private _doc?: TemplateDocument
  @state() private _selectedNodeId: NodeId | null = null
  @state() private _previewOpen = false
  @state() private _saveState: SaveState = { status: 'idle' }

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
    // Clean up previous engine and save service
    this._unsubEngine?.()
    this._unsubSelection?.()
    this._saveService?.dispose()
    this._saveService = undefined

    const reg = registry ?? createDefaultRegistry()
    this._engine = new EditorEngine(doc, reg, {
      dataModel: options?.dataModel,
      dataExamples: options?.dataExamples,
    })
    this._doc = this._engine.doc

    this._unsubEngine = this._engine.events.on('doc:change', ({ doc }) => {
      this._doc = doc
      // Notify save service of changes
      if (this._saveService) {
        this._saveService.markDirty()
        this._saveService.scheduleAutoSave(doc)
      }
    })

    this._unsubSelection = this._engine.events.on('selection:change', ({ nodeId }) => {
      this._selectedNodeId = nodeId
      // Clear component state when selecting a different node
      this._engine?.setComponentState('table:cellSelection', null)
    })

    // Create save service if onSave callback is provided
    if (this.onSave) {
      this._saveService = new SaveService(this.onSave, (state) => {
        this._saveState = state
      })
    }

    // Initialize plugins
    this._disposePlugins()
    if (this.plugins) {
      const context: PluginContext = {
        engine: this._engine,
        doc: this._doc!,
        selectedNodeId: this._selectedNodeId,
      }
      this._pluginDisposers = this.plugins.map((p) => p.init(context))
    }
  }

  private _disposePlugins(): void {
    this._pluginDisposers.forEach((dispose) => dispose())
    this._pluginDisposers = []
  }

  /** Sidebar tab contributions from plugins. */
  private get _pluginSidebarTabs(): SidebarTabContribution[] {
    if (!this.plugins) return []
    return this.plugins.filter((p) => p.sidebarTab).map((p) => p.sidebarTab!)
  }

  /** Toolbar action contributions from plugins. */
  private get _pluginToolbarActions(): ToolbarAction[] {
    if (!this.plugins) return []
    return this.plugins.flatMap((p) => p.toolbarActions ?? [])
  }

  /** Current plugin context for reactive sidebar tab rendering. */
  private get _pluginContext(): PluginContext | undefined {
    if (!this._engine || !this._doc) return undefined
    return {
      engine: this._engine,
      doc: this._doc,
      selectedNodeId: this._selectedNodeId,
    }
  }

  override connectedCallback(): void {
    super.connectedCallback()
    this.addEventListener('keydown', this._onKeydown)
    this.addEventListener('toggle-preview', this._handleTogglePreview)
    this.addEventListener('force-save', this._handleForceSave)
    window.addEventListener('beforeunload', this._onBeforeUnload)

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
    this.removeEventListener('force-save', this._handleForceSave)
    window.removeEventListener('beforeunload', this._onBeforeUnload)
    super.disconnectedCallback()
    this._disposePlugins()
    this._unsubEngine?.()
    this._unsubSelection?.()
    this._saveService?.dispose()
  }

  /**
   * Global keyboard handler for undo/redo and save.
   */
  private _handleKeydown(e: KeyboardEvent): void {
    if (!this._engine) return
    const mod = e.metaKey || e.ctrlKey
    if (!mod) return

    if (e.key === 's') {
      e.preventDefault()
      if (this._saveService && this._doc) {
        this._saveService.forceSave(this._doc)
      }
    } else if (e.key === 'z' && !e.shiftKey) {
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

  private _handleForceSave = () => {
    if (this._saveService && this._doc) {
      this._saveService.forceSave(this._doc)
    }
  }

  /**
   * Warn users about unsaved changes when closing/navigating away.
   */
  private _handleBeforeUnload(e: BeforeUnloadEvent): void {
    if (this._saveService?.isDirtyOrSaving) {
      e.preventDefault()
    }
  }

  override render() {
    if (!this._engine || !this._doc) {
      return html`<div class="editor-empty">No template loaded</div>`
    }

    const hasPreview = !!this.fetchPreview
    const hasSave = !!this.onSave
    const showPreview = hasPreview && this._previewOpen
    const previewWidth = showPreview ? EpistolaResizeHandle.getPersistedWidth() : undefined

    return html`
      <div class="epistola-editor">
        <!-- Toolbar -->
        <epistola-toolbar
          .engine=${this._engine}
          .previewOpen=${this._previewOpen}
          .hasPreview=${hasPreview}
          .hasSave=${hasSave}
          .saveState=${this._saveState}
          .pluginActions=${this._pluginToolbarActions}
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
            .pluginTabs=${this._pluginSidebarTabs}
            .pluginContext=${this._pluginContext}
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
