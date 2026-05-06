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
 * True when the target slot lives inside a published stencil's frozen layout
 * (and not inside one of its placeholder fill slots).
 *
 * Walks **slots**, not just nodes, because the rule depends on which slot of a
 * placeholder we crossed:
 *
 *  - Crossing a placeholder's `fill` slot: we are in the template-fill area
 *    → editable, return false. The stencil's lock state above doesn't matter.
 *  - Crossing a placeholder's `default` slot: we are in the stencil-author area
 *    → continue walking; the parent stencil's lock state decides.
 *  - Reaching a stencil node:
 *    - has a `stencilId` and is not draft → locked, return true.
 *    - draft, or unlinked (`stencilId == null`) → editable, return false.
 */
export function isInLockedStencilLayout(
  doc: TemplateDocument,
  targetSlotId: SlotId,
  indexes: DocumentIndexes,
): boolean {
  const visited = new Set<SlotId>();
  let currentSlotId: SlotId | null = targetSlotId;

  while (currentSlotId !== null) {
    if (visited.has(currentSlotId)) return false; // defensive cycle guard
    visited.add(currentSlotId);
    const slot: import('../../types/index.js').Slot | undefined = doc.slots[currentSlotId];
    if (!slot) return false;
    const parent: import('../../types/index.js').Node | undefined = doc.nodes[slot.nodeId];
    if (!parent) return false;

    if (parent.type === 'placeholder') {
      if (slot.name === 'fill') return false; // fill slot → editable
      // default (or any other) → keep walking; the stencil decides.
    }
    if (parent.type === 'stencil') {
      const stencilId = parent.props?.stencilId as string | null | undefined;
      const isDraft = (parent.props?.isDraft as boolean | undefined) ?? false;
      const isLinked = stencilId !== null && stencilId !== undefined;
      return isLinked && !isDraft;
    }

    const next: SlotId | undefined = indexes.parentSlotByNodeId.get(parent.id);
    currentSlotId = next ?? null;
  }
  return false;
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
