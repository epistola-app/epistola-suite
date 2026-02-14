/**
 * Derived indexes computed from a TemplateDocument.
 *
 * These provide O(1) parent/slot lookups that would otherwise require
 * traversing the entire node/slot graph.
 */

import type { NodeId, SlotId, TemplateDocument } from '../types/model.js'

/**
 * Derived indexes for fast lookups into the document graph.
 */
export interface DocumentIndexes {
  /** Maps a node ID to its parent slot ID (root node maps to undefined). */
  readonly parentSlotByNodeId: ReadonlyMap<NodeId, SlotId>
  /** Maps a node ID to the parent node that contains it (via a slot). */
  readonly parentNodeByNodeId: ReadonlyMap<NodeId, NodeId>
  /** Maps a slot ID to the node that owns it. */
  readonly nodeBySlotId: ReadonlyMap<SlotId, NodeId>
}

/**
 * Build derived indexes from a TemplateDocument.
 * Called once after every state change — O(N) in total nodes+slots.
 */
export function buildIndexes(doc: TemplateDocument): DocumentIndexes {
  const parentSlotByNodeId = new Map<NodeId, SlotId>()
  const parentNodeByNodeId = new Map<NodeId, NodeId>()
  const nodeBySlotId = new Map<SlotId, NodeId>()

  for (const slot of Object.values(doc.slots)) {
    nodeBySlotId.set(slot.id, slot.nodeId)
    for (const childId of slot.children) {
      parentSlotByNodeId.set(childId, slot.id)
      parentNodeByNodeId.set(childId, slot.nodeId)
    }
  }

  return { parentSlotByNodeId, parentNodeByNodeId, nodeBySlotId }
}

/**
 * Walk up the parent chain from a node to the root.
 * Returns the ordered path from root to the given node (inclusive).
 * Throws if a cycle is detected.
 */
export function getAncestorPath(
  nodeId: NodeId,
  indexes: DocumentIndexes,
  doc: TemplateDocument,
): NodeId[] {
  const path: NodeId[] = []
  const visited = new Set<NodeId>()
  let current: NodeId | undefined = nodeId

  while (current !== undefined) {
    if (visited.has(current)) {
      throw new Error(`Cycle detected: node ${current} appears twice in ancestor path`)
    }
    visited.add(current)
    path.unshift(current)
    current = indexes.parentNodeByNodeId.get(current)
  }

  // Verify the path starts at the document root
  if (path.length > 0 && path[0] !== doc.root) {
    throw new Error(
      `Node ${nodeId} is not connected to document root ${doc.root}`,
    )
  }

  return path
}

/**
 * Check whether `ancestorId` is an ancestor of `nodeId`.
 */
export function isAncestor(
  nodeId: NodeId,
  ancestorId: NodeId,
  indexes: DocumentIndexes,
): boolean {
  const visited = new Set<NodeId>()
  let current: NodeId | undefined = indexes.parentNodeByNodeId.get(nodeId)

  while (current !== undefined) {
    if (current === ancestorId) return true
    if (visited.has(current)) return false // cycle guard
    visited.add(current)
    current = indexes.parentNodeByNodeId.get(current)
  }

  return false
}
