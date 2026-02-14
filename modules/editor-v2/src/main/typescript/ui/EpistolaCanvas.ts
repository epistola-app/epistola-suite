import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import type { TemplateDocument, NodeId, SlotId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'

@customElement('epistola-canvas')
export class EpistolaCanvas extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument
  @property({ attribute: false }) selectedNodeId: NodeId | null = null

  private _handleSelect(e: Event, nodeId: NodeId) {
    e.stopPropagation()
    this.engine?.selectNode(nodeId)
  }

  private _handleCanvasClick() {
    this.engine?.selectNode(null)
  }

  override render() {
    if (!this.doc || !this.engine) {
      return html`<div class="editor-empty">No document</div>`
    }

    return html`
      <div class="epistola-canvas" @click=${this._handleCanvasClick}>
        <div class="canvas-page">
          ${this._renderNodeChildren(this.doc.root)}
        </div>
      </div>
    `
  }

  private _renderNodeChildren(nodeId: NodeId): unknown {
    const doc = this.doc!
    const node = doc.nodes[nodeId]
    if (!node) return nothing

    if (node.slots.length === 0) {
      // Leaf node
      return this._renderLeafNode(nodeId)
    }

    return html`
      ${node.slots.map(slotId => this._renderSlot(slotId))}
    `
  }

  private _renderSlot(slotId: SlotId): unknown {
    const doc = this.doc!
    const slot = doc.slots[slotId]
    if (!slot) return nothing

    const parentNode = doc.nodes[slot.nodeId]
    const isMultiSlot = parentNode && parentNode.slots.length > 1

    return html`
      <div
        class="canvas-slot ${slot.children.length === 0 ? 'empty' : ''}"
        data-slot-id=${slotId}
        data-slot-name=${slot.name}
      >
        ${slot.children.length === 0
          ? html`<span class="canvas-slot-hint">${isMultiSlot ? slot.name : 'Drop blocks here'}</span>`
          : slot.children.map(childId => this._renderBlock(childId))
        }
      </div>
    `
  }

  private _renderBlock(nodeId: NodeId): unknown {
    const doc = this.doc!
    const node = doc.nodes[nodeId]
    if (!node) return nothing

    const isSelected = this.selectedNodeId === nodeId
    const def = this.engine!.registry.get(node.type)
    const label = def?.label ?? node.type

    return html`
      <div
        class="canvas-block ${isSelected ? 'selected' : ''}"
        data-node-id=${nodeId}
        @click=${(e: Event) => this._handleSelect(e, nodeId)}
      >
        <!-- Block header -->
        <div class="canvas-block-header">
          <span class="canvas-block-label">${label}</span>
          <span class="canvas-block-id">${nodeId.slice(0, 6)}</span>
        </div>

        <!-- Block content area -->
        <div class="canvas-block-content ${node.type === 'text' ? 'text-type' : ''}">
          ${this._renderBlockContent(nodeId)}
        </div>
      </div>
    `
  }

  private _renderBlockContent(nodeId: NodeId): unknown {
    const doc = this.doc!
    const node = doc.nodes[nodeId]
    if (!node) return nothing

    // For leaf nodes with no slots, show a content placeholder
    if (node.slots.length === 0) {
      return this._renderLeafNode(nodeId)
    }

    // For container nodes, render their slot children
    if (node.type === 'columns') {
      return this._renderColumnsLayout(node.slots)
    }

    return html`
      ${node.slots.map(slotId => this._renderSlot(slotId))}
    `
  }

  private _renderLeafNode(nodeId: NodeId): unknown {
    const doc = this.doc!
    const node = doc.nodes[nodeId]
    if (!node) return nothing

    switch (node.type) {
      case 'text':
        return html`<div class="canvas-leaf-text">Text content</div>`
      case 'pagebreak':
        return html`<div class="canvas-pagebreak">
          <div class="canvas-pagebreak-line"></div>
          <span class="canvas-pagebreak-label">Page Break</span>
          <div class="canvas-pagebreak-line"></div>
        </div>`
      default:
        return html`<div class="canvas-leaf-default">${node.type}</div>`
    }
  }

  private _renderColumnsLayout(slotIds: SlotId[]): unknown {
    return html`
      <div class="canvas-columns">
        ${slotIds.map(slotId => html`
          <div class="canvas-column">
            ${this._renderSlot(slotId)}
          </div>
        `)}
      </div>
    `
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-canvas': EpistolaCanvas
  }
}
