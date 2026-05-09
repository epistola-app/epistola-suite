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
import type { Node, NodeId } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { StencilCallbacks, StencilRef } from './types.js';
import * as stencilActions from './stencil-actions.js';
import { isStencil } from './node-types.js';
import { openParameterDefinitionsDialog } from './parameter-definitions-dialog.js';
import { openParameterBindingsDialog } from './parameter-bindings-dialog.js';

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
  @state() private _messageFading = false;
  @state() private _messageType: 'info' | 'success' | 'error' = 'info';
  @state() private _draftVersion: number | null = null;
  @state() private _latestVersion: number | null = null;

  private _unsubState?: () => void;
  private _messageTimer?: ReturnType<typeof setTimeout>;
  private _fadeTimer?: ReturnType<typeof setTimeout>;

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
    clearTimeout(this._messageTimer);
    clearTimeout(this._fadeTimer);
    super.disconnectedCallback();
  }

  // ── Computed state ──

  private get _stencilId(): string | null {
    return isStencil(this.node) ? this.node.props.stencilId : null;
  }

  private get _ref(): StencilRef | null {
    if (!isStencil(this.node)) return null;
    const { stencilId: id, catalogKey } = this.node.props;
    return id && catalogKey ? { stencilId: id, catalogKey } : null;
  }

  private get _version(): number | null {
    return isStencil(this.node) ? this.node.props.version : null;
  }

  private get _isDraft(): boolean {
    return isStencil(this.node) ? this.node.props.isDraft : false;
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

  /** Look up this node's parent node id, if any. Used to compute the outer
   *  scope when binding parameters (the expressions evaluate against the
   *  context surrounding the stencil, not its own params namespace). */
  private _parentNodeId(): NodeId | null {
    return this.engine.indexes.parentNodeByNodeId.get(this.node.id) ?? null;
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
              class=${`callout callout--${this._messageType}${this._messageFading ? ' is-fading' : ''}`}
            >
              ${this._message}
            </div>`
          : nothing}
      </div>
      ${this._renderParameters()}
    `;
  }

  // ── Parameters: per-instance bindings (popup) ──

  private _renderParameters() {
    if (!isStencil(this.node)) return nothing;
    const props = this.node.props;
    const schema = props.parameterSchemaSnapshot;
    if (!schema?.properties || Object.keys(schema.properties).length === 0) return nothing;

    const bindings = (props.parameterBindings ?? {}) as Record<string, string>;
    const declared = Object.keys(schema.properties);
    const required = new Set(schema.required ?? []);
    const boundCount = declared.filter((name) => (bindings[name] ?? '').trim().length > 0).length;
    const missingRequired = Array.from(required).some(
      (name) => (bindings[name] ?? '').trim().length === 0,
    );

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Parameters</div>
        <div
          style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); margin-bottom: var(--ep-space-2);"
        >
          ${boundCount} of ${declared.length}
          bound${missingRequired
            ? html`<span style="color: var(--ep-destructive, #dc2626); margin-left: 4px;"
                >· missing required</span
              >`
            : nothing}
        </div>
        <button
          class="btn btn-sm btn-outline stencil-btn"
          @click=${this._handleEditBindings}
          style="width: 100%;"
        >
          Configure parameters…
        </button>
      </div>
    `;
  }

  private _handleEditBindings = async () => {
    if (!isStencil(this.node)) return;
    const schema = this.node.props.parameterSchemaSnapshot;
    if (!schema) return;
    // Compute the available scope at the stencil node's *parent* (bindings
    // are evaluated against the outer context, not the stencil's own scope).
    const parentNodeId = this._parentNodeId();
    const fieldPaths = parentNodeId
      ? this.engine.getAvailableVariablesAt(parentNodeId)
      : this.engine.getAvailableVariablesAt(this.node.id);
    const getExampleData = () =>
      parentNodeId
        ? this.engine.getEvaluationContextAt(parentNodeId)
        : this.engine.getEvaluationContextAt(this.node.id);
    const result = await openParameterBindingsDialog({
      schema,
      initialBindings: (this.node.props.parameterBindings ?? {}) as Record<string, string>,
      initialAlias: this.node.props.paramsAlias ?? 'params',
      fieldPaths,
      getExampleData,
    });
    if (!result) return;

    const next = { ...this.node.props } as Record<string, unknown>;
    if (Object.keys(result.bindings).length > 0) {
      next.parameterBindings = result.bindings;
    } else {
      delete next.parameterBindings;
    }
    if (result.paramsAlias && result.paramsAlias !== 'params') {
      next.paramsAlias = result.paramsAlias;
    } else {
      delete next.paramsAlias;
    }
    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.node.id,
      props: next,
    });
  };

  // ── Locked: published version, not editing ──

  private _renderLocked() {
    return html`
      <div class="inspector-field stencil-actions">
        ${this._hasUpgrade
          ? html`<button
                class="btn btn-sm btn-primary stencil-btn"
                ?disabled=${this._busy}
                @click=${this._handleUpgrade}
              >
                ${this._busy ? 'Upgrading...' : `Upgrade to v${this._latestVersion}`}
              </button>
              <div class="callout callout--warning">
                Upgrade to v${this._latestVersion} before editing — editing the stale
                v${this._version} content here would overwrite the newer published version when you
                save.
              </div>`
          : this.callbacks?.startEditing
            ? html`<button
                class="btn btn-sm btn-primary stencil-btn"
                ?disabled=${this._busy}
                @click=${this._handleStartEditing}
              >
                ${this._busy ? 'Loading...' : 'Start Editing'}
              </button>`
            : nothing}

        <button class="btn btn-sm btn-outline stencil-btn" @click=${this._handleDetach}>
          Detach from Stencil
        </button>
      </div>
    `;
  }

  // ── Draft: editing mode ──

  private _renderDraft() {
    const schema = isStencil(this.node) ? this.node.props.parameterSchemaSnapshot : undefined;
    const paramCount = schema?.properties ? Object.keys(schema.properties).length : 0;

    return html`
      <div class="inspector-field" style="margin-bottom: var(--ep-space-2);">
        <button
          class="btn btn-sm btn-outline stencil-btn"
          @click=${this._handleEditDefinitions}
          style="width: 100%;"
        >
          Define parameters… (${paramCount})
        </button>
      </div>

      <div class="inspector-field stencil-actions">
        ${this.callbacks?.updateStencil
          ? html`<button
              class="btn btn-sm btn-primary stencil-btn"
              ?disabled=${this._busy}
              @click=${this._handleSaveDraft}
            >
              ${this._busy ? 'Saving...' : 'Save to Draft'}
            </button>`
          : nothing}
        ${this.callbacks?.publishDraft
          ? html`<button
              class="btn btn-sm btn-outline stencil-btn"
              ?disabled=${this._busy}
              @click=${this._handlePublishDraft}
            >
              ${this._busy ? 'Publishing...' : 'Publish Draft'}
            </button>`
          : nothing}
        ${this.callbacks?.getStencilVersion
          ? html`<button
              class="btn btn-sm btn-outline btn-outline-destructive stencil-btn"
              ?disabled=${this._busy}
              @click=${this._handleDiscard}
            >
              Discard Changes
            </button>`
          : nothing}

        <button class="btn btn-sm btn-outline stencil-btn" @click=${this._handleDetach}>
          Detach from Stencil
        </button>
      </div>
    `;
  }

  private _handleEditDefinitions = async () => {
    if (!isStencil(this.node)) return;
    const result = await openParameterDefinitionsDialog(this.node.props.parameterSchemaSnapshot);
    if (!result) return;
    // Empty-properties schemas drop the snapshot entirely so the stencil
    // reads as "no parameters".
    const hasProps = result.properties && Object.keys(result.properties).length > 0;
    const next = { ...this.node.props } as Record<string, unknown>;
    if (hasProps) next.parameterSchemaSnapshot = result;
    else delete next.parameterSchemaSnapshot;
    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.node.id,
      props: next,
    });
  };

  // ── Click handlers — thin wrappers around stencil-actions ──

  private _handleStartEditing = () =>
    this._run(async (ctx) => {
      const r = await stencilActions.startEditing(ctx);
      this._draftVersion = r.draftVersion;
      return 'Editing the stencil — your template overrides are preserved';
    }, 'info');

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
    this._setMessage('Converted to container', 'success');
  };

  private _setMessage(msg: string, type: 'info' | 'success' | 'error' = 'info') {
    clearTimeout(this._messageTimer);
    clearTimeout(this._fadeTimer);
    this._messageFading = false;
    this._message = msg;
    this._messageType = type;
    this._messageTimer = setTimeout(() => {
      this._messageFading = true;
      this._fadeTimer = setTimeout(() => {
        this._message = '';
        this._messageFading = false;
      }, 200);
    }, 4000);
  }

  /**
   * Common UI shell: set busy, run an action that returns its own success
   * message, surface error messages on failure, clear busy on completion.
   * Skips the call when callbacks aren't configured.
   */
  private async _run(
    action: (ctx: stencilActions.StencilActionContext) => Promise<string>,
    type: 'info' | 'success' = 'success',
  ): Promise<void> {
    const ctx = this._ctx();
    if (!ctx) return;
    this._busy = true;
    this._message = '';
    try {
      this._setMessage(await action(ctx), type);
    } catch (e) {
      this._setMessage(`Error: ${(e as Error).message}`, 'error');
    } finally {
      this._busy = false;
    }
  }
}
