import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { EditorPlugin, PluginContext } from '../plugins/types.js';
import type { NodeId } from '../types/index.js';

export interface QualityPluginOptions {
  findingsUrl: string;
  checkUrl: string;
  csrfToken: () => string;
}

export interface QualityPanelData {
  currentInputFingerprint: string | null;
  openCount: number;
  staleCount: number;
  findings: QualityFinding[];
}

export interface QualityFinding {
  id: string;
  sourceId: string;
  sourceName: string;
  ruleId: string;
  severity: 'ERROR' | 'WARNING' | 'INFO' | string;
  status: 'OPEN' | 'IGNORED' | 'RESOLVED' | string;
  message: string;
  nodeIds: string[];
  primaryNodeId: string | null;
  path: string | null;
  docsUrl: string | null;
  stale: boolean;
  commentCount: number;
}

class QualityService {
  private readonly options: QualityPluginOptions;

  constructor(options: QualityPluginOptions) {
    this.options = options;
  }

  async load(): Promise<QualityPanelData> {
    const response = await fetch(this.options.findingsUrl, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) throw new Error('Failed to load quality findings');
    return await response.json();
  }

  async checkNow(): Promise<QualityPanelData> {
    const response = await fetch(this.options.checkUrl, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'X-XSRF-TOKEN': this.options.csrfToken(),
      },
    });
    if (!response.ok) throw new Error('Failed to run quality checks');
    return await response.json();
  }
}

export function createQualityPlugin(options: QualityPluginOptions): EditorPlugin {
  const service = new QualityService(options);
  return {
    id: 'quality',
    sidebarTab: {
      id: 'quality',
      label: 'Quality',
      keepOpenOnSelection: true,
      render: (context) =>
        html`<epistola-quality-panel
          .service=${service}
          .context=${context}
        ></epistola-quality-panel>`,
    },
    init: () => () => {},
  };
}

@customElement('epistola-quality-panel')
export class EpistolaQualityPanel extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) service?: QualityService;
  @property({ attribute: false }) context?: PluginContext;
  @state() private _data?: QualityPanelData;
  @state() private _loading = false;
  @state() private _checking = false;
  @state() private _error = '';

  override connectedCallback(): void {
    super.connectedCallback();
    void this._load();
  }

  private async _load(): Promise<void> {
    if (!this.service || this._loading) return;
    this._loading = true;
    this._error = '';
    try {
      this._data = await this.service.load();
    } catch (e) {
      this._error = e instanceof Error ? e.message : 'Failed to load quality findings';
    } finally {
      this._loading = false;
    }
  }

  private async _checkNow(): Promise<void> {
    if (!this.service || this._checking) return;
    this._checking = true;
    this._error = '';
    try {
      await this.context?.saveDraftNow?.();
      this._data = await this.service.checkNow();
    } catch (e) {
      this._error = e instanceof Error ? e.message : 'Failed to run quality checks';
    } finally {
      this._checking = false;
    }
  }

  private _selectNode(finding: QualityFinding): void {
    if (!finding.primaryNodeId) return;
    this.context?.engine.selectNode(finding.primaryNodeId as NodeId);
    requestAnimationFrame(() => {
      const block = this.closest('epistola-editor')?.querySelector<HTMLElement>(
        `.canvas-block[data-node-id="${finding.primaryNodeId}"]`,
      );
      block?.focus({ preventScroll: false });
      block?.scrollIntoView({ block: 'center', inline: 'nearest' });
    });
  }

  private _visibleFindings(): QualityFinding[] {
    const findings = this._data?.findings ?? [];
    return [...findings].sort((a, b) => {
      const statusRank = statusOrder(a.status) - statusOrder(b.status);
      if (statusRank !== 0) return statusRank;
      return severityOrder(a.severity) - severityOrder(b.severity);
    });
  }

  override render(): TemplateResult {
    const data = this._data;
    const findings = this._visibleFindings();

    return html`
      <section class="quality-panel" aria-label="Quality">
        <div class="quality-panel-header">
          <div>
            <div class="quality-panel-title">Quality</div>
            <div class="quality-panel-summary">
              ${data ? `${data.openCount} open` : this._loading ? 'Loading' : 'Not loaded'}
              ${data?.staleCount ? html`<span>${data.staleCount} stale</span>` : nothing}
            </div>
          </div>
          <div class="quality-panel-actions">
            <button
              type="button"
              class="quality-icon-button"
              title="Refresh"
              aria-label="Refresh quality findings"
              ?disabled=${this._loading || this._checking}
              @click=${() => this._load()}
            >
              Refresh
            </button>
            <button
              type="button"
              class="ep-btn ep-btn-primary ep-btn-sm"
              ?disabled=${this._loading || this._checking}
              @click=${() => this._checkNow()}
            >
              ${this._checking ? 'Checking' : 'Check now'}
            </button>
          </div>
        </div>

        ${this._error ? html`<div class="quality-panel-error">${this._error}</div>` : nothing}
        ${!this._loading && findings.length === 0
          ? html`<div class="quality-panel-empty">No findings for this variant.</div>`
          : nothing}

        <div class="quality-finding-list">
          ${findings.map((finding) => this._renderFinding(finding))}
        </div>
      </section>
    `;
  }

  private _renderFinding(finding: QualityFinding): TemplateResult {
    const hasNode = !!finding.primaryNodeId;
    return html`
      <article class="quality-finding quality-${finding.severity.toLowerCase()}">
        <button
          type="button"
          class="quality-finding-main"
          ?disabled=${!hasNode}
          @click=${() => this._selectNode(finding)}
        >
          <span class="quality-finding-message">${finding.message}</span>
          <span class="quality-finding-meta">
            <span>${finding.sourceName}</span>
            <span>${finding.ruleId}</span>
          </span>
        </button>
        <div class="quality-finding-badges">
          <span class="quality-badge quality-severity">${finding.severity.toLowerCase()}</span>
          <span class="quality-badge">${finding.status.toLowerCase()}</span>
          ${finding.stale ? html`<span class="quality-badge quality-stale">stale</span>` : nothing}
          ${finding.nodeIds.length > 0
            ? html`<span class="quality-badge"
                >${finding.nodeIds.length} node${finding.nodeIds.length === 1 ? '' : 's'}</span
              >`
            : nothing}
        </div>
      </article>
    `;
  }
}

function statusOrder(status: string): number {
  if (status === 'OPEN') return 0;
  if (status === 'IGNORED') return 1;
  return 2;
}

function severityOrder(severity: string): number {
  if (severity === 'ERROR') return 0;
  if (severity === 'WARNING') return 1;
  return 2;
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-quality-panel': EpistolaQualityPanel;
  }
}
