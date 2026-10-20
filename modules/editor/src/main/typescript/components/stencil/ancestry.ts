// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Shared ancestor-walk helpers for stencil/placeholder drop validation and
 * placeholder presentation.
 *
 * Recursion and nesting depth are lexical and live in `instances.ts`
 * (`lexicalScopeAt`, `checkStencilNesting`); this module answers the
 * structural questions (is there a stencil or placeholder above this slot?)
 * and which side of a placeholder the author is on.
 */

import type { NodeId, SlotId, TemplateDocument } from '../../types/index.js';
import type { DocumentIndexes } from '../../engine/indexes.js';
import type { ComponentRegistry } from '../../engine/registry.js';
import { isPlaceholder } from '../placeholder/node-types.js';
import { STENCIL_SLOT_CHILDREN } from './constants.js';
import { isStencil } from './node-types.js';
import { lexicalOwnerOf } from './instances.js';

export interface AncestorScope {
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
  let hasStencilAncestor = false;
  let hasPlaceholderAncestor = false;

  const slot = doc.slots[targetSlotId];
  if (!slot) return { hasStencilAncestor, hasPlaceholderAncestor };

  const visited = new Set<NodeId>();
  let current: NodeId | undefined = slot.nodeId;
  while (current !== undefined) {
    if (visited.has(current)) break; // defensive cycle guard
    visited.add(current);
    const node = doc.nodes[current];
    if (!node) break;
    if (isStencil(node)) hasStencilAncestor = true;
    if (isPlaceholder(node)) hasPlaceholderAncestor = true;
    current = indexes.parentNodeByNodeId.get(current);
  }
  return { hasStencilAncestor, hasPlaceholderAncestor };
}

/**
 * The stencil instance a placeholder belongs to: the stencil whose content it
 * lexically is. Placeholder names are unique per stencil, so two stencils may
 * both declare a `body` placeholder; they live in independent namespaces.
 */
export function owningStencilOf(
  doc: TemplateDocument,
  nodeId: NodeId,
  indexes: DocumentIndexes,
): NodeId | undefined {
  return lexicalOwnerOf(doc, nodeId, indexes);
}

/**
 * Whether the content of stencil instance [stencilNodeId] is locked: the
 * stencil definition's `children` slot predicate, so the lock has one source.
 */
export function isStencilContentLocked(
  doc: TemplateDocument,
  stencilNodeId: NodeId,
  registry: ComponentRegistry,
): boolean {
  const node = doc.nodes[stencilNodeId];
  if (!isStencil(node)) return false;
  const template = registry.get(node.type)?.slots.find((s) => s.name === STENCIL_SLOT_CHILDREN);
  const locked = template?.locked;
  return typeof locked === 'function' ? locked(node) : locked === true;
}

/**
 * Determines what context a placeholder is being rendered in.
 *
 * The placeholder belongs to the stencil whose content it lexically is:
 *   - none, or a stencil whose content is unlocked (being edited, or unlinked)
 *     → 'stencil-author' (the user is editing the stencil itself);
 *   - a stencil whose content is locked → 'template-fill' (the user is filling
 *     a placeholder of a stencil the document uses).
 *
 * Used by the placeholder canvas to render the right slot.
 */
export function placeholderContext(
  doc: TemplateDocument,
  placeholderNodeId: NodeId,
  indexes: DocumentIndexes,
  registry: ComponentRegistry,
): 'stencil-author' | 'template-fill' {
  const owner = owningStencilOf(doc, placeholderNodeId, indexes);
  if (!owner) return 'stencil-author';
  return isStencilContentLocked(doc, owner, registry) ? 'template-fill' : 'stencil-author';
}
