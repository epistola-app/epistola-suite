/**
 * Shared ancestor-walk helpers for stencil/placeholder drop validation.
 *
 * The same walk produces three pieces of information used by:
 * - the stencil picker (recursion guard before insert)
 * - drop validation (canDropHere): recursion + placeholder-scope rules
 * - lock enforcement (isInLockedStencilLayout): published stencil layouts are
 *   read-only except inside placeholder fills.
 */

import type { NodeId, SlotId, TemplateDocument } from '../../types/index.js';
import type { DocumentIndexes } from '../../engine/indexes.js';

export interface AncestorScope {
  /** Stencil IDs of every stencil ancestor (used for the recursion guard). */
  stencilIds: Set<string>;
  /** True if any stencil node is in the ancestor chain. */
  hasStencilAncestor: boolean;
  /** True if any placeholder node is in the ancestor chain. */
  hasPlaceholderAncestor: boolean;
}

/**
 * Compute the ancestor scope for a target slot. The walk goes from the slot's
 * parent node upward to the document root.
 */
export function computeAncestorScope(
  doc: TemplateDocument,
  targetSlotId: SlotId,
  indexes: DocumentIndexes,
): AncestorScope {
  const stencilIds = new Set<string>();
  let hasStencilAncestor = false;
  let hasPlaceholderAncestor = false;

  const slot = doc.slots[targetSlotId];
  if (!slot) {
    return { stencilIds, hasStencilAncestor, hasPlaceholderAncestor };
  }

  const visited = new Set<NodeId>();
  let current: NodeId | undefined = slot.nodeId;

  while (current !== undefined) {
    if (visited.has(current)) break; // defensive cycle guard
    visited.add(current);
    const node = doc.nodes[current];
    if (!node) break;
    if (node.type === 'stencil') {
      hasStencilAncestor = true;
      const sid = node.props?.stencilId as string | undefined;
      if (sid) stencilIds.add(sid);
    }
    if (node.type === 'placeholder') {
      hasPlaceholderAncestor = true;
    }
    current = indexes.parentNodeByNodeId.get(current);
  }

  return { stencilIds, hasStencilAncestor, hasPlaceholderAncestor };
}

/**
 * Determines what context a placeholder is being rendered in.
 *
 * Walks the placeholder's ancestors looking for the nearest stencil:
 *   - none, draft, or unlinked → 'stencil-author' (the user is editing the stencil itself).
 *   - published (`stencilId != null && !isDraft`) → 'template-fill' (the user is filling
 *     a placeholder inside an inserted stencil).
 *
 * Used by the placeholder canvas to render the right slot.
 */
export function placeholderContext(
  doc: TemplateDocument,
  placeholderNodeId: NodeId,
  indexes: DocumentIndexes,
): 'stencil-author' | 'template-fill' {
  const visited = new Set<NodeId>();
  let current: NodeId | undefined = indexes.parentNodeByNodeId.get(placeholderNodeId);
  while (current !== undefined) {
    if (visited.has(current)) return 'stencil-author';
    visited.add(current);
    const node = doc.nodes[current];
    if (!node) return 'stencil-author';
    if (node.type === 'stencil') {
      const stencilId = node.props?.stencilId as string | null | undefined;
      const isDraft = (node.props?.isDraft as boolean | undefined) ?? false;
      const isLinked = stencilId !== null && stencilId !== undefined;
      return isLinked && !isDraft ? 'template-fill' : 'stencil-author';
    }
    current = indexes.parentNodeByNodeId.get(current);
  }
  return 'stencil-author';
}
