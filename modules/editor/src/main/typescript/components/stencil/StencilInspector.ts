/**
 * StencilInspector — Lit component for stencil-specific inspector controls.
 *
 * Pure UI: renders contextual buttons based on stencil state, owns
 * busy-spinner / message state, and routes user clicks to functions in
 * `stencil-actions.ts`. All business logic (backend orchestration, content
 * swap, fill preservation, draft-version recovery) lives in that module.
 *
 * State shown:
 * - Unlinked (no `stencilId`): no controls (publishing flows through other UI).
 * - Locked (`stencilId` set, `isDraft=false`): "Upgrade" (when one is available)
 *   or "Start Editing" + "Detach".
 * - Editing draft (`isDraft=true`): "Save to Draft", "Publish Draft",
 *   "Discard Changes", "Detach".
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { Node } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { StencilCallbacks, StencilRef } from './types.js';
import * as stencilActions from './stencil-actions.js';

@customElement('stencil-inspector')
export class StencilInspector extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) node!: Node;
  @property({ attribute: false }) engine!: EditorEngine;
  @property({ attribute: false }) callbacks!: StencilCallbacks | null;

  @state() private _busy = false;
  @state() private _message = '';
  @state() private _draftVersion: number | null = null;
  @state() private _latestVersion: number | null = null;

  private _unsubState?: () => void;

  override connectedCallback(): void {
    super.connectedCallback();
    this._readUpgradeState();
    this._checkForUpgrades();
    // If we're mounting on a stencil that's already in draft mode (e.g. user
    // reloaded the page mid-edit, or selected the stencil after a previous
    // Start Editing), discover the backend draft's version so Publish/Save
    // know which version to target.
    if (this._isDraft && this._draftVersion === null && this.callbacks) {
      void this._refreshDraftVersion();
    }
    this._unsubState = this.engine.events.on('component-state:change', ({ key }) => {
      if (key === 'stencil:upgrades') {
        this._readUpgradeState();
      }
    });
  }

  override disconnectedCallback(): void {
    this._unsubState?.();
    super.disconnectedCallback();
  }

  // ── Computed state ──

  private get _stencilId(): string | null {
    return (this.node.props?.stencilId as string) ?? null;
  }

  private get _ref(): StencilRef | null {
    const id = this._stencilId;
    const catalog = this.node.props?.catalogKey as string | null;
    return id && catalog ? { stencilId: id, catalogKey: catalog } : null;
  }

  private get _version(): number | null {
    return (this.node.props?.version as number) ?? null;
  }

  private get _isDraft(): boolean {
    return (this.node.props?.isDraft as boolean) ?? false;
  }

  private get _isLocked(): boolean {
    return this._stencilId !== null && !this._isDraft;
  }

  private get _hasUpgrade(): boolean {
    return (
      this._latestVersion != null &&
      this._version != null &&
      this._latestVersion > this._version &&
      !this._isDraft
    );
  }

  /** Build the action context. Returns null when callbacks aren't configured. */
  private _ctx(): stencilActions.StencilActionContext | null {
    if (!this.callbacks) return null;
    return {
      engine: this.engine,
      callbacks: this.callbacks,
      stencilNodeId: this.node.id,
    };
  }

  // ── Background queries ──

  /** Check for upgrades for this specific stencil on every selection. */
  private async _checkForUpgrades() {
    if (!this._ref || !this._version || this._isDraft) return;
    const ctx = this._ctx();
    if (!ctx) return;
    const latest = await stencilActions.findLatestPublishedVersion(ctx);
    if (latest != null && latest > this._version) {
      const current =
        this.engine.getComponentState<Record<string, number>>('stencil:upgrades') ?? {};
      this.engine.setComponentState('stencil:upgrades', {
        ...current,
        [this._stencilId!]: latest,
      });
    }
  }

  private _readUpgradeState() {
    const upgrades = this.engine.getComponentState<Record<string, number>>('stencil:upgrades');
    if (upgrades && this._stencilId) {
      this._latestVersion = upgrades[this._stencilId] ?? null;
    }
  }

  /** Resolve the current backend draft version for this stencil. */
  private async _refreshDraftVersion() {
    const ctx = this._ctx();
    if (!ctx) return;
    this._draftVersion = await stencilActions.loadDraftVersion(ctx);
  }

  // ── Render ──

  override render() {
    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Stencil</div>

        ${this._isLocked ? this._renderLocked() : nothing}
        ${this._isDraft ? this._renderDraft() : nothing}
        ${this._message
          ? html`<div
              class="inspector-field"
              style="font-size: var(--ep-font-size-sm); margin-top: var(--ep-space-2); color: var(--ep-color-success, #16a34a);"
            >
              ${this._message}
            </div>`
          : nothing}
      </div>
    `;
  }

  // ── Locked: published version, not editing ──

  private _renderLocked() {
    return html`
      <div class="inspector-field">
        <div style="font-size: var(--ep-font-size-sm); margin-bottom: var(--ep-space-2);">
          <strong>${this._stencilId}</strong> v${this._version}
          <span style="color: var(--ep-color-text-muted);">(locked)</span>
        </div>

        ${this._hasUpgrade
          ? html`<button
                class="btn btn-sm btn-primary"
                style="width: 100%; margin-bottom: var(--ep-space-2);"
                ?disabled=${this._busy}
                @click=${this._handleUpgrade}
              >
                ${this._busy ? 'Upgrading...' : `Upgrade to v${this._latestVersion}`}
              </button>
              <div
                class="inspector-field-hint"
                style="font-size: var(--ep-font-size-xs); color: var(--ep-color-text-muted); margin-bottom: var(--ep-space-2);"
              >
                Upgrade to v${this._latestVersion} before editing — editing the stale
                v${this._version} content here would overwrite the newer published version when you
                save.
              </div>`
          : this.callbacks?.startEditing
            ? html`<button
                class="btn btn-sm btn-primary"
                style="width: 100%; margin-bottom: var(--ep-space-2);"
                ?disabled=${this._busy}
                @click=${this._handleStartEditing}
              >
                ${this._busy ? 'Loading...' : 'Start Editing'}
              </button>`
            : nothing}

        <button class="btn btn-sm btn-ghost" style="width: 100%;" @click=${this._handleDetach}>
          Detach from Stencil
        </button>
      </div>
    `;
  }

  // ── Draft: editing mode ──

  private _renderDraft() {
    return html`
      <div class="inspector-field">
        <div style="font-size: var(--ep-font-size-sm); margin-bottom: var(--ep-space-2);">
          <strong>${this._stencilId}</strong>
          <span style="color: var(--ep-amber-700, #b45309);">editing draft</span>
        </div>

        ${this.callbacks?.updateStencil
          ? html`<button
              class="btn btn-sm btn-primary"
              style="width: 100%; margin-bottom: var(--ep-space-2);"
              ?disabled=${this._busy}
              @click=${this._handleSaveDraft}
            >
              ${this._busy ? 'Saving...' : 'Save to Draft'}
            </button>`
          : nothing}
        ${this.callbacks?.publishDraft
          ? html`<button
              class="btn btn-sm btn-outline"
              style="width: 100%; margin-bottom: var(--ep-space-2);"
              ?disabled=${this._busy}
              @click=${this._handlePublishDraft}
            >
              ${this._busy ? 'Publishing...' : 'Publish Draft'}
            </button>`
          : nothing}
        ${this.callbacks?.getStencilVersion
          ? html`<button
              class="btn btn-sm btn-outline"
              style="width: 100%; margin-bottom: var(--ep-space-2);"
              ?disabled=${this._busy}
              @click=${this._handleDiscard}
            >
              Discard Changes
            </button>`
          : nothing}

        <button class="btn btn-sm btn-ghost" style="width: 100%;" @click=${this._handleDetach}>
          Detach from Stencil
        </button>
      </div>
    `;
  }

  // ── Click handlers — thin wrappers around stencil-actions ──

  private _handleStartEditing = () =>
    this._run(async (ctx) => {
      const r = await stencilActions.startEditing(ctx);
      this._draftVersion = r.draftVersion;
      return 'Editing the stencil — your template overrides are preserved';
    });

  private _handleSaveDraft = () =>
    this._run(async (ctx) => {
      const r = await stencilActions.saveDraft(ctx);
      this._draftVersion = r.version;
      return `Draft v${r.version} saved`;
    });

  private _handlePublishDraft = () =>
    this._run(async (ctx) => {
      let draftVersion = this._draftVersion;
      if (draftVersion == null) {
        draftVersion = await stencilActions.loadDraftVersion(ctx);
        if (draftVersion != null) this._draftVersion = draftVersion;
      }
      if (draftVersion == null) {
        throw new Error('Could not locate the draft version. Reload the editor and try again.');
      }
      const r = await stencilActions.publishDraft(ctx, draftVersion);
      return `Published v${r.version}`;
    });

  private _handleDiscard = () =>
    this._run(async (ctx) => {
      if (this._version == null) throw new Error('No published version to revert to');
      await stencilActions.discard(ctx, this._version);
      return 'Changes discarded — reverted to published version';
    });

  private _handleUpgrade = () =>
    this._run(async (ctx) => {
      if (this._latestVersion == null) throw new Error('No upgrade available');
      const r = await stencilActions.upgrade(ctx, this._latestVersion);
      this._latestVersion = null;
      return `Upgraded to v${r.version}`;
    });

  private _handleDetach = () => {
    // Detach works even without callbacks — it's a pure local operation.
    stencilActions.detach({
      engine: this.engine,
      callbacks: this.callbacks ?? ({} as StencilCallbacks),
      stencilNodeId: this.node.id,
    });
    this._message = 'Converted to container';
  };

  /**
   * Common UI shell: set busy, run an action that returns its own success
   * message, surface error messages on failure, clear busy on completion.
   * Skips the call when callbacks aren't configured.
   */
  private async _run(
    action: (ctx: stencilActions.StencilActionContext) => Promise<string>,
  ): Promise<void> {
    const ctx = this._ctx();
    if (!ctx) return;
    this._busy = true;
    this._message = '';
    try {
      this._message = await action(ctx);
    } catch (e) {
      this._message = `Error: ${(e as Error).message}`;
    } finally {
      this._busy = false;
    }
  }
}
