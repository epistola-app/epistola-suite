// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import cytoscape, { type Core, type ElementDefinition } from 'cytoscape';
import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import {
  RESOURCE_TYPES,
  displayType,
  type EvidenceResponse,
  type ResourceEdge,
  type ResourceNode,
  type ResourceType,
  type SubgraphResponse,
} from './model';

const NODE_COLORS: Record<ResourceType, string> = {
  template: '#2563eb',
  theme: '#7c3aed',
  stencil: '#0891b2',
  attribute: '#c2410c',
  codeList: '#a16207',
  font: '#4f46e5',
  asset: '#15803d',
};

const DIRECTIONS = ['incoming', 'outgoing', 'both'] as const;

function nodeColor(value: unknown): string {
  return isResourceType(value) ? NODE_COLORS[value] : '#64748b';
}

function isResourceType(value: unknown): value is ResourceType {
  return typeof value === 'string' && RESOURCE_TYPES.some((type) => type.value === value);
}

function isDirection(value: unknown): value is (typeof DIRECTIONS)[number] {
  return typeof value === 'string' && DIRECTIONS.some((direction) => direction === value);
}

function isResourceNode(value: unknown): value is ResourceNode {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<ResourceNode>;
  return (
    typeof candidate.id === 'string' &&
    isResourceType(candidate.type) &&
    typeof candidate.catalogKey === 'string' &&
    typeof candidate.key === 'string' &&
    typeof candidate.name === 'string'
  );
}

function isResourceEdge(value: unknown): value is ResourceEdge {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<ResourceEdge>;
  return typeof candidate.id === 'string' && typeof candidate.source === 'string';
}

@customElement('ep-resource-graph')
export class ResourceGraphExplorer extends LitElement {
  @property({ attribute: 'data-base-url' }) baseUrl = '';
  @state() private search = '';
  @state() private searchResults: ResourceNode[] = [];
  @state() private searchResultsVisible = false;
  @state() private searching = false;
  @state() private catalogOptions: Array<[string, string]> = [];
  @state() private graph?: SubgraphResponse;
  @state() private selectedEdge?: ResourceEdge;
  @state() private evidence?: EvidenceResponse;
  @state() private direction: 'incoming' | 'outgoing' | 'both' = 'both';
  @state() private depth = 1;
  @state() private includeHistory = false;
  @state() private typeFilter = '';
  @state() private catalogFilter = '';
  @state() private semanticsFilter = '';
  @state() private loading = false;
  @state() private error = '';
  private cy?: Core;
  private searchSequence = 0;

  static styles = css`
    :host {
      display: block;
      color: var(--ep-color-text, #172033);
    }
    .toolbar,
    .filters,
    .legend {
      display: flex;
      gap: 0.75rem;
      align-items: end;
      flex-wrap: wrap;
    }
    .toolbar {
      padding: 1rem;
      border: 1px solid var(--ep-color-border, #d8dee9);
      border-radius: 0.5rem;
      background: var(--ep-color-surface, white);
    }
    .resource-search {
      position: relative;
      display: flex;
      gap: 0.5rem;
      align-items: end;
    }
    label {
      display: grid;
      gap: 0.3rem;
      color: var(--ep-color-text-muted, #536176);
      font-size: 0.78rem;
      font-weight: 600;
    }
    input,
    select {
      min-height: 2.35rem;
      padding: 0.45rem 0.65rem;
      border: 1px solid var(--ep-color-border, #c9d1dc);
      border-radius: 0.35rem;
      background: white;
      color: inherit;
    }
    input[type='search'] {
      width: min(28rem, 70vw);
    }
    input[type='checkbox'] {
      min-height: auto;
    }
    .check {
      display: flex;
      align-items: center;
      gap: 0.45rem;
      min-height: 2.35rem;
    }
    button {
      min-height: 2.35rem;
      padding: 0.45rem 0.75rem;
      border: 1px solid var(--ep-color-border, #c9d1dc);
      border-radius: 0.35rem;
      background: white;
      cursor: pointer;
    }
    button:hover {
      background: #f4f7fb;
    }
    .results {
      position: absolute;
      top: calc(100% + 0.25rem);
      left: 0;
      z-index: 5;
      width: min(32rem, 85vw);
      max-height: 20rem;
      overflow: auto;
      margin: 0.25rem 0 0;
      padding: 0.25rem;
      list-style: none;
      border: 1px solid #c9d1dc;
      border-radius: 0.4rem;
      background: white;
      box-shadow: 0 0.5rem 1.5rem rgb(15 23 42 / 15%);
    }
    .results-empty {
      padding: 0.75rem;
      color: #667085;
    }
    .results button {
      width: 100%;
      border: 0;
      text-align: left;
      display: grid;
      grid-template-columns: 6rem 1fr;
      gap: 0.5rem;
    }
    .result-meta {
      color: #667085;
      font-size: 0.75rem;
    }
    .workspace {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 20rem;
      gap: 1rem;
      margin-top: 1rem;
    }
    .canvas-panel,
    .details {
      border: 1px solid var(--ep-color-border, #d8dee9);
      border-radius: 0.5rem;
      background: white;
    }
    #graph {
      height: 36rem;
    }
    .empty {
      height: 36rem;
      display: grid;
      place-content: center;
      padding: 2rem;
      text-align: center;
      color: #667085;
    }
    .canvas-actions {
      position: absolute;
      z-index: 2;
      display: flex;
      gap: 0.35rem;
      margin: 0.65rem;
    }
    .canvas-actions button {
      min-height: 2rem;
      padding: 0.3rem 0.55rem;
    }
    .details {
      padding: 1rem;
      max-height: 36rem;
      overflow: auto;
    }
    .details h2,
    .details h3 {
      margin: 0 0 0.7rem;
    }
    .details h3 {
      margin-top: 1.25rem;
      font-size: 0.9rem;
    }
    .connections {
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 0.45rem;
    }
    .connections button {
      width: 100%;
      text-align: left;
    }
    .badge {
      display: inline-block;
      padding: 0.15rem 0.4rem;
      border-radius: 999px;
      background: #eef2f7;
      font-size: 0.72rem;
    }
    .badge.missing {
      color: #991b1b;
      background: #fee2e2;
    }
    .badge.ambiguous {
      color: #854d0e;
      background: #fef3c7;
    }
    .evidence {
      border-top: 1px solid #e5e7eb;
      margin-top: 0.7rem;
      padding-top: 0.7rem;
      font-size: 0.82rem;
      overflow-wrap: anywhere;
    }
    .legend {
      padding: 0.7rem 0;
      font-size: 0.75rem;
      color: #667085;
    }
    .legend span {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
    }
    .dot {
      width: 0.65rem;
      height: 0.65rem;
      border-radius: 50%;
      display: inline-block;
    }
    .status {
      margin-top: 0.7rem;
      color: #667085;
    }
    .error {
      color: #991b1b;
    }
    @media (max-width: 900px) {
      .workspace {
        grid-template-columns: 1fr;
      }
      #graph,
      .empty {
        height: 28rem;
      }
      .details {
        max-height: none;
      }
    }
  `;

  connectedCallback(): void {
    super.connectedCallback();
    const params = new URLSearchParams(window.location.search);
    const requestedDirection = params.get('direction');
    this.direction = isDirection(requestedDirection) ? requestedDirection : 'both';
    this.depth = Number(params.get('depth') ?? 1);
    this.includeHistory = params.get('history') === 'true';
    const requestedType = params.get('type');
    const type = isResourceType(requestedType) ? requestedType : undefined;
    const catalog = params.get('catalog');
    const key = params.get('key');
    void this.loadNodes(false);
    if (type && catalog && key)
      void this.focusResource({
        id: `${type}:${catalog}:${key}`,
        type,
        catalogKey: catalog,
        key,
        name: key,
        catalogName: catalog,
        catalogType: '',
      });
  }

  disconnectedCallback(): void {
    this.cy?.destroy();
    this.cy = undefined;
    super.disconnectedCallback();
  }

  protected updated(changed: Map<string, unknown>): void {
    if (changed.has('graph')) this.renderCanvas();
  }

  private async loadNodes(showResults: boolean): Promise<void> {
    const sequence = ++this.searchSequence;
    const params = new URLSearchParams({ q: this.search });
    if (this.typeFilter) params.set('type', this.typeFilter);
    if (this.catalogFilter) params.set('catalog', this.catalogFilter);
    if (showResults) this.searching = true;
    try {
      const response = await fetch(`${this.baseUrl}/nodes?${params}`);
      if (!response.ok) throw new Error(`Could not load resources (${response.status})`);
      const body = (await response.json()) as {
        nodes: ResourceNode[];
        catalogs: Array<{ key: string; name: string }>;
      };
      if (sequence === this.searchSequence) {
        this.catalogOptions = body.catalogs.map((catalog) => [catalog.key, catalog.name]);
        if (showResults) {
          this.searchResults = body.nodes;
          this.searchResultsVisible = true;
        }
      }
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Could not load resources';
    } finally {
      if (sequence === this.searchSequence) this.searching = false;
    }
  }

  private searchResources(event: SubmitEvent): void {
    event.preventDefault();
    this.search = this.search.trim();
    if (!this.search) {
      this.searchResults = [];
      this.searchResultsVisible = false;
      return;
    }
    void this.loadNodes(true);
  }

  private async focusResource(node: ResourceNode): Promise<void> {
    this.loading = true;
    this.error = '';
    this.search = node.name;
    this.searchResults = [];
    this.searchResultsVisible = false;
    const params = new URLSearchParams({
      type: node.type,
      catalog: node.catalogKey,
      key: node.key,
      direction: this.direction,
      depth: String(this.depth),
      includeHistory: String(this.includeHistory),
    });
    if (this.typeFilter) params.set('types', this.typeFilter);
    if (this.catalogFilter) params.set('catalogs', this.catalogFilter);
    if (this.semanticsFilter) params.set('semantics', this.semanticsFilter);
    try {
      const response = await fetch(`${this.baseUrl}/subgraph?${params}`);
      if (!response.ok)
        throw new Error(
          response.status === 404
            ? 'Resource not found with the active filters'
            : `Could not load graph (${response.status})`,
        );
      this.graph = (await response.json()) as SubgraphResponse;
      this.selectedEdge = undefined;
      this.evidence = undefined;
      const url = new URL(window.location.href);
      for (const [key, value] of [
        ['type', node.type],
        ['catalog', node.catalogKey],
        ['key', node.key],
        ['direction', this.direction],
        ['depth', String(this.depth)],
        ['history', String(this.includeHistory)],
      ])
        url.searchParams.set(key, value);
      window.history.replaceState({}, '', url);
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Could not load graph';
    } finally {
      this.loading = false;
    }
  }

  private async reload(): Promise<void> {
    const graph = this.graph;
    if (!graph) return;
    const node = graph.nodes.find((candidate) => candidate.id === graph.focus.id);
    if (node) await this.focusResource(node);
  }

  private renderCanvas(): void {
    const container = this.renderRoot.querySelector<HTMLElement>('#graph');
    if (!container || !this.graph) return;
    this.cy?.destroy();
    const elements: ElementDefinition[] = this.graph.nodes.map((node) => ({
      data: { id: node.id, label: node.name, type: node.type, node },
    }));
    for (const edge of this.graph.edges) {
      let target = edge.target;
      if (!target) {
        target = `unresolved:${edge.id}`;
        elements.push({
          data: {
            id: target,
            label: `${edge.targetSelector.key} (${edge.resolution})`,
            type: edge.targetSelector.type,
            unresolved: edge.resolution,
          },
        });
      }
      elements.push({
        data: {
          id: edge.id,
          source: edge.source,
          target,
          label: edge.kind,
          semantics: edge.semantics,
          resolution: edge.resolution,
          edge,
        },
      });
    }
    this.cy = cytoscape({
      container,
      elements,
      layout: { name: 'cose', animate: false, fit: true, padding: 36 },
      style: [
        {
          selector: 'node',
          style: {
            'background-color': (element) => nodeColor(element.data('type')),
            label: 'data(label)',
            color: '#172033',
            'font-size': 10,
            'text-valign': 'bottom',
            'text-margin-y': 7,
            width: 30,
            height: 30,
          },
        },
        {
          selector: `node[id = "${this.graph.focus.id}"]`,
          style: { 'border-width': 4, 'border-color': '#111827', width: 40, height: 40 },
        },
        {
          selector: 'node[unresolved]',
          style: { shape: 'diamond', 'background-color': '#dc2626', 'border-style': 'dashed' },
        },
        {
          selector: 'edge',
          style: {
            width: 2,
            'line-color': '#94a3b8',
            'target-arrow-color': '#94a3b8',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
          },
        },
        { selector: 'edge[semantics = "authoring"]', style: { 'line-style': 'dashed' } },
        { selector: 'edge[semantics = "provenance"]', style: { 'line-style': 'dotted' } },
        {
          selector: 'edge[resolution != "resolved"]',
          style: { 'line-color': '#dc2626', 'target-arrow-color': '#dc2626' },
        },
        {
          selector: ':selected',
          style: { 'overlay-color': '#2563eb', 'overlay-opacity': 0.15, 'overlay-padding': 6 },
        },
      ],
    });
    this.cy.on('tap', 'node', (event) => {
      const node: unknown = event.target.data('node');
      if (isResourceNode(node)) void this.focusResource(node);
    });
    this.cy.on('tap', 'edge', (event) => {
      const edge: unknown = event.target.data('edge');
      if (isResourceEdge(edge)) void this.selectEdge(edge);
    });
  }

  private async selectEdge(edge: ResourceEdge, page = 1): Promise<void> {
    this.selectedEdge = edge;
    this.evidence = undefined;
    const params = new URLSearchParams({
      edgeId: edge.id,
      includeHistory: String(this.includeHistory),
      page: String(page),
    });
    const response = await fetch(`${this.baseUrl}/evidence?${params}`);
    if (response.ok) this.evidence = (await response.json()) as EvidenceResponse;
  }

  private connectionLabel(edge: ResourceEdge): string {
    const otherId = edge.source === this.graph?.focus.id ? edge.target : edge.source;
    const node = this.graph?.nodes.find((candidate) => candidate.id === otherId);
    return node?.name ?? edge.targetSelector.key;
  }

  protected render() {
    const outgoing = this.graph?.edges.filter((edge) => edge.source === this.graph?.focus.id) ?? [];
    const incoming = this.graph?.edges.filter((edge) => edge.target === this.graph?.focus.id) ?? [];
    return html`
      <div class="toolbar">
        <form
          class="resource-search"
          role="search"
          @submit=${(event: SubmitEvent) => this.searchResources(event)}
          @focusout=${(event: FocusEvent) => {
            if (!(event.currentTarget instanceof HTMLFormElement)) return;
            if (
              event.relatedTarget instanceof Node &&
              event.currentTarget.contains(event.relatedTarget)
            )
              return;
            this.searchResultsVisible = false;
          }}
        >
          <label
            >Find a resource
            <input
              type="search"
              .value=${this.search}
              placeholder="Name, key, or catalog"
              @input=${(event: InputEvent) => {
                if (!(event.currentTarget instanceof HTMLInputElement)) return;
                this.search = event.currentTarget.value;
                this.searchResults = [];
                this.searchResultsVisible = false;
              }}
              @keydown=${(event: KeyboardEvent) => {
                if (event.key === 'Escape') this.searchResultsVisible = false;
              }}
              aria-label="Find a resource"
            />
          </label>
          <button type="submit" ?disabled=${this.searching || !this.search.trim()}>
            ${this.searching ? 'Searching…' : 'Search'}
          </button>
          ${this.searchResultsVisible
            ? html`<ul class="results" aria-label="Resource search results">
                ${this.searchResults.length
                  ? this.searchResults.map(
                      (node) =>
                        html`<li>
                          <button type="button" @click=${() => void this.focusResource(node)}>
                            <span class="badge">${displayType(node.type)}</span
                            ><span
                              ><strong>${node.name}</strong><br /><span class="result-meta"
                                >${node.catalogName} · ${node.key}</span
                              ></span
                            >
                          </button>
                        </li>`,
                    )
                  : html`<li class="results-empty">No resources found.</li>`}
              </ul>`
            : nothing}
        </form>
        <label
          >Direction<select
            .value=${this.direction}
            @change=${(event: Event) => {
              if (!(event.currentTarget instanceof HTMLSelectElement)) return;
              if (!isDirection(event.currentTarget.value)) return;
              this.direction = event.currentTarget.value;
              void this.reload();
            }}
          >
            <option value="both">Both</option>
            <option value="outgoing">Uses</option>
            <option value="incoming">Used by</option>
          </select></label
        >
        <label
          >Depth<select
            .value=${String(this.depth)}
            @change=${(event: Event) => {
              if (!(event.currentTarget instanceof HTMLSelectElement)) return;
              this.depth = Number(event.currentTarget.value);
              void this.reload();
            }}
          >
            <option>1</option>
            <option>2</option>
            <option>3</option>
          </select></label
        >
        <label
          >Resource type<select
            .value=${this.typeFilter}
            @change=${(event: Event) => {
              if (!(event.currentTarget instanceof HTMLSelectElement)) return;
              this.typeFilter = event.currentTarget.value;
              this.searchResultsVisible = false;
              void this.reload();
            }}
          >
            <option value="">All types</option>
            ${RESOURCE_TYPES.map(
              (type) => html`<option value=${type.value}>${type.label}</option>`,
            )}
          </select></label
        >
        <label
          >Catalog<select
            .value=${this.catalogFilter}
            @change=${(event: Event) => {
              if (!(event.currentTarget instanceof HTMLSelectElement)) return;
              this.catalogFilter = event.currentTarget.value;
              this.searchResultsVisible = false;
              void this.reload();
            }}
          >
            <option value="">All catalogs</option>
            ${this.catalogOptions.map(([key, name]) => html`<option value=${key}>${name}</option>`)}
          </select></label
        >
        <label
          >Reference<select
            .value=${this.semanticsFilter}
            @change=${(event: Event) => {
              if (!(event.currentTarget instanceof HTMLSelectElement)) return;
              const value = event.currentTarget.value;
              this.semanticsFilter =
                value === 'runtime' || value === 'authoring' || value === 'provenance' ? value : '';
              void this.reload();
            }}
          >
            <option value="">All references</option>
            <option value="runtime">Runtime</option>
            <option value="authoring">Authoring</option>
            <option value="provenance">Provenance</option>
          </select></label
        >
        <label class="check"
          ><input
            type="checkbox"
            .checked=${this.includeHistory}
            @change=${(event: Event) => {
              if (!(event.currentTarget instanceof HTMLInputElement)) return;
              this.includeHistory = event.currentTarget.checked;
              void this.reload();
            }}
          />
          Include archived history</label
        >
      </div>
      ${this.error ? html`<p class="status error" role="alert">${this.error}</p>` : nothing}
      ${this.loading ? html`<p class="status" role="status">Loading graph…</p>` : nothing}
      <div class="workspace">
        <section class="canvas-panel" aria-label="Resource reference graph">
          ${this.graph
            ? html`<div class="canvas-actions">
                  <button type="button" @click=${() => this.cy?.fit(undefined, 35)}>Fit</button
                  ><button type="button" @click=${() => this.cy?.zoom(1)}>100%</button>
                </div>
                <div id="graph"></div>`
            : html`<div class="empty">
                <div>
                  <strong>Select a resource to begin</strong><br />Search by name, key, or catalog,
                  then explore its references.
                </div>
              </div>`}
        </section>
        <aside class="details" aria-live="polite">
          ${this.selectedEdge ? this.renderEvidence() : this.renderConnections(incoming, outgoing)}
        </aside>
      </div>
      <div class="legend">
        ${RESOURCE_TYPES.map(
          (type) =>
            html`<span
              ><i class="dot" style=${`background:${NODE_COLORS[type.value]}`}></i
              >${type.label}</span
            >`,
        )}<span>solid = runtime</span><span>dashed = authoring</span
        ><span>dotted = provenance</span>
      </div>
    `;
  }

  private renderConnections(incoming: ResourceEdge[], outgoing: ResourceEdge[]) {
    if (!this.graph)
      return html`<h2>References</h2>
        <p>Select a resource to see its connections.</p>`;
    const focus = this.graph.nodes.find((node) => node.id === this.graph?.focus.id);
    const list = (edges: ResourceEdge[]) =>
      edges.length
        ? html`<ul class="connections">
            ${edges.map(
              (edge) =>
                html`<li>
                  <button type="button" @click=${() => void this.selectEdge(edge)}>
                    ${this.connectionLabel(edge)}
                    <span class=${`badge ${edge.resolution}`}
                      >${edge.kind}${edge.resolution === 'resolved'
                        ? ''
                        : ` · ${edge.resolution}`}</span
                    >
                  </button>
                </li>`,
            )}
          </ul>`
        : html`<p>None in this view.</p>`;
    return html`<h2>${focus?.name ?? this.graph.focus.key}</h2>
      <p class="result-meta">
        ${displayType(this.graph.focus.type)} ·
        ${this.graph.focus.catalogKey}/${this.graph.focus.key}
      </p>
      <h3>Uses</h3>
      ${list(outgoing)}
      <h3>Used by</h3>
      ${list(incoming)}`;
  }

  private renderEvidence() {
    const edge = this.selectedEdge;
    if (!edge) return nothing;
    const evidence = this.evidence;
    return html`<button
        type="button"
        @click=${() => {
          this.selectedEdge = undefined;
          this.evidence = undefined;
        }}
      >
        ← Back
      </button>
      <h2 style="margin-top:1rem">${edge.kind}</h2>
      <p>
        <span class="badge">${edge.semantics}</span>
        <span class=${`badge ${edge.resolution}`}>${edge.resolution}</span>
      </p>
      <p>
        ${edge.source}<br />→
        ${edge.target ?? `${edge.targetSelector.catalogKey ?? '*'}:${edge.targetSelector.key}`}
      </p>
      <h3>Evidence (${edge.evidenceCount})</h3>
      ${evidence
        ? html`${evidence.items.map(
            (item) =>
              html`<div class="evidence">
                <strong>${item.owner}</strong> ${item.status
                  ? html`<span class="badge"
                      >${item.status}${item.version ? ` v${item.version}` : ''}</span
                    >`
                  : nothing}<br />${item.location}${item.pinnedVersion
                  ? html`<br />pinned stencil v${item.pinnedVersion}`
                  : nothing}
              </div>`,
          )}${evidence.totalPages > 1
            ? html`<p>
                <button
                  type="button"
                  ?disabled=${evidence.page <= 1}
                  @click=${() => void this.selectEdge(edge, evidence.page - 1)}
                >
                  Previous
                </button>
                <span>Page ${evidence.page} of ${evidence.totalPages}</span>
                <button
                  type="button"
                  ?disabled=${evidence.page >= evidence.totalPages}
                  @click=${() => void this.selectEdge(edge, evidence.page + 1)}
                >
                  Next
                </button>
              </p>`
            : nothing}`
        : html`<p>Loading evidence…</p>`}`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'ep-resource-graph': ResourceGraphExplorer;
  }
}
