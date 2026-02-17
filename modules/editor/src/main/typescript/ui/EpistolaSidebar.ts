import { LitElement, html } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { TemplateDocument, NodeId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'

import './EpistolaPalette.js'
import './EpistolaTree.js'
import './EpistolaInspector.js'

type SidebarTab = 'blocks' | 'structure' | 'inspector'

@customElement('epistola-sidebar')
export class EpistolaSidebar extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument
  @property({ attribute: false }) selectedNodeId: NodeId | null = null

  @state() private _activeTab: SidebarTab = 'blocks'

  private _setTab(tab: SidebarTab) {
    this._activeTab = tab
  }

  private get _inspectorLabel(): string {
    return this.selectedNodeId ? 'Inspector' : 'Document'
  }

  override render() {
    return html`
      <div class="epistola-sidebar">
        <div class="sidebar-tabs">
          ${this._renderTab('blocks', 'Blocks')}
          ${this._renderTab('structure', 'Structure')}
          ${this._renderTab('inspector', this._inspectorLabel)}
        </div>
        <div class="sidebar-content">
          ${this._renderActivePanel()}
        </div>
      </div>
    `
  }

  private _renderTab(tab: SidebarTab, label: string) {
    const isActive = this._activeTab === tab
    return html`
      <button
        class="sidebar-tab ${isActive ? 'active' : ''}"
        @click=${() => this._setTab(tab)}
      >${label}</button>
    `
  }

  private _renderActivePanel() {
    switch (this._activeTab) {
      case 'blocks':
        return html`
          <epistola-palette
            .engine=${this.engine}
          ></epistola-palette>
        `
      case 'structure':
        return html`
          <epistola-tree
            .engine=${this.engine}
            .doc=${this.doc}
            .selectedNodeId=${this.selectedNodeId}
          ></epistola-tree>
        `
      case 'inspector':
        return html`
          <epistola-inspector
            .engine=${this.engine}
            .doc=${this.doc}
            .selectedNodeId=${this.selectedNodeId}
          ></epistola-inspector>
        `
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-sidebar': EpistolaSidebar
  }
}
