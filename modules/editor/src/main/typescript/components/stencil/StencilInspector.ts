/**
 * StencilInspector — Lit component for stencil-specific inspector controls.
 *
 * Shows contextual actions based on stencil state:
 * - Unlinked (no stencilId): "Publish as Stencil"
 * - Locked (stencilId set, isDraft=false): "Start Editing", "Detach"
 * - Editing draft (isDraft=true): "Save to Draft", "Discard Changes", "Detach"
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { Node } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { StencilCallbacks } from './types.js';
import { extractSubtree } from './extract-subtree.js';
import { reKeyContent } from './rekey-content.js';

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

  private get _isDraft(): boolean {
    return (this.node.props?.isDraft as boolean) ?? false;
  }

  private get _isUnlinked(): boolean {
    return this._stencilId === null;
  }

  private get _isLocked(): boolean {
    return this._stencilId !== null && !this._isDraft;
  }

  override render() {
    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Stencil</div>

        ${this._isUnlinked ? this._renderUnlinked() : nothing}
        ${this._isLocked ? this._renderLocked() : nothing}
        ${this._isDraft ? this._renderDraft() : nothing}

        ${this._message
          ? html`<div class="inspector-field" style="font-size: var(--ep-font-size-sm); margin-top: var(--ep-space-2); color: var(--ep-color-success, #16a34a);">${this._message}</div>`
          : nothing}
      </div>
    `;
  }

  // ── Unlinked: no stencilId ──

  private _renderUnlinked() {
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

  // ── Locked: published version, not editing ──

  private _renderLocked() {
    return html`
      <div class="inspector-field">
        <div style="font-size: var(--ep-font-size-sm); margin-bottom: var(--ep-space-2);">
          <strong>${this._stencilId}</strong> v${this._version}
          <span style="color: var(--ep-color-text-muted);">(locked)</span>
        </div>

        ${this.callbacks?.startEditing
          ? html`<button
              class="btn btn-sm btn-primary"
              style="width: 100%; margin-bottom: var(--ep-space-2);"
              ?disabled=${this._busy}
              @click=${this._handleStartEditing}
            >
              ${this._busy ? 'Loading...' : 'Start Editing'}
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

  // ── Actions ──

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
          isDraft: false,
        },
      });

      this._message = `Published as ${result.stencilId} v${result.version}`;
    } catch (e) {
      this._message = `Error: ${(e as Error).message}`;
    } finally {
      this._busy = false;
    }
  }

  private async _handleStartEditing() {
    if (!this.callbacks?.startEditing || !this._stencilId) return;
    this._busy = true;
    this._message = '';

    try {
      await this.callbacks.startEditing(this._stencilId);

      this.engine.dispatch({
        type: 'UpdateNodeProps',
        nodeId: this.node.id,
        props: {
          ...this.node.props,
          isDraft: true,
        },
      });

      this._message = 'Editing mode — changes are local until you save to draft';
    } catch (e) {
      this._message = `Error: ${(e as Error).message}`;
    } finally {
      this._busy = false;
    }
  }

  private async _handleSaveDraft() {
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

  private async _handlePublishDraft() {
    if (!this.callbacks?.publishDraft || !this._stencilId) return;
    this._busy = true;
    this._message = '';

    try {
      // Save current content to draft first
      if (this.callbacks.updateStencil) {
        const content = extractSubtree(this.engine.doc, this.node.id);
        await this.callbacks.updateStencil(this._stencilId, content);
      }

      // Publish the draft
      const result = await this.callbacks.publishDraft(this._stencilId);

      // Update node to reference the new published version and exit draft mode
      this.engine.dispatch({
        type: 'UpdateNodeProps',
        nodeId: this.node.id,
        props: {
          ...this.node.props,
          version: result.version,
          isDraft: false,
        },
      });

      this._message = `Published v${result.version}`;
    } catch (e) {
      this._message = `Error: ${(e as Error).message}`;
    } finally {
      this._busy = false;
    }
  }

  private async _handleDiscard() {
    if (!this.callbacks?.getStencilVersion || !this._stencilId || !this._version) return;
    this._busy = true;
    this._message = '';

    try {
      // Fetch the published version's content
      const versionInfo = await this.callbacks.getStencilVersion(this._stencilId, this._version);
      if (!versionInfo) {
        this._message = 'Could not load published version';
        return;
      }

      // Replace the stencil's children with the published content
      this._replaceContent(versionInfo.content);

      // Switch back to locked mode
      this.engine.dispatch({
        type: 'UpdateNodeProps',
        nodeId: this.node.id,
        props: {
          ...this.node.props,
          isDraft: false,
        },
      });

      this._message = 'Changes discarded — reverted to published version';
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
        isDraft: false,
      },
    });
    this._message = 'Detached from stencil';
  }

  /**
   * Replace the stencil's slot children with new content from a TemplateDocument.
   * Removes existing children, re-keys the new content, and inserts.
   */
  private _replaceContent(content: import('../../types/index.js').TemplateDocument) {
    const doc = this.engine.doc;
    const slotId = this.node.slots[0];
    if (!slotId) return;

    const slot = doc.slots[slotId];
    if (!slot) return;

    // Remove existing children (in reverse to maintain indices)
    for (let i = slot.children.length - 1; i >= 0; i--) {
      this.engine.dispatch({ type: 'RemoveNode', nodeId: slot.children[i] });
    }

    // Re-key and insert new content
    const reKeyed = reKeyContent(content);
    for (const newNode of reKeyed.nodes) {
      // Find which slot this node should be a child of
      const parentSlot = reKeyed.slots.find((s) =>
        s.children.includes(newNode.id),
      );
      if (!parentSlot) continue;

      // Only insert top-level nodes (children of the root slot)
      if (reKeyed.childNodeIds.includes(newNode.id)) {
        // Build the slots for this node
        const nodeSlots = reKeyed.slots.filter((s) => s.nodeId === newNode.id);
        const descendantNodes = reKeyed.nodes.filter((n) => n.id !== newNode.id);

        this.engine.dispatch({
          type: 'InsertNode',
          node: newNode,
          slots: [...nodeSlots, ...reKeyed.slots.filter((s) => !nodeSlots.includes(s) && s.nodeId !== newNode.id)],
          targetSlotId: slotId,
          index: -1,
          _restoreNodes: descendantNodes,
        });
      }
    }
  }
}
