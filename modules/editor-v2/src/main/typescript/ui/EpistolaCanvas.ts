import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import { draggable, dropTargetForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { attachClosestEdge, extractClosestEdge } from '@atlaskit/pragmatic-drag-and-drop-hitbox/closest-edge'
import type { TemplateDocument, NodeId, SlotId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import { isDragData, isPaletteDrag, isBlockDrag, type DragData } from '../dnd/types.js'
import { resolveDropOnBlockEdge, resolveDropOnEmptySlot, canDropHere, type Edge } from '../dnd/drop-logic.js'

@customElement('epistola-canvas')
export class EpistolaCanvas extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument
  @property({ attribute: false }) selectedNodeId: NodeId | null = null

  private _dndCleanup: (() => void) | null = null

  private _handleSelect(e: Event, nodeId: NodeId) {
    e.stopPropagation()
    this.engine?.selectNode(nodeId)
  }

  private _handleCanvasClick() {
    this.engine?.selectNode(null)
  }

  override updated() {
    this._dndCleanup?.()
    this._dndCleanup = this._setupDnD()
  }

  override disconnectedCallback() {
    this._dndCleanup?.()
    this._dndCleanup = null
    super.disconnectedCallback()
  }

  // ---------------------------------------------------------------------------
  // DnD setup
  // ---------------------------------------------------------------------------

  private _setupDnD(): (() => void) | null {
    if (!this.engine || !this.doc) return null

    const cleanups: (() => void)[] = []

    // Setup drag sources on canvas blocks (skip root)
    const blocks = this.querySelectorAll<HTMLElement>('.canvas-block[data-node-id]')
    for (const blockEl of blocks) {
      const nodeId = blockEl.dataset.nodeId as NodeId | undefined
      if (!nodeId || nodeId === this.doc.root) continue

      const node = this.doc.nodes[nodeId]
      if (!node) continue

      // Drag source
      cleanups.push(draggable({
        element: blockEl,
        dragHandle: blockEl.querySelector<HTMLElement>('.canvas-block-header') ?? blockEl,
        getInitialData: (): DragData => ({ source: 'block', nodeId, blockType: node.type }),
        onDragStart: () => blockEl.classList.add('dragging'),
        onDrop: () => blockEl.classList.remove('dragging'),
      }))

      // Drop target on each block (edge detection)
      cleanups.push(dropTargetForElements({
        element: blockEl,
        getData: ({ input, element }) => attachClosestEdge(
          { nodeId },
          { element, input, allowedEdges: ['top', 'bottom'] },
        ),
        canDrop: ({ source }) => {
          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return false

          // Resolve parent slot of this block via DOM
          const slotEl = blockEl.closest<HTMLElement>('[data-slot-id]')
          const slotId = slotEl?.dataset.slotId as SlotId | undefined
          if (!slotId) return false

          return canDropHere(dragData, slotId, this.doc!, this.engine!.indexes, this.engine!.registry)
        },
        onDragEnter: ({ self }) => {
          const edge = extractClosestEdge(self.data)
          if (edge === 'top' || edge === 'bottom') {
            blockEl.setAttribute('data-drop-edge', edge)
          }
        },
        onDrag: ({ self }) => {
          const edge = extractClosestEdge(self.data)
          if (edge === 'top' || edge === 'bottom') {
            blockEl.setAttribute('data-drop-edge', edge)
          }
        },
        onDragLeave: () => {
          blockEl.removeAttribute('data-drop-edge')
        },
        onDrop: ({ self, source }) => {
          blockEl.removeAttribute('data-drop-edge')

          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return

          const edge = extractClosestEdge(self.data) as Edge | null
          if (!edge) return

          const location = resolveDropOnBlockEdge(nodeId, edge, this.doc!, this.engine!.indexes)
          if (!location) return

          this._handleDrop(dragData, location.targetSlotId, location.index)
        },
      }))
    }

    // Setup drop targets on empty slots
    const slots = this.querySelectorAll<HTMLElement>('.canvas-slot[data-slot-id]')
    for (const slotEl of slots) {
      const slotId = slotEl.dataset.slotId as SlotId | undefined
      if (!slotId) continue

      const slot = this.doc.slots[slotId]
      if (!slot) continue

      // Only make empty slots direct drop targets (non-empty slots are handled by block edges)
      if (slot.children.length > 0) continue

      cleanups.push(dropTargetForElements({
        element: slotEl,
        canDrop: ({ source }) => {
          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return false
          return canDropHere(dragData, slotId, this.doc!, this.engine!.indexes, this.engine!.registry)
        },
        onDragEnter: () => slotEl.classList.add('drag-over'),
        onDragLeave: () => slotEl.classList.remove('drag-over'),
        onDrop: ({ source }) => {
          slotEl.classList.remove('drag-over')

          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return

          const location = resolveDropOnEmptySlot(slotId)
          this._handleDrop(dragData, location.targetSlotId, location.index)
        },
      }))
    }

    return () => cleanups.forEach(fn => fn())
  }

  // ---------------------------------------------------------------------------
  // Drop handler
  // ---------------------------------------------------------------------------

  private _handleDrop(dragData: DragData, targetSlotId: SlotId, index: number) {
    if (!this.engine) return

    if (isPaletteDrag(dragData)) {
      const { node, slots } = this.engine.registry.createNode(dragData.blockType)
      this.engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId, index })
      this.engine.selectNode(node.id)
    } else if (isBlockDrag(dragData)) {
      this.engine.dispatch({ type: 'MoveNode', nodeId: dragData.nodeId, targetSlotId, index })
    }
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

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
