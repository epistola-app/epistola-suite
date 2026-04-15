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
  @state() private _draftVersion: number | null = null;
  @state() private _latestVersion: number | null = null;

  private _unsubState?: () => void;

  override connectedCallback(): void {
    super.connectedCallback();
    this._readUpgradeState();
    this._checkForUpgrades();
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

  /** Check for upgrades for this specific stencil on every selection. */
  private async _checkForUpgrades() {
    if (!this.callbacks?.listVersions || !this._stencilId || !this._version || this._isDraft)
      return;

    try {
      const versions = await this.callbacks.listVersions(this._stencilId, this._catalogKey);
      const latestPublished = versions
        .filter((v) => v.status === 'published')
        .toSorted((a, b) => b.version - a.version)[0];

      if (latestPublished && latestPublished.version > this._version) {
        const current =
          this.engine.getComponentState<Record<string, number>>('stencil:upgrades') ?? {};
        this.engine.setComponentState('stencil:upgrades', {
          ...current,
          [this._stencilId]: latestPublished.version,
        });
      }
    } catch {
      // Silently fail — upgrade check is non-critical
    }
  }

  private _readUpgradeState() {
    const upgrades = this.engine.getComponentState<Record<string, number>>('stencil:upgrades');
    if (upgrades && this._stencilId) {
      this._latestVersion = upgrades[this._stencilId] ?? null;
    }
  }

  private get _hasUpgrade(): boolean {
    return (
      this._latestVersion != null &&
      this._version != null &&
      this._latestVersion > this._version &&
      !this._isDraft
    );
  }

  private get _stencilId(): string | null {
    return (this.node.props?.stencilId as string) ?? null;
  }

  private get _version(): number | null {
    return (this.node.props?.version as number) ?? null;
  }

  private get _isDraft(): boolean {
    return (this.node.props?.isDraft as boolean) ?? false;
  }

  private get _catalogKey(): string | undefined {
    return (this.node.props?.catalogKey as string) ?? undefined;
  }

  private get _isLocked(): boolean {
    return this._stencilId !== null && !this._isDraft;
  }

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
            </button>`
          : nothing}
        ${this.callbacks?.startEditing
          ? html`<button
              class="btn btn-sm ${this._hasUpgrade ? 'btn-outline' : 'btn-primary'}"
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

  // ── Actions ──

  private async _handleStartEditing() {
    if (!this.callbacks?.startEditing || !this._stencilId) return;
    this._busy = true;
    this._message = '';

    try {
      const result = await this.callbacks.startEditing(this._stencilId, this._catalogKey);
      this._draftVersion = result.draftVersion;

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

  private async _handleUpgrade() {
    if (!this.callbacks?.getStencilVersion || !this._stencilId || !this._latestVersion) return;
    this._busy = true;
    this._message = '';

    try {
      const versionInfo = await this.callbacks.getStencilVersion(
        this._stencilId,
        this._latestVersion,
        this._catalogKey,
      );
      if (!versionInfo) {
        this._message = 'Could not load the new version';
        return;
      }

      // Replace current content with the new version
      this._replaceContent(versionInfo.content);

      // Update version prop
      this.engine.dispatch({
        type: 'UpdateNodeProps',
        nodeId: this.node.id,
        props: {
          ...this.node.props,
          version: this._latestVersion,
        },
      });

      this._latestVersion = null; // No more upgrade available
      this._message = `Upgraded to v${versionInfo.version}`;
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
      const result = await this.callbacks.updateStencil(this._stencilId, content, this._catalogKey);
      this._draftVersion = result.version;

      this._message = `Draft v${result.version} saved`;
    } catch (e) {
      this._message = `Error: ${(e as Error).message}`;
    } finally {
      this._busy = false;
    }
  }

  private async _handlePublishDraft() {
    const draftVersion = this._draftVersion ?? this._version;
    if (!this.callbacks?.publishDraft || !this._stencilId || !draftVersion) return;
    this._busy = true;
    this._message = '';

    try {
      // Save current content to draft first
      if (this.callbacks.updateStencil) {
        const content = extractSubtree(this.engine.doc, this.node.id);
        await this.callbacks.updateStencil(this._stencilId, content, this._catalogKey);
      }

      // Publish the draft
      const result = await this.callbacks.publishDraft(
        this._stencilId,
        draftVersion,
        this._catalogKey,
      );

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
      const versionInfo = await this.callbacks.getStencilVersion(
        this._stencilId,
        this._version,
        this._catalogKey,
      );
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
      type: 'ReplaceNode',
      nodeId: this.node.id,
      newType: 'container',
      newProps: {},
    });
    this._message = 'Converted to container';
  }

  /**
   * Replace the stencil's slot children with new content from a TemplateDocument.
   * Removes existing children, re-keys the new content, and inserts.
   */
  private _replaceContent(content: import('../../types/index.js').TemplateDocument) {
    const slotId = this.node.slots[0];
    if (!slotId) return;

    // Remove existing children (re-read doc after each removal since it's immutable)
    while (true) {
      const currentSlot = this.engine.doc.slots[slotId];
      if (!currentSlot || currentSlot.children.length === 0) break;
      this.engine.dispatch({ type: 'RemoveNode', nodeId: currentSlot.children[0] });
    }

    // Re-key the new content and insert each top-level node
    const reKeyed = reKeyContent(content);

    // Build a set of node IDs that are descendants of each top-level node
    const nodeById = new Map(reKeyed.nodes.map((n) => [n.id as string, n]));
    const slotById = new Map(reKeyed.slots.map((s) => [s.id as string, s]));

    for (const childId of reKeyed.childNodeIds) {
      const childNode = nodeById.get(childId as string);
      if (!childNode) continue;

      // Collect this node's descendant nodes and slots
      const descNodes: import('../../types/index.js').Node[] = [];
      const descSlots: import('../../types/index.js').Slot[] = [];

      function collectDescendants(nodeId: import('../../types/index.js').NodeId) {
        const node = nodeById.get(nodeId as string);
        if (!node) return;
        for (const sid of node.slots) {
          const slot = slotById.get(sid as string);
          if (slot) {
            descSlots.push(slot);
            for (const cid of slot.children) {
              const child = nodeById.get(cid as string);
              if (child) {
                descNodes.push(child);
                collectDescendants(cid);
              }
            }
          }
        }
      }

      collectDescendants(childId);

      // Get this node's own slots
      const ownSlots = childNode.slots
        .map((sid) => slotById.get(sid as string))
        .filter(Boolean) as import('../../types/index.js').Slot[];

      this.engine.dispatch({
        type: 'InsertNode',
        node: childNode,
        slots: [...ownSlots, ...descSlots],
        targetSlotId: slotId,
        index: -1,
        _restoreNodes: descNodes.length > 0 ? descNodes : undefined,
      });
    }
  }
}
