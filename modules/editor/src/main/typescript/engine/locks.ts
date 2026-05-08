/**
 * Generic slot-lock mechanism.
 *
 * A slot is "locked" when its component definition's `SlotTemplate.locked`
 * predicate fires (e.g. a stencil node says its `children` slot is locked
 * when the stencil is published and not in draft mode). The lock propagates
 * inward to descendant slots unless an intermediate slot opts out via
 * `SlotTemplate.editable` (e.g. a placeholder's `fill` slot inside a locked
 * stencil is still mutable).
 *
 * The engine reducer consults this helper to reject mutations targeting
 * locked slots. Component code provides the predicates; the engine has no
 * built-in knowledge of what "locked" means in any given domain.
 */

import type { TemplateDocument, SlotId } from '../types/index.js';
import type { DocumentIndexes } from './indexes.js';
import type { ComponentRegistry, SlotTemplate } from './registry.js';
import type { Node } from '../types/index.js';

/**
 * Walks the slot chain from `slotId` upward to the document root. Returns
 * `true` when the walk hits a `locked` slot template before any `editable`
 * one breaks the inheritance chain. Default (no predicates fire): `false`.
 */
export function isSlotLocked(
  doc: TemplateDocument,
  slotId: SlotId,
  indexes: DocumentIndexes,
  registry: ComponentRegistry,
): boolean {
  const visited = new Set<SlotId>();
  let currentSlotId: SlotId | null = slotId;

  while (currentSlotId !== null) {
    if (visited.has(currentSlotId)) return false; // defensive cycle guard
    visited.add(currentSlotId);

    const slot = doc.slots[currentSlotId];
    if (!slot) return false;
    const parent: Node | undefined = doc.nodes[slot.nodeId];
    if (!parent) return false;

    const def = registry.get(parent.type);
    const slotTpl: SlotTemplate | undefined = def?.slots.find((s) => s.name === slot.name);
    if (slotTpl) {
      if (resolvePredicate(slotTpl.editable, parent)) return false;
      if (resolvePredicate(slotTpl.locked, parent)) return true;
    }

    const next: SlotId | undefined = indexes.parentSlotByNodeId.get(parent.id);
    currentSlotId = next ?? null;
  }
  return false;
}

function resolvePredicate(
  pred: boolean | ((node: Node) => boolean) | undefined,
  node: Node,
): boolean {
  if (pred === undefined) return false;
  if (typeof pred === 'boolean') return pred;
  return pred(node);
}
