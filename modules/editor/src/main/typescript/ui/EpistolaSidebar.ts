import { LitElement, html, type TemplateResult } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { TemplateDocument, NodeId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { SidebarTabContribution, PluginContext } from '../plugins/types.js'

import './EpistolaPalette.js'
import './EpistolaTree.js'
import './EpistolaInspector.js'

// ---------------------------------------------------------------------------
// Tab definition — used for both built-in and plugin tabs
// ---------------------------------------------------------------------------

interface TabDefinition {
  id: string
  label: string | (() => string)
  icon?: string
  render: () => TemplateResult
}

@customElement('epistola-sidebar')
export class EpistolaSidebar extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument
  @property({ attribute: false }) selectedNodeId: NodeId | null = null
  @property({ attribute: false }) pluginTabs?: SidebarTabContribution[]
  @property({ attribute: false }) pluginContext?: PluginContext

  @state() private _activeTab = 'blocks'

  private _setTab(tabId: string) {
    this._activeTab = tabId
  }

  private _focusTab(tabId: string, focusTarget: () => HTMLElement | null) {
    this._setTab(tabId)
    void this.updateComplete.then(() => {
      focusTarget()?.focus()
    })
  }

  focusPalette(): void {
    this._focusTab('blocks', () =>
      this.querySelector<HTMLElement>('.epistola-palette .palette-item'),
    )
  }

  focusTree(): void {
    const selectedId = this.selectedNodeId
    this._focusTab('structure', () => {
      if (selectedId) {
        const selected = this.querySelector<HTMLElement>(`.tree-node-label[data-node-id="${selectedId}"]`)
        if (selected) return selected
      }
      return this.querySelector<HTMLElement>('.tree-node-label[data-node-id]')
    })
  }

  focusInspector(): void {
    this._focusTab('inspector', () =>
      this.querySelector<HTMLElement>(
        '.epistola-inspector input, .epistola-inspector select, .epistola-inspector textarea, .epistola-inspector button, .epistola-inspector [tabindex]'
      ),
    )
  }

  private get _inspectorLabel(): string {
    return this.selectedNodeId ? 'Inspector' : 'Document'
  }

  private get _builtinTabs(): TabDefinition[] {
    return [
      {
        id: 'blocks',
        label: 'Blocks',
        render: () => html`
          <epistola-palette
            .engine=${this.engine}
          ></epistola-palette>
        `,
      },
      {
        id: 'structure',
        label: 'Structure',
        render: () => html`
          <epistola-tree
            .engine=${this.engine}
            .doc=${this.doc}
            .selectedNodeId=${this.selectedNodeId}
          ></epistola-tree>
        `,
      },
      {
        id: 'inspector',
        label: () => this._inspectorLabel,
        render: () => html`
          <epistola-inspector
            .engine=${this.engine}
            .doc=${this.doc}
            .selectedNodeId=${this.selectedNodeId}
          ></epistola-inspector>
        `,
      },
    ]
  }

  private get _allTabs(): TabDefinition[] {
    const pluginDefs: TabDefinition[] = (this.pluginTabs ?? []).map((tab) => ({
      id: tab.id,
      label: tab.label,
      icon: tab.icon,
      render: () => {
        if (!this.pluginContext) return html``
        return tab.render(this.pluginContext)
      },
    }))
    return [...this._builtinTabs, ...pluginDefs]
  }

  override render() {
    const tabs = this._allTabs
    // Fall back to 'blocks' if the active tab no longer exists (e.g. plugin removed)
    const activeTab = tabs.find((t) => t.id === this._activeTab) ?? tabs[0]

    return html`
      <div class="epistola-sidebar">
        <div class="sidebar-tabs">
          ${tabs.map((tab) => {
            const label = typeof tab.label === 'function' ? tab.label() : tab.label
            const isActive = tab.id === activeTab?.id
            return html`
              <button
                class="sidebar-tab ${isActive ? 'active' : ''}"
                @click=${() => this._setTab(tab.id)}
              >${label}</button>
            `
          })}
        </div>
        <div class="sidebar-content">
          ${activeTab?.render()}
        </div>
      </div>
    `
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-sidebar': EpistolaSidebar
  }
}
