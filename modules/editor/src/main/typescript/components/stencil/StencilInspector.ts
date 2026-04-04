/**
 * StencilInspector — Lit component for stencil-specific inspector controls.
 *
 * Shows contextual actions based on stencil state:
 * - "Publish as Stencil" when stencilId is null (new, unpublished)
 * - "Update Stencil" when stencilId is set (push changes back)
 * - "Detach from Stencil" when stencilId is set (remove reference, keep content)
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { Node } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { StencilCallbacks } from './types.js';
import { extractSubtree } from './extract-subtree.js';

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

  private get _stencilId(): string | null {
    return (this.node.props?.stencilId as string) ?? null;
  }

  private get _version(): number | null {
    return (this.node.props?.version as number) ?? null;
  }

  override render() {
    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Stencil</div>

        ${this._stencilId
          ? this._renderLinkedActions()
          : this._renderUnpublishedActions()}

        ${this._message
          ? html`<div class="inspector-field" style="color: var(--ep-color-success); font-size: var(--ep-font-size-sm);">${this._message}</div>`
          : nothing}
      </div>
    `;
  }

  private _renderUnpublishedActions() {
    if (!this.callbacks?.publishAsStencil) return nothing;

    return html`
      <div class="inspector-field">
        <button
          class="btn btn-sm btn-primary"
          style="width: 100%;"
          ?disabled=${this._busy}
          @click=${this._handlePublish}
        >
          ${this._busy ? 'Publishing...' : 'Publish as Stencil'}
        </button>
        <div style="font-size: var(--ep-font-size-xs); color: var(--ep-color-text-muted); margin-top: var(--ep-space-1);">
          Create a reusable stencil from this content.
        </div>
      </div>
    `;
  }

  private _renderLinkedActions() {
    return html`
      <div class="inspector-field">
        <div style="font-size: var(--ep-font-size-sm); margin-bottom: var(--ep-space-2);">
          <strong>${this._stencilId}</strong> v${this._version}
        </div>

        ${this.callbacks?.updateStencil
          ? html`<button
              class="btn btn-sm btn-outline"
              style="width: 100%; margin-bottom: var(--ep-space-2);"
              ?disabled=${this._busy}
              @click=${this._handleUpdate}
            >
              ${this._busy ? 'Updating...' : 'Update Stencil'}
            </button>`
          : nothing}

        <button
          class="btn btn-sm btn-ghost"
          style="width: 100%;"
          @click=${this._handleDetach}
        >
          Detach from Stencil
        </button>
      </div>
    `;
  }

  private async _handlePublish() {
    if (!this.callbacks?.publishAsStencil) return;
    this._busy = true;
    this._message = '';

    try {
      const name = prompt('Stencil name:');
      if (!name) { this._busy = false; return; }

      const slug = prompt('Stencil ID (slug):', name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''));
      if (!slug) { this._busy = false; return; }

      const content = extractSubtree(this.engine.doc, this.node.id);
      const result = await this.callbacks.publishAsStencil(slug, name, content);

      this.engine.dispatch({
        type: 'UpdateNodeProps',
        nodeId: this.node.id,
        props: {
          ...this.node.props,
          stencilId: result.stencilId,
          version: result.version,
        },
      });

      this._message = `Published as ${result.stencilId} v${result.version}`;
    } catch (e) {
      this._message = `Error: ${(e as Error).message}`;
    } finally {
      this._busy = false;
    }
  }

  private async _handleUpdate() {
    if (!this.callbacks?.updateStencil || !this._stencilId) return;
    this._busy = true;
    this._message = '';

    try {
      const content = extractSubtree(this.engine.doc, this.node.id);
      const result = await this.callbacks.updateStencil(this._stencilId, content);

      this._message = `Draft v${result.version} saved`;
    } catch (e) {
      this._message = `Error: ${(e as Error).message}`;
    } finally {
      this._busy = false;
    }
  }

  private _handleDetach() {
    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.node.id,
      props: {
        ...this.node.props,
        stencilId: null,
        version: null,
      },
    });
    this._message = 'Detached from stencil';
  }
}
