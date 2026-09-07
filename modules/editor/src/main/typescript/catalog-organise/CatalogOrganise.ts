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
 * Deep-linkable via `?resource=<type>:<catalog>/<key>`, repeatable, so anything that notices a
 * misplaced resource can hand off here with it already selected.
 */
@customElement('ep-catalog-organise')
export class CatalogOrganise extends LitElement {
  // Light DOM: the app's stylesheet is global, and a shadow root would cut this off from it.
  protected createRenderRoot() {
    // Lit appends its content to the render root rather than replacing what is already in it, so
    // the pre-upgrade placeholder this element wraps would otherwise stay on the page above the
    // component that replaced it. Clearing here rather than in `connectedCallback` keeps it to one
    // pass: this runs once, immediately before the first render.
    this.replaceChildren();
    return this;
  }

  @property({ attribute: 'data-base-url' }) baseUrl = '';
  @property({ attribute: 'data-preselected' }) preselected = '';
  /** Focus on one resource: `<type>:<catalog>/<key>`. Empty renders the browser. */
  @property({ attribute: 'data-single' }) single = '';

  @state() private catalogs: OrganiseCatalog[] = [];
  @state() private resources: OrganiseResource[] = [];
  @state() private search = '';
  @state() private catalogFilter = '';
  /** Destination for every selected row that has not overridden it. */
  @state() private sharedDestination = '';
  @state() private selected = new Map<string, Destination>();
  @state() private preview?: RelocationPreview;
  @state() private busy = false;
  @state() private error = '';
  /** What the last successful move did, for a message that says more than "done". */
  @state() private applied?: { count: number; destination?: string };

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

  private deepLinkApplied = false;

  /**
   * Runs once, after the first load: a deep link names resources that must exist to be selected.
   *
   * Tracked with a flag rather than "nothing is selected yet" — a completed move clears the
   * selection, and re-applying the link there would put the form back looking as though nothing
   * had happened.
   */
  private applyDeepLink(): void {
    const deepLinked = this.single || this.preselected;
    if (!deepLinked || this.deepLinkApplied) return;
    this.deepLinkApplied = true;
    const wanted = new Set(deepLinked.split(',').filter(Boolean));
    for (const resource of this.resources) {
      if (wanted.has(resource.id))
        this.selected.set(resource.id, { catalog: '', key: '', overridden: false });
    }
    if (this.selected.size > 0) this.requestUpdate();
  }

  private toggle(resource: OrganiseResource): void {
    if (this.selected.has(resource.id)) this.selected.delete(resource.id);
    else this.selected.set(resource.id, { catalog: '', key: '', overridden: false });
    this.invalidatePreview();
  }

  /** Selects everything currently visible, or clears the selection when all of it is selected. */
  private toggleAll(visible: OrganiseResource[]): void {
    const allSelected =
      visible.length > 0 && visible.every((resource) => this.selected.has(resource.id));
    if (allSelected) visible.forEach((resource) => this.selected.delete(resource.id));
    else {
      visible.forEach((resource) => {
        if (!this.selected.has(resource.id)) {
          this.selected.set(resource.id, { catalog: '', key: '', overridden: false });
        }
      });
    }
    this.invalidatePreview();
  }

  /**
   * The destination a row is actually going to: its own when it overrode, the shared one otherwise.
   * Blank means "stay where it is", which is what makes a rename-only relocation expressible.
   */
  private destinationFor(destination: Destination): string {
    return destination.overridden ? destination.catalog : this.sharedDestination;
  }

  private invalidatePreview(): void {
    this.preview = undefined;
    this.applied = undefined;
    this.requestUpdate();
  }

  /** Says what moved and where, so the outcome is legible without re-reading the table. */
  private renderApplied(applied: { count: number; destination?: string }) {
    const catalog = this.catalogs.find((candidate) => candidate.key === applied.destination);
    const where = catalog ? ` to ${catalog.name}` : '';
    const what = applied.count === 1 ? '1 resource' : `${applied.count} resources`;
    return html`<p class="alert alert-success" role="status" data-testid="organise-applied">
      Moved ${what}${where}. Old addresses keep resolving, so existing references still work.
    </p>`;
  }

  private setDestination(id: string, patch: Partial<Destination>): void {
    const current = this.selected.get(id);
    if (!current) return;
    this.selected.set(id, { ...current, ...patch });
    this.invalidatePreview();
  }

  /**
   * Starts an override from wherever the row was already heading, so revealing the control never
   * silently changes the destination — only the fact that the shared one no longer applies.
   */
  private beginOverride(resource: OrganiseResource): void {
    const current = this.selected.get(resource.id);
    if (!current) return;
    this.selected.set(resource.id, {
      ...current,
      catalog: this.sharedDestination,
      overridden: true,
    });
    this.invalidatePreview();
  }

  private endOverride(resource: OrganiseResource): void {
    const current = this.selected.get(resource.id);
    if (!current) return;
    this.selected.set(resource.id, { ...current, catalog: '', key: '', overridden: false });
    this.invalidatePreview();
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
          targetCatalog: this.destinationFor(destination) || resource.catalogKey,
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
      const moved = this.preview.relocations;
      const destinations = new Set(moved.map((plan) => plan.target.catalogKey));
      await this.post('execute', this.preview.planFingerprint);
      this.selected = new Map();
      this.preview = undefined;
      this.sharedDestination = '';
      this.applied = {
        count: moved.length,
        destination: destinations.size === 1 ? [...destinations][0] : undefined,
      };
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

  /** The resource this element is focused on, or undefined while loading or if it is gone. */
  private get focused(): OrganiseResource | undefined {
    return this.single ? this.resources.find((resource) => resource.id === this.single) : undefined;
  }

  /**
   * One resource, one destination. No table, no filters: everything here is about the resource the
   * caller already chose, which is what makes this worth a separate surface rather than the browser
   * with a row preselected.
   */
  private renderSingle() {
    // The move is done; showing the form again — now pointing at the new catalog — reads as though
    // it had not been.
    if (this.applied) return nothing;
    const resource = this.focused;
    if (this.busy && !resource) return html`<p class="text-muted">Loading resource…</p>`;
    if (!resource) {
      return html`<p class="alert alert-error" data-testid="organise-single-missing">
        That resource cannot be moved. It may have been deleted, or it lives in a catalog this
        tenant does not author.
      </p>`;
    }

    const destination = this.selected.get(resource.id);
    const authored = this.catalogs.filter((catalog) => catalog.type === 'authored');

    return html`
      <div class="ep-panel" style="padding: var(--ep-space-4); margin-bottom: var(--ep-space-4);">
        <p style="margin-top: 0;">
          <strong>${resource.name}</strong>
          <span class="text-muted">· ${resource.type} · currently in ${resource.catalogName}</span>
        </p>
        ${resource.note ? html`<p class="text-muted">${resource.note}</p>` : nothing}
        <label class="ep-label"
          >Move to
          <select
            class="ep-input ep-input-sm"
            data-testid="organise-single-destination"
            .value=${destination?.catalog ?? ''}
            @change=${(event: Event) => {
              if (!(event.currentTarget instanceof HTMLSelectElement)) return;
              this.setDestination(resource.id, {
                catalog: event.currentTarget.value,
                overridden: true,
              });
            }}
          >
            <option value="">Stay in ${resource.catalogName}</option>
            ${authored
              .filter((catalog) => catalog.key !== resource.catalogKey)
              .map((catalog) => html`<option value=${catalog.key}>${catalog.name}</option>`)}
          </select>
        </label>
        <label class="ep-label"
          >New key (optional)
          <input
            class="ep-input ep-input-sm"
            type="text"
            placeholder=${resource.key}
            .value=${destination?.key ?? ''}
            @input=${(event: InputEvent) => {
              if (!(event.currentTarget instanceof HTMLInputElement)) return;
              this.setDestination(resource.id, {
                key: event.currentTarget.value,
                overridden: true,
              });
            }}
          />
        </label>
      </div>
      ${this.renderSummary()}
    `;
  }

  protected render() {
    const visible = this.catalogFilter
      ? this.resources.filter((resource) => resource.catalogKey === this.catalogFilter)
      : this.resources;
    const authored = this.catalogs.filter((catalog) => catalog.type === 'authored');

    const banners = html`
      ${this.error ? html`<p class="alert alert-error" role="alert">${this.error}</p>` : nothing}
      ${this.applied ? this.renderApplied(this.applied) : nothing}
    `;
    if (this.single) return html`${banners} ${this.renderSingle()}`;

    return html`
      ${banners}
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

      ${this.renderDestinationBar(authored)} ${this.renderResources(visible, authored)}
      ${this.renderSummary()}
    `;
  }

  /**
   * One destination for the whole selection. Rows that overrode it are called out here rather than
   * only in their own row, so the count always explains itself without scanning the table.
   */
  private renderDestinationBar(authored: OrganiseCatalog[]) {
    if (this.selected.size === 0) return nothing;
    const overridden = [...this.selected.values()].filter(
      (destination) => destination.overridden,
    ).length;

    return html`<div
      class="ep-panel"
      style="padding: var(--ep-space-4); margin-bottom: var(--ep-space-4); display: flex; gap: var(--ep-space-4); align-items: center; flex-wrap: wrap;"
    >
      <label class="ep-label"
        >Move selected to
        <select
          class="ep-input ep-input-sm"
          data-testid="organise-shared-destination"
          .value=${this.sharedDestination}
          @change=${(event: Event) => {
            if (!(event.currentTarget instanceof HTMLSelectElement)) return;
            this.sharedDestination = event.currentTarget.value;
            this.invalidatePreview();
          }}
        >
          <option value="">Leave where they are</option>
          ${authored.map((catalog) => html`<option value=${catalog.key}>${catalog.name}</option>`)}
        </select>
      </label>
      <span class="text-muted">
        ${this.selected.size}
        selected${overridden > 0 ? html` · ${overridden} with their own destination` : nothing}
      </span>
    </div>`;
  }

  private renderResources(visible: OrganiseResource[], authored: OrganiseCatalog[]) {
    if (this.busy && visible.length === 0) return html`<p class="text-muted">Loading…</p>`;
    if (visible.length === 0) {
      return html`<p class="text-muted">
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
          <th>
            <input
              type="checkbox"
              aria-label="Select all shown"
              data-testid="organise-select-all"
              ?checked=${visible.length > 0 && visible.every((resource) => this.selected.has(resource.id))}
              @change=${() => this.toggleAll(visible)}
            />
          </th>
          <th>Resource</th>
          <th>Type</th>
          <th>Catalog</th>
          <th>Destination</th>
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
              <br /><small class="text-muted">${resource.key}</small>
            </td>
            <td>${resource.type}</td>
            <td>
              ${resource.catalogName}
              ${resource.note ? html`<br /><small class="text-muted">${resource.note}</small>` : nothing}
            </td>
            <td>
              ${destination ? this.renderDestinationCell(resource, destination, authored) : nothing}
              ${blockers.map(
                (blocker) =>
                  html`<br /><small class="alert alert-error">${blocker.message}</small>`,
              )}
            </td>
          </tr>`;
        })}
      </tbody>
    </table>`;
  }

  /**
   * A selected row shows where it is heading in words, and offers to take its own destination. The
   * override is opt-in so the common case — everything going to one place — needs no per-row
   * interaction at all, and a rename stays reachable without leaving the page.
   */
  private renderDestinationCell(
    resource: OrganiseResource,
    destination: Destination,
    authored: OrganiseCatalog[],
  ) {
    if (!destination.overridden) {
      const target = authored.find((catalog) => catalog.key === this.sharedDestination);
      return html`
        ${
          target && target.key !== resource.catalogKey
            ? html`→ <strong>${target.name}</strong>`
            : html`<span class="text-muted">stays in ${resource.catalogName}</span>`
        }
        <button
          type="button"
          class="ep-btn ep-btn-sm ep-btn-ghost"
          data-testid=${`organise-override-${resource.id}`}
          @click=${() => this.beginOverride(resource)}
        >
          Choose separately
        </button>
      `;
    }

    return html`
      <select
        class="ep-input ep-input-sm"
        .value=${destination.catalog}
        @change=${(event: Event) => {
          if (!(event.currentTarget instanceof HTMLSelectElement)) return;
          this.setDestination(resource.id, { catalog: event.currentTarget.value });
        }}
      >
        <option value="">Stay in ${resource.catalogName}</option>
        ${authored
          .filter((catalog) => catalog.key !== resource.catalogKey)
          .map((catalog) => html`<option value=${catalog.key}>${catalog.name}</option>`)}
      </select>
      <input
        class="ep-input ep-input-sm"
        type="text"
        aria-label=${`New key for ${resource.name}`}
        placeholder=${resource.key}
        .value=${destination.key}
        @input=${(event: InputEvent) => {
          if (!(event.currentTarget instanceof HTMLInputElement)) return;
          this.setDestination(resource.id, { key: event.currentTarget.value });
        }}
      />
      <button
        type="button"
        class="ep-btn ep-btn-sm ep-btn-ghost"
        @click=${() => this.endOverride(resource)}
      >
        Follow the rest
      </button>
    `;
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
                (blocker) => html`<p class="alert alert-error">${blocker.message}</p>`,
              )}
              ${(this.preview.warnings ?? []).map(
                (warning) => html`<p class="alert alert-warning">⚠ ${warning.message}</p>`,
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
