/**
 * Pure functions for resolving drop locations and validating drops.
 *
 * These are framework-agnostic and testable without DOM or DnD library dependencies.
 */

import type { NodeId, SlotId, TemplateDocument } from '../types/index.js'
import type { DocumentIndexes } from '../engine/indexes.js'
import type { ComponentRegistry } from '../engine/registry.js'
import type { DragData } from './types.js'

export type Edge = 'top' | 'bottom'

export interface DropLocation {
  targetSlotId: SlotId
  index: number
}

/**
 * Resolve where to insert based on a drop on a block's edge.
 *
 * @param blockNodeId - the node the cursor is hovering over
 * @param edge - which edge (top or bottom) is closest
 * @param doc - the current document
 * @param indexes - derived indexes for parent lookups
 * @returns the slot and index to insert at, or null if the block has no parent
 */
export function resolveDropOnBlockEdge(
  blockNodeId: NodeId,
  edge: Edge,
  doc: TemplateDocument,
  indexes: DocumentIndexes,
): DropLocation | null {
  const parentSlotId = indexes.parentSlotByNodeId.get(blockNodeId)
  if (!parentSlotId) return null

  const parentSlot = doc.slots[parentSlotId]
  if (!parentSlot) return null

  const blockIndex = parentSlot.children.indexOf(blockNodeId)
  if (blockIndex === -1) return null

  return {
    targetSlotId: parentSlotId,
    index: edge === 'top' ? blockIndex : blockIndex + 1,
  }
}

/**
 * Resolve a drop on an empty slot.
 */
export function resolveDropOnEmptySlot(slotId: SlotId): DropLocation {
  return { targetSlotId: slotId, index: 0 }
}

/**
 * Resolve a "make-child" drop into a node's first slot, appending at the end.
 *
 * Used by tree DnD when the user drops onto the center of a container node,
 * indicating they want to move the dragged item inside that node.
 *
 * @param nodeId - the container node to drop into
 * @param doc - the current document
 * @returns the first slot with index at end, or null if the node has no slots
 */
export function resolveDropInsideNode(nodeId: NodeId, doc: TemplateDocument): DropLocation | null {
  const node = doc.nodes[nodeId]
  if (!node || node.slots.length === 0) return null

  const firstSlotId = node.slots[0]
  const firstSlot = doc.slots[firstSlotId]
  if (!firstSlot) return null

  return { targetSlotId: firstSlotId, index: firstSlot.children.length }
}

/**
 * Check whether a drag source can be dropped at a given location.
 *
 * Validates:
 * - Parent node type allows the dragged block type
 * - If moving an existing block, it's not being moved into its own descendant
 * - Block is not being moved to its own position (no-op)
 */
export function canDropHere(
  dragData: DragData,
  targetSlotId: SlotId,
  doc: TemplateDocument,
  indexes: DocumentIndexes,
  registry: ComponentRegistry,
): boolean {
  const targetSlot = doc.slots[targetSlotId]
  if (!targetSlot) return false

  const parentNode = doc.nodes[targetSlot.nodeId]
  if (!parentNode) return false

  // Check containment constraint
  if (!registry.canContain(parentNode.type, dragData.blockType)) {
    return false
  }

  // For block drags: prevent cycle (can't move into own descendant)
  if (dragData.source === 'block') {
    // Can't drop into itself
    if (targetSlot.nodeId === dragData.nodeId) return false

    // Can't drop into a descendant of itself
    if (isDescendant(targetSlot.nodeId, dragData.nodeId, indexes)) return false
  }

  return true
}

/**
 * Check whether `nodeId` is a descendant of `ancestorId`.
 * Walks up from nodeId looking for ancestorId.
 */
function isDescendant(nodeId: NodeId, ancestorId: NodeId, indexes: DocumentIndexes): boolean {
  const visited = new Set<NodeId>()
  let current: NodeId | undefined = indexes.parentNodeByNodeId.get(nodeId)

  while (current !== undefined) {
    if (current === ancestorId) return true
    if (visited.has(current)) return false
    visited.add(current)
    current = indexes.parentNodeByNodeId.get(current)
  }

  return false
}
