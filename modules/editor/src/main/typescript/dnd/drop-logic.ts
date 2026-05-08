/**
 * Pure functions for resolving drop locations and validating drops.
 *
 * These are framework-agnostic and testable without DOM or DnD library dependencies.
 */

import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';
import type { DocumentIndexes } from '../engine/indexes.js';
import { type ComponentRegistry, isAnchoredPageBlock } from '../engine/registry.js';
import { computeAncestorScope } from '../components/stencil/ancestry.js';
import { isSlotLocked } from '../engine/locks.js';
import { STENCIL_TYPE } from '../components/stencil/constants.js';
import { PLACEHOLDER_TYPE } from '../components/placeholder/constants.js';
import { stencilId as readStencilId } from '../components/stencil/node-types.js';
import type { DragData } from './types.js';

export type Edge = 'top' | 'bottom';

export interface DropLocation {
  targetSlotId: SlotId;
  index: number;
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
  const parentSlotId = indexes.parentSlotByNodeId.get(blockNodeId);
  if (!parentSlotId) return null;

  const parentSlot = doc.slots[parentSlotId];
  if (!parentSlot) return null;

  const blockIndex = parentSlot.children.indexOf(blockNodeId);
  if (blockIndex === -1) return null;

  return {
    targetSlotId: parentSlotId,
    index: edge === 'top' ? blockIndex : blockIndex + 1,
  };
}

/**
 * Resolve a drop on an empty slot.
 */
export function resolveDropOnEmptySlot(slotId: SlotId): DropLocation {
  return { targetSlotId: slotId, index: 0 };
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
  const node = doc.nodes[nodeId];
  if (!node || node.slots.length === 0) return null;

  const firstSlotId = node.slots[0];
  const firstSlot = doc.slots[firstSlotId];
  if (!firstSlot) return null;

  return { targetSlotId: firstSlotId, index: firstSlot.children.length };
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
  const targetSlot = doc.slots[targetSlotId];
  if (!targetSlot) return false;

  const parentNode = doc.nodes[targetSlot.nodeId];
  if (!parentNode) return false;

  const rootNode = doc.nodes[doc.root];
  const rootSlotId = rootNode?.slots[0];

  if (isAnchoredPageBlock(dragData.blockType)) {
    if (!rootSlotId || targetSlotId !== rootSlotId) {
      return false;
    }
  }

  if (dragData.source === 'palette' && !registry.canInsertInDocument(dragData.blockType, doc)) {
    return false;
  }

  if (dragData.source === 'block' && isAnchoredPageBlock(dragData.blockType)) {
    return false;
  }

  // Check containment constraint
  if (!registry.canContain(parentNode.type, dragData.blockType)) {
    return false;
  }

  // The slot's component definition may declare it locked. Ask the engine
  // generically — the engine doesn't know about stencils, but the stencil's
  // SlotTemplate.locked predicate (combined with the placeholder fill's
  // editable break) gives the right answer here.
  if (isSlotLocked(doc, targetSlotId, indexes, registry)) {
    return false;
  }

  // For block drags: prevent cycle (can't move into own descendant)
  if (dragData.source === 'block') {
    // Can't drop into itself
    if (targetSlot.nodeId === dragData.nodeId) return false;

    // Can't drop into a descendant of itself
    if (isDescendant(targetSlot.nodeId, dragData.nodeId, indexes)) return false;
  }

  // Stencil/placeholder structural rules. The same ancestor walk powers
  // three checks; computed once here.
  if (dragData.blockType === STENCIL_TYPE || dragData.blockType === PLACEHOLDER_TYPE) {
    const scope = computeAncestorScope(doc, targetSlotId, indexes);

    // Rule 2: a placeholder may only be dropped where a stencil is in the
    // ancestor chain, and never inside another placeholder's fill slot
    // (rule 3 — at the stencil-definition level, which is the only level
    // a bare placeholder can be dropped from the palette).
    if (dragData.blockType === PLACEHOLDER_TYPE) {
      if (!scope.hasStencilAncestor) return false;
      if (scope.hasPlaceholderAncestor) return false;
    }

    // Rule 1: a stencil cannot be dropped where its own stencilId is already
    // in the ancestor chain. For palette drags the stencilId is chosen later
    // in the picker dialog (which has its own recursion guard); for block
    // drags we have the dragged node, so we can check now.
    if (dragData.blockType === STENCIL_TYPE && dragData.source === 'block') {
      const sid = readStencilId(doc.nodes[dragData.nodeId]);
      if (sid && scope.stencilIds.has(sid)) return false;
    }
  }

  return true;
}

/**
 * Check whether `nodeId` is a descendant of `ancestorId`.
 * Walks up from nodeId looking for ancestorId.
 */
function isDescendant(nodeId: NodeId, ancestorId: NodeId, indexes: DocumentIndexes): boolean {
  const visited = new Set<NodeId>();
  let current: NodeId | undefined = indexes.parentNodeByNodeId.get(nodeId);

  while (current !== undefined) {
    if (current === ancestorId) return true;
    if (visited.has(current)) return false;
    visited.add(current);
    current = indexes.parentNodeByNodeId.get(current);
  }

  return false;
}
