// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * StencilInspector — Lit component for stencil-specific inspector controls.
 *
 * Pure UI: renders contextual buttons based on the instance's state, owns
 * busy-spinner / message state, and routes clicks to `stencil-actions.ts`.
 *
 * A template uses a stencil by reference: the instance shows the stencil's
 * working copy. States shown:
 * - Unlinked (no `stencilKey`): says so; only parameters, if any.
 * - Using the stencil: "Edit stencil" when its working copy can be edited
 *   here (else why not), and "Detach from stencil".
 * - Editing the stencil in place: "Define parameters…", "Save stencil",
 *   "Save and mark ready", "Stop editing", "Detach from stencil".
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { Node, NodeId } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { StencilCallbacks, StencilWorkingCopy } from './types.js';
import * as stencilActions from './stencil-actions.js';
import { isLinkedStencil, isStencil } from './node-types.js';
import { STENCIL_EDITING_STATE_KEY, type StencilEditingState } from './editing-state.js';
import { openParameterDefinitionsDialog } from './parameter-definitions-dialog.js';
import { openParameterBindingsDialog } from './parameter-bindings-dialog.js';
import { STENCIL_BINDING_ERRORS_KEY, type BindingErrors } from './binding-errors.js';
import { bindingsDeclaredBySchema, missingRequiredParameters } from './parameter-requirements.js';

@customElement('stencil-inspector')
export class StencilInspector extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) node!: Node;
  @property({ attribute: false }) engine!: EditorEngine;
  @property({ attribute: false }) callbacks!: StencilCallbacks | null;
  @property({ attribute: false }) editing!: StencilEditingState;

  @state() private _busy = false;
  @state() private _message = '';
  @state() private _messageFading = false;
  @state() private _messageType: 'info' | 'success' | 'error' = 'info';
  /** The working copy, read once per instance to know its name and whether it can be edited. */
  @state() private _copy: StencilWorkingCopy | null = null;
  @state() private _copyMissing = false;

  private _copyFor: string | null = null;
  private _unsubState?: () => void;
  private _messageTimer?: ReturnType<typeof setTimeout>;
  private _fadeTimer?: ReturnType<typeof setTimeout>;

  override connectedCallback(): void {
    super.connectedCallback();
    this._unsubState = this.engine.events.on('component-state:change', ({ key }) => {
      if (key === STENCIL_EDITING_STATE_KEY) this.requestUpdate();
    });
  }

  override disconnectedCallback(): void {
    this._unsubState?.();
    clearTimeout(this._messageTimer);
    clearTimeout(this._fadeTimer);
    super.disconnectedCallback();
  }

  override willUpdate(): void {
    this._loadWorkingCopy();
  }

  // ── Computed state ──

  private get _isLinked(): boolean {
    return isLinkedStencil(this.node);
  }

  private get _isEditing(): boolean {
    return this._isLinked && (this.editing?.isEditing(this.node.id) ?? false);
  }

  /** Build the action context. Returns null when callbacks aren't configured. */
  private _ctx(): stencilActions.StencilActionContext | null {
    if (!this.callbacks) return null;
    return {
      engine: this.engine,
      callbacks: this.callbacks,
      editing: this.editing,
      stencilNodeId: this.node.id,
    };
  }

  private _loadWorkingCopy() {
    const ctx = this._ctx();
    const ref = ctx ? stencilActions.stencilRef(ctx) : null;
    const key = ref ? `${this.node.id}|${ref.catalogKey}/${ref.stencilKey}` : null;
    if (key === this._copyFor) return;
    this._copyFor = key;
    this._copy = null;
    this._copyMissing = false;
    if (!ref || !this.callbacks) return;
    this.callbacks
      .getStencil(ref)
      .then((copy) => {
        if (this._copyFor !== key) return;
        this._copy = copy;
        this._copyMissing = copy === null;
      })
      .catch(() => {
        if (this._copyFor === key) this._copyMissing = true;
      });
  }

  /** Look up this node's parent node id: bindings evaluate in the scope around the stencil. */
  private _parentNodeId(): NodeId | null {
    return this.engine.indexes.parentNodeByNodeId.get(this.node.id) ?? null;
  }

  // ── Render ──

  override render() {
    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Stencil</div>
        ${
          !this._isLinked
            ? html`<div class="stencil-hint" data-testid="stencil-unlinked">
                This block is not linked to a stencil.
              </div>`
            : this._isEditing
              ? this._renderEditing()
              : this._renderUsing()
        }
        ${
          this._message
            ? html`<div
                class=${`callout callout--${this._messageType}${this._messageFading ? ' is-fading' : ''}`}
                data-testid="stencil-message"
              >
                ${this._message}
              </div>`
            : nothing
        }
      </div>
      ${this._renderParameters()}
    `;
  }

  // ── Using the stencil (content locked) ──

  private _renderUsing() {
    return html`
      ${this._copy ? html`<div class="stencil-hint">${this._copy.name}</div>` : nothing}
      <div class="inspector-field stencil-actions">
        ${this._renderEditAffordance()}
        <button
          class="ep-btn ep-btn-outline ep-btn-sm stencil-btn"
          data-testid="stencil-detach"
          @click=${this._handleDetach}
        >
          Detach from Stencil
        </button>
      </div>
    `;
  }

  /** "Edit stencil" when it can be used here; otherwise the reason it is not offered. */
  private _renderEditAffordance() {
    if (!this.callbacks?.saveStencil) {
      return html`<div class="stencil-hint">
        This editor cannot save stencils, so the stencil's content is read-only here.
      </div>`;
    }
    if (this._copyMissing) {
      return html`<div class="callout callout--warning" data-testid="stencil-missing">
        This stencil no longer exists. Detach it to keep its content in this template, or remove it.
      </div>`;
    }
    if (!this._copy) return nothing; // still loading
    if (!this._copy.editable) {
      return html`<div class="stencil-hint" data-testid="stencil-read-only">
        This stencil belongs to the read-only catalog '${this._copy.ref.catalogKey}', so it cannot
        be edited here. Detach it to customise its content in this template.
      </div>`;
    }
    return html`<button
      class="ep-btn ep-btn-primary ep-btn-sm stencil-btn"
      data-testid="stencil-start-editing"
      ?disabled=${this._busy}
      @click=${this._handleStartEditing}
    >
      ${this._busy ? 'Loading...' : 'Edit Stencil'}
    </button>`;
  }

  // ── Editing the stencil in place ──

  private _renderEditing() {
    const schema = isStencil(this.node) ? this.node.props.parameterSchemaSnapshot : undefined;
    const paramCount = schema?.properties ? Object.keys(schema.properties).length : 0;
    const missingRequired = schema
      ? missingRequiredParameters(
          schema,
          isStencil(this.node) ? (this.node.props.parameterBindings ?? {}) : {},
        )
      : [];

    return html`
      <div class="stencil-hint">
        You are editing the stencil itself. Saving changes it in every template that uses it.
      </div>
      <div class="inspector-field" style="margin-bottom: var(--ep-space-2);">
        <button
          class="ep-btn ep-btn-outline ep-btn-sm stencil-btn"
          data-testid="stencil-define-parameters"
          @click=${this._handleEditDefinitions}
          style="width: 100%;"
        >
          Define parameters… (${paramCount})
        </button>
      </div>

      <div class="inspector-field stencil-actions">
        <button
          class="ep-btn ep-btn-primary ep-btn-sm stencil-btn"
          data-testid="stencil-save"
          ?disabled=${this._busy}
          @click=${this._handleSave}
        >
          ${this._busy ? 'Saving...' : 'Save Stencil'}
        </button>
        ${
          this.callbacks?.markStencilReady
            ? html`<button
                class="ep-btn ep-btn-outline ep-btn-sm stencil-btn"
                data-testid="stencil-save-and-mark-ready"
                ?disabled=${this._busy || missingRequired.length > 0}
                @click=${this._handleSaveAndMarkReady}
              >
                Save and Mark Ready
              </button>`
            : nothing
        }
        <button
          class="ep-btn ep-btn-outline ep-btn-sm stencil-btn"
          data-testid="stencil-stop-editing"
          ?disabled=${this._busy}
          @click=${this._handleStopEditing}
        >
          Stop Editing
        </button>
        <button
          class="ep-btn ep-btn-outline ep-btn-sm stencil-btn"
          data-testid="stencil-detach"
          @click=${this._handleDetach}
        >
          Detach from Stencil
        </button>
        <div class="stencil-hint">
          Stop editing shows the stencil as it was last saved; changes you did not save are dropped.
        </div>
        ${
          missingRequired.length > 0 && this.callbacks?.markStencilReady
            ? html`<div class="callout callout--warning">
                Configure required ${missingRequired.length === 1 ? 'parameter' : 'parameters'}
                before marking the stencil ready: ${missingRequired.join(', ')}. You can still save
                it.
              </div>`
            : nothing
        }
      </div>
    `;
  }

  // ── Parameters: per-instance bindings (popup) ──

  private _renderParameters() {
    if (!isStencil(this.node)) return nothing;
    const props = this.node.props;
    const schema = props.parameterSchemaSnapshot;
    if (!schema?.properties || Object.keys(schema.properties).length === 0) return nothing;

    const bindings = props.parameterBindings ?? {};
    const declared = Object.keys(schema.properties);
    const boundCount = declared.filter((name) => (bindings[name] ?? '').trim().length > 0).length;
    const missingRequired = missingRequiredParameters(schema, bindings).length > 0;

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Parameters</div>
        <div
          style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); margin-bottom: var(--ep-space-2);"
        >
          ${boundCount} of ${declared.length}
          bound${
            missingRequired
              ? html`<span style="color: var(--ep-destructive, #dc2626); margin-left: 4px;"
                  >· missing required</span
                >`
              : nothing
          }
        </div>
        <button
          class="ep-btn ep-btn-outline ep-btn-sm stencil-btn"
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
    // Bindings are evaluated against the context around the stencil, not its own scope.
    const parentNodeId = this._parentNodeId();
    const at = parentNodeId ?? this.node.id;
    const fieldPaths = this.engine.getAvailableVariablesAt(at);
    const getExampleData = () => this.engine.getEvaluationContextAt(at);
    // Retrieve any stored binding errors from a failed save and clear them
    const bindingErrors = this.engine.getComponentState<BindingErrors>(STENCIL_BINDING_ERRORS_KEY);
    this.engine.setComponentState(STENCIL_BINDING_ERRORS_KEY, null);

    const result = await openParameterBindingsDialog({
      schema,
      initialBindings: this.node.props.parameterBindings ?? {},
      initialAlias: this.node.props.paramsAlias ?? 'params',
      fieldPaths,
      getExampleData,
      locale: this.engine.locale,
      bindingErrors,
    });
    if (!result) return;

    const next = { ...this.node.props } as Record<string, unknown>;
    if (Object.keys(result.bindings).length > 0) next.parameterBindings = result.bindings;
    else delete next.parameterBindings;
    if (result.paramsAlias && result.paramsAlias !== 'params')
      next.paramsAlias = result.paramsAlias;
    else delete next.paramsAlias;
    this.engine.dispatch({ type: 'UpdateNodeProps', nodeId: this.node.id, props: next });
  };

  private _handleEditDefinitions = async () => {
    if (!isStencil(this.node)) return;
    const result = await openParameterDefinitionsDialog(this.node.props.parameterSchemaSnapshot);
    if (!result) return;
    // Empty-properties schemas drop the schema entirely so the stencil reads as "no parameters".
    const hasProps = result.properties && Object.keys(result.properties).length > 0;
    const next = { ...this.node.props } as Record<string, unknown>;
    if (hasProps) next.parameterSchemaSnapshot = result;
    else delete next.parameterSchemaSnapshot;
    const retainedBindings = bindingsDeclaredBySchema(
      hasProps ? result : undefined,
      this.node.props.parameterBindings ?? {},
    );
    if (Object.keys(retainedBindings).length > 0) next.parameterBindings = retainedBindings;
    else delete next.parameterBindings;
    this.engine.dispatch({ type: 'UpdateNodeProps', nodeId: this.node.id, props: next });
  };

  // ── Click handlers — thin wrappers around stencil-actions ──

  private _handleStartEditing = () =>
    this._run(async (ctx) => {
      await stencilActions.startEditing(ctx);
      return 'Editing the stencil — your fills in this template are kept';
    }, 'info');

  private _handleSave = () =>
    this._run(async (ctx) => {
      await stencilActions.saveStencil(ctx);
      return 'Stencil saved';
    });

  private _handleSaveAndMarkReady = () =>
    this._run(async (ctx) => {
      await stencilActions.saveAndMarkReady(ctx);
      return 'Stencil saved and marked ready';
    });

  private _handleStopEditing = () =>
    this._run(async (ctx) => {
      await stencilActions.stopEditing(ctx);
      return 'Stopped editing — showing the saved stencil';
    }, 'info');

  private _handleDetach = () => {
    // Detach works even without callbacks — it's a pure local operation.
    stencilActions.detach({
      engine: this.engine,
      stencilNodeId: this.node.id,
      editing: this.editing,
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
