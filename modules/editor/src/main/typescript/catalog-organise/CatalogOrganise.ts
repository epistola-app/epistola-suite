// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type {
  Blocker,
  Destination,
  OrganiseCatalog,
  OrganiseResource,
  RelocationPreview,
} from './model.js';

/**
 * A browser across a tenant's catalogs that allows moving what it shows.
 *
 * Relocation began life in the resource graph, which had the reference data a preview needs but is
 * a read-only diagnostic tool: an author reorganising catalogs would not think to open it, and it
 * could only act on the single node in focus. This is the operation's own page — pick resources
 * from anywhere, say where they go, see what it does, apply it as one batch.
 *
 * Deep-linkable via `?resource=<type>:<catalog>:<key>`, repeatable, so anything that notices a
 * misplaced resource can hand off here with it already selected.
 */
@customElement('ep-catalog-organise')
export class CatalogOrganise extends LitElement {
  // Light DOM: the app's stylesheet is global, and a shadow root would cut this off from it.
  protected createRenderRoot() {
    return this;
  }

  @property({ attribute: 'data-base-url' }) baseUrl = '';
  @property({ attribute: 'data-preselected' }) preselected = '';

  @state() private catalogs: OrganiseCatalog[] = [];
  @state() private resources: OrganiseResource[] = [];
  @state() private search = '';
  @state() private catalogFilter = '';
  @state() private selected = new Map<string, Destination>();
  @state() private preview?: RelocationPreview;
  @state() private busy = false;
  @state() private error = '';
  @state() private applied = false;

  connectedCallback() {
    super.connectedCallback();
    void this.load();
  }

  private async load(): Promise<void> {
    this.busy = true;
    try {
      const params = new URLSearchParams();
      if (this.search.trim()) params.set('q', this.search.trim());
      const response = await fetch(`${this.baseUrl}/resources?${params}`);
      if (!response.ok) throw new Error(`Could not load resources (${response.status})`);
      const body = (await response.json()) as {
        catalogs: OrganiseCatalog[];
        resources: OrganiseResource[];
      };
      this.catalogs = body.catalogs;
      this.resources = body.resources;
      this.applyDeepLink();
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Could not load resources';
    } finally {
      this.busy = false;
    }
  }

  /** Runs once, after the first load: a deep link names resources that must exist to be selected. */
  private applyDeepLink(): void {
    if (!this.preselected || this.selected.size > 0) return;
    const wanted = new Set(this.preselected.split(',').filter(Boolean));
    for (const resource of this.resources) {
      if (wanted.has(resource.id)) this.selected.set(resource.id, { catalog: '', key: '' });
    }
    if (this.selected.size > 0) this.requestUpdate();
  }

  private toggle(resource: OrganiseResource): void {
    if (this.selected.has(resource.id)) this.selected.delete(resource.id);
    else this.selected.set(resource.id, { catalog: '', key: '' });
    this.preview = undefined;
    this.applied = false;
    this.requestUpdate();
  }

  private setDestination(id: string, patch: Partial<Destination>): void {
    const current = this.selected.get(id);
    if (!current) return;
    this.selected.set(id, { ...current, ...patch });
    this.preview = undefined;
    this.applied = false;
    this.requestUpdate();
  }

  /** A destination left blank means "unchanged", so a rename needs no catalog and vice versa. */
  private batch() {
    return [...this.selected.entries()].flatMap(([id, destination]) => {
      const resource = this.resources.find((candidate) => candidate.id === id);
      if (!resource) return [];
      return [
        {
          type: resource.type,
          catalog: resource.catalogKey,
          key: resource.key,
          targetCatalog: destination.catalog || resource.catalogKey,
          targetKey: destination.key.trim() || undefined,
        },
      ];
    });
  }

  private async post(path: string, planFingerprint?: string): Promise<RelocationPreview> {
    const response = await fetch(`${this.baseUrl}/${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': window.getCsrfToken?.() ?? '',
      },
      body: JSON.stringify({ relocations: this.batch(), planFingerprint }),
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => null)) as { blockers?: Blocker[] } | null;
      throw new Error(
        body?.blockers?.map((blocker) => blocker.message).join('; ') ||
          `Request failed (${response.status})`,
      );
    }
    return (await response.json()) as RelocationPreview;
  }

  private async runPreview(): Promise<void> {
    this.busy = true;
    this.error = '';
    try {
      this.preview = await this.post('preview');
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Could not preview';
    } finally {
      this.busy = false;
    }
  }

  private async apply(): Promise<void> {
    if (!this.preview?.executable) return;
    this.busy = true;
    this.error = '';
    try {
      await this.post('execute', this.preview.planFingerprint);
      this.selected = new Map();
      this.preview = undefined;
      this.applied = true;
      await this.load();
    } catch (error) {
      this.error = error instanceof Error ? error.message : 'Could not move';
    } finally {
      this.busy = false;
    }
  }

  private blockersFor(resource: OrganiseResource): Blocker[] {
    return (this.preview?.blockers ?? []).filter(
      (blocker) =>
        blocker.source?.catalogKey === resource.catalogKey && blocker.source?.key === resource.key,
    );
  }

  private get batchBlockers(): Blocker[] {
    return (this.preview?.blockers ?? []).filter((blocker) => !blocker.source);
  }

  protected render() {
    const visible = this.catalogFilter
      ? this.resources.filter((resource) => resource.catalogKey === this.catalogFilter)
      : this.resources;
    const authored = this.catalogs.filter((catalog) => catalog.type === 'authored');

    return html`
      ${this.error ? html`<p class="ep-alert ep-alert-error" role="alert">${this.error}</p>` : nothing}
      ${
        this.applied
          ? html`<p class="ep-alert ep-alert-success" role="status">Resources moved.</p>`
          : nothing
      }
      <div
        class="ep-panel"
        style="padding: var(--ep-space-4); margin-bottom: var(--ep-space-4); display: flex; gap: var(--ep-space-4); flex-wrap: wrap;"
      >
        <label class="ep-label"
          >Search
          <input
            class="ep-input ep-input-sm"
            type="search"
            .value=${this.search}
            placeholder="Filter by name or key"
            @input=${(event: InputEvent) => {
              if (!(event.currentTarget instanceof HTMLInputElement)) return;
              this.search = event.currentTarget.value;
              void this.load();
            }}
          />
        </label>
        <label class="ep-label"
          >Catalog
          <select
            class="ep-input ep-input-sm"
            .value=${this.catalogFilter}
            @change=${(event: Event) => {
              if (!(event.currentTarget instanceof HTMLSelectElement)) return;
              this.catalogFilter = event.currentTarget.value;
            }}
          >
            <option value="">All catalogs</option>
            ${this.catalogs.map(
              (catalog) => html`<option value=${catalog.key}>${catalog.name}</option>`,
            )}
          </select>
        </label>
      </div>

      ${this.renderResources(visible, authored)} ${this.renderSummary()}
    `;
  }

  private renderResources(visible: OrganiseResource[], authored: OrganiseCatalog[]) {
    if (this.busy && visible.length === 0) return html`<p class="ep-text-muted">Loading…</p>`;
    if (visible.length === 0) {
      return html`<p class="ep-text-muted">
        ${
          this.search || this.catalogFilter
            ? 'No resources match this filter.'
            : 'No relocatable resources. Only stencils, attributes and templates in authored catalogs can be moved.'
        }
      </p>`;
    }

    return html`<table class="ep-table">
      <thead>
        <tr>
          <th></th>
          <th>Resource</th>
          <th>Type</th>
          <th>Catalog</th>
          <th>Move to catalog</th>
          <th>New key</th>
        </tr>
      </thead>
      <tbody>
        ${visible.map((resource) => {
          const destination = this.selected.get(resource.id);
          const blockers = this.blockersFor(resource);

          return html`<tr>
            <td>
              <input
                type="checkbox"
                aria-label=${`Select ${resource.name}`}
                ?checked=${destination !== undefined}
                @change=${() => this.toggle(resource)}
              />
            </td>
            <td>
              ${resource.name}
              <br /><small class="ep-text-muted">${resource.key}</small>
            </td>
            <td>${resource.type}</td>
            <td>
              ${resource.catalogName}
              ${resource.note ? html`<br /><small class="ep-text-muted">${resource.note}</small>` : nothing}
            </td>
            <td>
              ${
                destination
                  ? html`<select
                      .value=${destination.catalog}
                      @change=${(event: Event) => {
                        if (!(event.currentTarget instanceof HTMLSelectElement)) return;
                        this.setDestination(resource.id, { catalog: event.currentTarget.value });
                      }}
                    >
                      <option value="">Stay in ${resource.catalogName}</option>
                      ${authored
                        .filter((catalog) => catalog.key !== resource.catalogKey)
                        .map(
                          (catalog) => html`<option value=${catalog.key}>${catalog.name}</option>`,
                        )}
                    </select>`
                  : nothing
              }
            </td>
            <td>
              ${
                destination
                  ? html`<input
                      type="text"
                      placeholder=${resource.key}
                      .value=${destination.key}
                      @input=${(event: InputEvent) => {
                        if (!(event.currentTarget instanceof HTMLInputElement)) return;
                        this.setDestination(resource.id, { key: event.currentTarget.value });
                      }}
                    />`
                  : nothing
              }
              ${blockers.map(
                (blocker) => html`<br /><small class="ep-text-error">${blocker.message}</small>`,
              )}
            </td>
          </tr>`;
        })}
      </tbody>
    </table>`;
  }

  private renderSummary() {
    if (this.selected.size === 0) return nothing;
    const plans = this.preview?.relocations ?? [];
    return html`<div
      class="ep-panel"
      style="padding: var(--ep-space-4); margin-top: var(--ep-space-4);"
    >
      <strong>${this.selected.size} selected</strong>
      <p>
        <button
          type="button"
          class="ep-btn ep-btn-outline"
          ?disabled=${this.busy}
          @click=${() => void this.runPreview()}
        >
          ${this.busy ? 'Checking…' : 'Preview'}
        </button>
      </p>
      ${
        this.preview
          ? html`
              <p>
                <strong
                  >${
                    this.preview.executable ? 'Ready to move' : 'Blocked — nothing will be moved'
                  }</strong
                >
              </p>
              ${plans.map(
                (plan) => html`<p>
                  ${plan.source.catalogKey}/${plan.source.key} →
                  <strong>${plan.target.catalogKey}/${plan.target.key}</strong> —
                  ${plan.mutableRewriteCount} reference(s) rewritten,
                  ${plan.immutableReferenceCount} published reference(s) resolve through the alias
                </p>`,
              )}
              ${this.batchBlockers.map(
                (blocker) => html`<p class="ep-text-error">${blocker.message}</p>`,
              )}
              ${(this.preview.warnings ?? []).map(
                (warning) => html`<p class="ep-text-warning">⚠ ${warning.message}</p>`,
              )}
              ${
                this.preview.executable
                  ? html`<button
                      type="button"
                      class="ep-btn ep-btn-primary"
                      ?disabled=${this.busy}
                      @click=${() => void this.apply()}
                    >
                      Move ${this.selected.size} resource(s)
                    </button>`
                  : nothing
              }
            `
          : nothing
      }
    </div>`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'ep-catalog-organise': CatalogOrganise;
  }
  interface Window {
    getCsrfToken?: () => string;
  }
}
