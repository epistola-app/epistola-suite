import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import type { TemplateDocument, NodeId, SlotId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'

@customElement('epistola-tree')
export class EpistolaTree extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument
  @property({ attribute: false }) selectedNodeId: NodeId | null = null

  private _handleSelect(nodeId: NodeId) {
    this.engine?.selectNode(nodeId)
  }

  override render() {
    if (!this.doc || !this.engine) {
      return html`<div class="panel-empty">No document</div>`
    }

    return html`
      <div class="epistola-tree">
        <div class="panel-heading">Structure</div>
        ${this._renderNode(this.doc.root, 0)}
      </div>
    `
  }

  private _renderNode(nodeId: NodeId, depth: number): unknown {
    const doc = this.doc!
    const node = doc.nodes[nodeId]
    if (!node) return nothing

    const isSelected = this.selectedNodeId === nodeId
    const def = this.engine!.registry.get(node.type)
    const label = def?.label ?? node.type
    const isRoot = nodeId === doc.root

    return html`
      <div
        class="tree-node"
        data-node-id=${nodeId}
        style="padding-left: ${depth * 16}px"
      >
        <div
          class="tree-node-label ${isSelected ? 'selected' : ''}"
          @click=${() => this._handleSelect(nodeId)}
        >
          <span class="tree-node-icon ${isRoot ? 'root' : ''}">${isRoot ? '/' : '>'}</span>
          <span>${label}</span>
        </div>

        ${node.slots.map(slotId => this._renderSlotChildren(slotId, depth + 1))}
      </div>
    `
  }

  private _renderSlotChildren(slotId: SlotId, depth: number): unknown {
    const doc = this.doc!
    const slot = doc.slots[slotId]
    if (!slot || slot.children.length === 0) return nothing

    return html`
      ${slot.children.map(childId => this._renderNode(childId, depth))}
    `
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-tree': EpistolaTree
  }
}
