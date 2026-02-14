import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import type { TemplateDocument, NodeId, SlotId } from '../types/model.js'
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
      return html`<div class="flex items-center justify-center h-full text-gray-400">No document</div>`
    }

    return html`
      <div class="epistola-canvas p-6" @click=${this._handleCanvasClick}>
        <div class="mx-auto max-w-3xl bg-white border border-gray-200 rounded-lg shadow-sm min-h-96 p-4">
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
        class="canvas-slot ${slot.children.length === 0 ? 'min-h-12 border border-dashed border-gray-300 rounded flex items-center justify-center' : ''}"
        data-slot-id=${slotId}
        data-slot-name=${slot.name}
      >
        ${slot.children.length === 0
          ? html`<span class="text-xs text-gray-400">${isMultiSlot ? slot.name : 'Drop blocks here'}</span>`
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
        class="canvas-block relative my-1 rounded transition-all
          ${isSelected
            ? 'ring-2 ring-blue-500 ring-offset-1'
            : 'hover:ring-1 hover:ring-gray-300'}"
        data-node-id=${nodeId}
        @click=${(e: Event) => this._handleSelect(e, nodeId)}
      >
        <!-- Block header -->
        <div class="flex items-center gap-1.5 px-2 py-1 bg-gray-50 rounded-t border border-gray-200 text-xs text-gray-500">
          <span class="font-medium">${label}</span>
          <span class="text-gray-300 ml-auto font-mono text-[10px]">${nodeId.slice(0, 6)}</span>
        </div>

        <!-- Block content area -->
        <div class="px-2 py-2 border border-t-0 border-gray-200 rounded-b ${node.type === 'text' ? 'min-h-8' : ''}">
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
        return html`<div class="text-sm text-gray-600 italic">Text content</div>`
      case 'pagebreak':
        return html`<div class="flex items-center gap-2 py-1">
          <div class="flex-1 border-t border-dashed border-gray-400"></div>
          <span class="text-xs text-gray-400">Page Break</span>
          <div class="flex-1 border-t border-dashed border-gray-400"></div>
        </div>`
      default:
        return html`<div class="text-xs text-gray-400">${node.type}</div>`
    }
  }

  private _renderColumnsLayout(slotIds: SlotId[]): unknown {
    return html`
      <div class="flex gap-2">
        ${slotIds.map(slotId => html`
          <div class="flex-1">
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
