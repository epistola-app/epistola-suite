import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import { styleMap } from 'lit/directives/style-map.js'
import { draggable, dropTargetForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { attachClosestEdge, extractClosestEdge } from '@atlaskit/pragmatic-drag-and-drop-hitbox/closest-edge'
import type { TemplateDocument, NodeId, SlotId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import { isDragData, isBlockDrag, type DragData } from '../dnd/types.js'
import { resolveDropOnBlockEdge, canDropHere, type Edge } from '../dnd/drop-logic.js'
import { handleDrop } from '../dnd/drop-handler.js'
import '../ui/EpistolaTextEditor.js'
import '../components/table/TableCanvasBlock.js'

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

      // Drop target on each block (edge detection for inserting before/after in parent slot)
      cleanups.push(dropTargetForElements({
        element: blockEl,
        getData: ({ input, element }) => attachClosestEdge(
          { nodeId },
          { element, input, allowedEdges: ['top', 'bottom'] },
        ),
        canDrop: ({ source }) => {
          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return false

          // Can't drop a block on itself
          if (isBlockDrag(dragData) && dragData.nodeId === nodeId) return false

          // Resolve parent slot of this block via DOM
          const slotEl = blockEl.closest<HTMLElement>('[data-slot-id]')
          const parentSlotId = slotEl?.dataset.slotId as SlotId | undefined
          if (!parentSlotId) return false

          return canDropHere(dragData, parentSlotId, this.doc!, this.engine!.indexes, this.engine!.registry)
        },
        onDragEnter: ({ self, location }) => {
          // Only show edge indicator if this block is the innermost drop target.
          // A nested slot (inside this block) takes priority.
          if (location.current.dropTargets[0]?.element !== blockEl) return
          const edge = extractClosestEdge(self.data)
          if (edge === 'top' || edge === 'bottom') {
            blockEl.setAttribute('data-drop-edge', edge)
          }
        },
        onDrag: ({ self, location }) => {
          if (location.current.dropTargets[0]?.element !== blockEl) {
            blockEl.removeAttribute('data-drop-edge')
            return
          }
          const edge = extractClosestEdge(self.data)
          if (edge === 'top' || edge === 'bottom') {
            blockEl.setAttribute('data-drop-edge', edge)
          }
        },
        onDragLeave: () => {
          blockEl.removeAttribute('data-drop-edge')
        },
        onDrop: ({ self, source, location }) => {
          blockEl.removeAttribute('data-drop-edge')

          // If a deeper target (nested slot) is innermost, skip — it handles the drop
          if (location.current.dropTargets[0]?.element !== blockEl) return

          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return

          const edge = extractClosestEdge(self.data) as Edge | null
          if (!edge) return

          const dropLocation = resolveDropOnBlockEdge(nodeId, edge, this.doc!, this.engine!.indexes)
          if (!dropLocation) return

          this._handleDrop(dragData, dropLocation.targetSlotId, dropLocation.index)
        },
      }))
    }

    // Setup drop targets on ALL slots (empty and non-empty).
    // Empty slots accept drops at index 0.
    // Non-empty slots accept drops in the empty space below children (append).
    const slots = this.querySelectorAll<HTMLElement>('.canvas-slot[data-slot-id]')
    for (const slotEl of slots) {
      const slotId = slotEl.dataset.slotId as SlotId | undefined
      if (!slotId) continue

      const slot = this.doc.slots[slotId]
      if (!slot) continue

      cleanups.push(dropTargetForElements({
        element: slotEl,
        canDrop: ({ source }) => {
          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return false
          return canDropHere(dragData, slotId, this.doc!, this.engine!.indexes, this.engine!.registry)
        },
        onDragEnter: ({ location }) => {
          if (location.current.dropTargets[0]?.element === slotEl) {
            slotEl.classList.add('drag-over')
          }
        },
        onDrag: ({ location }) => {
          if (location.current.dropTargets[0]?.element === slotEl) {
            slotEl.classList.add('drag-over')
          } else {
            slotEl.classList.remove('drag-over')
          }
        },
        onDragLeave: () => {
          slotEl.classList.remove('drag-over')
        },
        onDrop: ({ source, location }) => {
          slotEl.classList.remove('drag-over')

          // Only handle if this slot is the innermost target.
          // If a child block is innermost, its edge handler takes care of it.
          if (location.current.dropTargets[0]?.element !== slotEl) return

          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return

          // Append at end of slot
          const currentSlot = this.doc!.slots[slotId]
          const index = currentSlot ? currentSlot.children.length : 0
          this._handleDrop(dragData, slotId, index)
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
    handleDrop(this.engine, dragData, targetSlotId, index)
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  override render() {
    if (!this.doc || !this.engine) {
      return html`<div class="editor-empty">No document</div>`
    }

    const pageSettings = this.engine.resolvedPageSettings
    const pageStyle: Record<string, string> = {}
    if (pageSettings.backgroundColor) {
      pageStyle.backgroundColor = pageSettings.backgroundColor
    }

    return html`
      <div class="epistola-canvas" @click=${this._handleCanvasClick}>
        <div class="canvas-page" style=${styleMap(pageStyle)}>
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

    // Resolve styles through the full cascade, filtered by component's applicable styles
    const resolvedStyles = this.engine!.getResolvedNodeStyles(nodeId)
    const applicableStyles = def?.applicableStyles
    const filteredStyles = filterByApplicableStyles(resolvedStyles, applicableStyles)
    const contentStyle = toStyleMap(filteredStyles)

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
        <div
          class="canvas-block-content ${node.type === 'text' ? 'text-type' : ''}"
          style=${styleMap(contentStyle)}
        >
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
      return this._renderColumnsLayout(node)
    }

    if (node.type === 'table') {
      return html`
        <table-canvas-block
          .node=${node}
          .doc=${this.doc!}
          .engine=${this.engine!}
          .renderSlotCallback=${(slotId: SlotId) => this._renderSlot(slotId)}
          .selectedNodeId=${this.selectedNodeId}
        ></table-canvas-block>
      `
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
      case 'text': {
        const resolvedStyles = this.engine!.getResolvedNodeStyles(nodeId)
        const def = this.engine!.registry.get(node.type)
        const applicableStyles = def?.applicableStyles
        const filteredStyles = filterByApplicableStyles(resolvedStyles, applicableStyles)
        const textStyles = toStyleMap(filteredStyles)
        return html`
          <epistola-text-editor
            .nodeId=${nodeId}
            .content=${node.props?.content ?? null}
            .resolvedStyles=${textStyles}
            .engine=${this.engine}
            .isSelected=${this.selectedNodeId === nodeId}
          ></epistola-text-editor>
        `
      }
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

  private _renderColumnsLayout(node: import('../types/index.js').Node): unknown {
    const props = node.props ?? {}
    const columnSizes = (props.columnSizes as number[] | undefined) ?? []
    const gap = (props.gap as number | undefined) ?? 0
    const gapStyle = gap > 0 ? `${gap}pt` : '0'

    return html`
      <div class="canvas-columns" style="gap: ${gapStyle}">
        ${node.slots.map((slotId, i) => {
          const flex = columnSizes[i] ?? 1
          return html`
            <div class="canvas-column" style="flex: ${flex}">
              ${this._renderSlot(slotId)}
            </div>
          `
        })}
      </div>
    `
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Filter a resolved styles object to only include properties the component supports. */
function filterByApplicableStyles(
  styles: Record<string, unknown>,
  applicableStyles: 'all' | string[] | undefined,
): Record<string, unknown> {
  if (!applicableStyles || applicableStyles === 'all') return styles
  if (applicableStyles.length === 0) return {}
  const result: Record<string, unknown> = {}
  for (const key of applicableStyles) {
    if (key in styles) result[key] = styles[key]
  }
  return result
}

/** Convert a camelCase key to kebab-case CSS property name. */
function camelToKebab(key: string): string {
  return key.replace(/[A-Z]/g, m => `-${m.toLowerCase()}`)
}

/**
 * Convert a resolved styles object to a styleMap-compatible record.
 * Handles both scalar values and spacing objects (padding/margin).
 */
function toStyleMap(styles: Record<string, unknown>): Record<string, string> {
  const result: Record<string, string> = {}
  for (const [key, value] of Object.entries(styles)) {
    if (value == null) continue

    // Spacing objects with top/right/bottom/left
    if (typeof value === 'object' && value !== null && 'top' in value) {
      const obj = value as Record<string, unknown>
      const cssKey = camelToKebab(key)
      result[cssKey] = `${obj.top} ${obj.right} ${obj.bottom} ${obj.left}`
      continue
    }

    result[camelToKebab(key)] = String(value)
  }
  return result
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-canvas': EpistolaCanvas
  }
}
