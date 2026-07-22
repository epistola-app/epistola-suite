import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { TemplateDocument, NodeId } from '../types/index.js';
import type { EditorEngine } from '../engine/EditorEngine.js';
import type { SidebarTabContribution, PluginContext } from '../plugins/types.js';
import { icon } from './icons.js';

import './EpistolaPalette.js';
import './EpistolaTree.js';
import './EpistolaInspector.js';

// ---------------------------------------------------------------------------
// Tab definition — used for both built-in and plugin tabs
// ---------------------------------------------------------------------------

interface TabDefinition {
  id: string;
  label: string | (() => string);
  icon?: string;
  keepOpenOnSelection?: boolean;
  render: () => TemplateResult;
}

@customElement('epistola-sidebar')
export class EpistolaSidebar extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) engine?: EditorEngine;
  @property({ attribute: false }) doc?: TemplateDocument;
  @property({ attribute: false }) selectedNodeId: NodeId | null = null;
  @property({ attribute: false }) pluginTabs?: SidebarTabContribution[];
  @property({ attribute: false }) pluginContext?: PluginContext;

  @state() private _activeTab = 'blocks';
  @state() private _canScrollTabsBack = false;
  @state() private _canScrollTabsForward = false;

  private _tabResizeObserver?: ResizeObserver;

  override willUpdate(changed: Map<string, unknown>) {
    if (changed.has('selectedNodeId') && this.selectedNodeId != null && !this._activeTabKeepsOpen) {
      this._activeTab = 'inspector';
    }
  }

  private _setTab(tabId: string) {
    this._activeTab = tabId;
    void this.updateComplete.then(() => {
      this.querySelector<HTMLElement>(`.sidebar-tab[data-tab-id="${tabId}"]`)?.scrollIntoView({
        block: 'nearest',
        inline: 'nearest',
      });
      this._updateTabOverflow();
    });
  }

  override firstUpdated(): void {
    const tabs = this._tabScroller();
    if (tabs && typeof ResizeObserver !== 'undefined') {
      this._tabResizeObserver = new ResizeObserver(() => this._scheduleTabOverflowUpdate());
      this._tabResizeObserver.observe(tabs);
    }
    this._scheduleTabOverflowUpdate();
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('pluginTabs') || changed.has('selectedNodeId')) {
      this._scheduleTabOverflowUpdate();
    }
  }

  override disconnectedCallback(): void {
    this._tabResizeObserver?.disconnect();
    super.disconnectedCallback();
  }

  private _focusTab(tabId: string, focusTarget: () => HTMLElement | null) {
    this._setTab(tabId);
    void this.updateComplete.then(() => {
      focusTarget()?.focus();
    });
  }

  focusPalette(): void {
    this._focusTab('blocks', () =>
      this.querySelector<HTMLElement>('.epistola-palette .palette-item'),
    );
  }

  focusTree(): void {
    const selectedId = this.selectedNodeId;
    this._focusTab('structure', () => {
      if (selectedId) {
        const selected = this.querySelector<HTMLElement>(
          `.tree-node-label[data-node-id="${selectedId}"]`,
        );
        if (selected) return selected;
      }
      return this.querySelector<HTMLElement>('.tree-node-label[data-node-id]');
    });
  }

  focusInspector(): void {
    this._focusTab('inspector', () =>
      this.querySelector<HTMLElement>(
        '.epistola-inspector input, .epistola-inspector select, .epistola-inspector textarea, .epistola-inspector button, .epistola-inspector [tabindex]',
      ),
    );
  }

  private _tabScroller(): HTMLElement | null {
    return this.querySelector<HTMLElement>('.sidebar-tabs');
  }

  private _scheduleTabOverflowUpdate(): void {
    void this.updateComplete.then(() => this._updateTabOverflow());
  }

  private _updateTabOverflow(): void {
    const tabs = this._tabScroller();
    if (!tabs) return;

    const maxScroll = tabs.scrollWidth - tabs.clientWidth;
    const canScrollBack = tabs.scrollLeft > 1;
    const canScrollForward = tabs.scrollLeft < maxScroll - 1;

    if (this._canScrollTabsBack !== canScrollBack) {
      this._canScrollTabsBack = canScrollBack;
    }
    if (this._canScrollTabsForward !== canScrollForward) {
      this._canScrollTabsForward = canScrollForward;
    }
  }

  private _scrollTabs(direction: -1 | 1): void {
    const tabs = this._tabScroller();
    if (!tabs) return;
    tabs.scrollBy({ left: direction * tabs.clientWidth * 0.75, behavior: 'smooth' });
    requestAnimationFrame(() => this._updateTabOverflow());
  }

  private get _inspectorLabel(): string {
    return this.selectedNodeId ? 'Inspector' : 'Document';
  }

  private get _activeTabKeepsOpen(): boolean {
    return this._allTabs.find((tab) => tab.id === this._activeTab)?.keepOpenOnSelection === true;
  }

  private get _builtinTabs(): TabDefinition[] {
    return [
      {
        id: 'blocks',
        label: 'Blocks',
        render: () => html` <epistola-palette .engine=${this.engine}></epistola-palette> `,
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
    ];
  }

  private get _allTabs(): TabDefinition[] {
    const pluginDefs: TabDefinition[] = (this.pluginTabs ?? []).map((tab) => ({
      id: tab.id,
      label: tab.label,
      icon: tab.icon,
      keepOpenOnSelection: tab.keepOpenOnSelection,
      render: () => {
        if (!this.pluginContext) return html``;
        return tab.render(this.pluginContext);
      },
    }));
    return [...this._builtinTabs, ...pluginDefs];
  }

  override render() {
    const tabs = this._allTabs;
    // Fall back to 'blocks' if the active tab no longer exists (e.g. plugin removed)
    const activeTab = tabs.find((t) => t.id === this._activeTab) ?? tabs[0];

    return html`
      <div class="epistola-sidebar">
        <div class="sidebar-tabs-shell">
          ${this._canScrollTabsBack
            ? html`
                <button
                  type="button"
                  class="sidebar-tab-scroll"
                  title="Previous tabs"
                  aria-label="Show previous tabs"
                  @click=${() => this._scrollTabs(-1)}
                >
                  ${icon('chevron-left', 14)}
                </button>
              `
            : nothing}
          <div class="sidebar-tabs" @scroll=${() => this._updateTabOverflow()}>
            ${tabs.map((tab) => {
              const label = typeof tab.label === 'function' ? tab.label() : tab.label;
              const isActive = tab.id === activeTab?.id;
              return html`
                <button
                  class="sidebar-tab ${isActive ? 'active' : ''}"
                  data-tab-id=${tab.id}
                  @click=${() => this._setTab(tab.id)}
                >
                  ${label}
                </button>
              `;
            })}
          </div>
          ${this._canScrollTabsForward
            ? html`
                <button
                  type="button"
                  class="sidebar-tab-scroll"
                  title="More tabs"
                  aria-label="Show more tabs"
                  @click=${() => this._scrollTabs(1)}
                >
                  ${icon('chevron-right', 14)}
                </button>
              `
            : nothing}
        </div>
        <div class="sidebar-content">${activeTab?.render()}</div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-sidebar': EpistolaSidebar;
  }
}
