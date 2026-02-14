import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import { draggable, dropTargetForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import {
  attachInstruction,
  extractInstruction,
  type Instruction,
  type ItemMode,
} from '@atlaskit/pragmatic-drag-and-drop-hitbox/tree-item'
import type { TemplateDocument, NodeId, SlotId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import { isDragData, isPaletteDrag, isBlockDrag, type DragData } from '../dnd/types.js'
import { resolveDropOnBlockEdge, resolveDropInsideNode, canDropHere, type Edge } from '../dnd/drop-logic.js'

const INDENT_PER_LEVEL = 16

@customElement('epistola-tree')
export class EpistolaTree extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument
  @property({ attribute: false }) selectedNodeId: NodeId | null = null

  private _dndCleanup: (() => void) | null = null

  private _handleSelect(nodeId: NodeId) {
    this.engine?.selectNode(nodeId)
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
    const labelEls = this.querySelectorAll<HTMLElement>('.tree-node-label[data-node-id]')

    for (const labelEl of labelEls) {
      const nodeId = labelEl.dataset.nodeId as NodeId | undefined
      if (!nodeId) continue

      const node = this.doc.nodes[nodeId]
      if (!node) continue

      const isRoot = nodeId === this.doc.root

      // Drag source (skip root — can't drag the document root)
      if (!isRoot) {
        cleanups.push(draggable({
          element: labelEl,
          getInitialData: (): DragData => ({ source: 'block', nodeId, blockType: node.type }),
          onDragStart: () => labelEl.classList.add('dragging'),
          onDrop: () => labelEl.classList.remove('dragging'),
        }))
      }

      // Drop target
      const depth = this._getNodeDepth(nodeId)
      cleanups.push(dropTargetForElements({
        element: labelEl,
        getData: ({ input, element }) => {
          const hasSlots = node.slots.length > 0
          const hasChildren = hasSlots && node.slots.some(
            sId => (this.doc!.slots[sId]?.children.length ?? 0) > 0,
          )

          let mode: ItemMode
          let block: Instruction['type'][] | undefined

          if (isRoot) {
            // Root: only allow dropping inside (make-child), block reorder/reparent
            mode = hasChildren ? 'expanded' : 'standard'
            block = ['reorder-above', 'reorder-below', 'reparent']
          } else if (!hasSlots) {
            // Leaf node: no make-child zone
            mode = 'standard'
            block = ['make-child']
          } else if (hasChildren) {
            // Container with children: expanded mode shows make-child naturally
            mode = 'expanded'
          } else {
            // Empty container: standard mode allows all zones
            mode = 'standard'
          }

          return attachInstruction({}, {
            element,
            input,
            currentLevel: depth,
            indentPerLevel: INDENT_PER_LEVEL,
            mode,
            block,
          })
        },
        canDrop: ({ source }) => {
          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return false

          // Can't drop a block on itself
          if (isBlockDrag(dragData) && dragData.nodeId === nodeId) return false

          // Check at least one possible instruction could lead to a valid drop.
          // For reorder (above/below), the target is the parent slot of this node.
          // For make-child, the target is the node's first slot.
          const parentSlotId = this.engine!.indexes.parentSlotByNodeId.get(nodeId)
          const canReorder = parentSlotId != null &&
            canDropHere(dragData, parentSlotId, this.doc!, this.engine!.indexes, this.engine!.registry)

          const firstSlotId = node.slots.length > 0 ? node.slots[0] : undefined
          const canMakeChild = firstSlotId != null &&
            canDropHere(dragData, firstSlotId, this.doc!, this.engine!.indexes, this.engine!.registry)

          return canReorder || canMakeChild
        },
        onDragEnter: ({ self }) => {
          const instruction = extractInstruction(self.data)
          this._setDropIndicator(labelEl, instruction)
        },
        onDrag: ({ self }) => {
          const instruction = extractInstruction(self.data)
          this._setDropIndicator(labelEl, instruction)
        },
        onDragLeave: () => {
          labelEl.removeAttribute('data-drop-indicator')
        },
        onDrop: ({ self, source }) => {
          labelEl.removeAttribute('data-drop-indicator')

          const dragData = source.data as Record<string, unknown>
          if (!isDragData(dragData)) return

          const instruction = extractInstruction(self.data)
          if (!instruction || instruction.type === 'instruction-blocked') return

          this._resolveAndDrop(instruction, nodeId, dragData)
        },
      }))
    }

    return () => cleanups.forEach(fn => fn())
  }

  private _setDropIndicator(el: HTMLElement, instruction: Instruction | null) {
    if (!instruction || instruction.type === 'instruction-blocked') {
      el.removeAttribute('data-drop-indicator')
      return
    }
    // reparent maps to reorder-below visually
    const indicator = instruction.type === 'reparent' ? 'reorder-below' : instruction.type
    el.setAttribute('data-drop-indicator', indicator)
  }

  private _resolveAndDrop(instruction: Instruction, targetNodeId: NodeId, dragData: DragData) {
    if (!this.engine || !this.doc) return

    switch (instruction.type) {
      case 'reorder-above':
      case 'reorder-below': {
        const edge: Edge = instruction.type === 'reorder-above' ? 'top' : 'bottom'
        const loc = resolveDropOnBlockEdge(targetNodeId, edge, this.doc, this.engine.indexes)
        if (loc) this._handleDrop(dragData, loc.targetSlotId, loc.index)
        break
      }
      case 'make-child': {
        const loc = resolveDropInsideNode(targetNodeId, this.doc)
        if (loc) this._handleDrop(dragData, loc.targetSlotId, loc.index)
        break
      }
      case 'reparent': {
        // reparent at desiredLevel — walk up from targetNodeId to find the ancestor at that level,
        // then insert below it in its parent slot
        const ancestor = this._findAncestorAtLevel(targetNodeId, instruction.desiredLevel)
        if (ancestor) {
          const loc = resolveDropOnBlockEdge(ancestor, 'bottom', this.doc, this.engine.indexes)
          if (loc) this._handleDrop(dragData, loc.targetSlotId, loc.index)
        }
        break
      }
    }
  }

  private _findAncestorAtLevel(nodeId: NodeId, targetLevel: number): NodeId | null {
    let current = nodeId
    let currentLevel = this._getNodeDepth(current)

    while (currentLevel > targetLevel) {
      const parentNodeId = this.engine!.indexes.parentNodeByNodeId.get(current)
      if (!parentNodeId) return null
      current = parentNodeId
      currentLevel--
    }

    return current
  }

  private _getNodeDepth(nodeId: NodeId): number {
    let depth = 0
    let current: NodeId | undefined = nodeId

    while (current !== undefined) {
      const parent = this.engine!.indexes.parentNodeByNodeId.get(current)
      if (parent === undefined) break
      depth++
      current = parent
    }

    return depth
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
        style="padding-left: ${depth * INDENT_PER_LEVEL}px"
      >
        <div
          class="tree-node-label ${isSelected ? 'selected' : ''}"
          data-node-id=${nodeId}
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
