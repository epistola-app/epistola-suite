/**
 * Extract a stencil node's children as a standalone TemplateDocument.
 *
 * Given a stencil node in a document, collects the node's slot → children →
 * all descendant nodes/slots recursively, and wraps them in a new
 * TemplateDocument with a root container.
 *
 * **Placeholder fill slots are stripped on extract.** A stencil definition
 * never ships with template overrides; the `fill` slot of every placeholder
 * is included structurally but with `children: []`, and the override's
 * descendants are not collected. This is the boundary between "I am editing
 * the stencil definition" and "I am editing a template's override of this
 * stencil."
 */

import type { TemplateDocument, Node, Slot, NodeId, SlotId } from '../../types/index.js';
import { nanoid } from 'nanoid';
import { PLACEHOLDER_TYPE, PLACEHOLDER_SLOT_FILL } from '../placeholder/constants.js';

/**
 * Extracts the content of a stencil node as a standalone TemplateDocument.
 * The stencil node itself is NOT included — only its children and their descendants.
 *
 * @param options.keepFills — when true, placeholder `fill` slot content is
 *   included in the output. Defaults to false (strip fills) — that's what
 *   stencil-save uses. The fills-preserving variant is used by client-side
 *   override-preservation flows (Discard).
 */
export function extractSubtree(
  doc: TemplateDocument,
  stencilNodeId: NodeId,
  options: { keepFills?: boolean } = {},
): TemplateDocument {
  const stencilNode = doc.nodes[stencilNodeId];
  if (!stencilNode) throw new Error(`Node ${stencilNodeId} not found`);

  // Find the stencil's children slot
  const slotId = stencilNode.slots[0];
  if (!slotId) {
    return createEmptyDocument();
  }

  const slot = (doc.slots as Record<string, Slot>)[slotId as string];
  if (!slot || slot.children.length === 0) {
    return createEmptyDocument();
  }

  // Collect all descendant nodes and slots recursively. When a placeholder is
  // encountered (and `keepFills` is false), descend only into its `default`
  // slot — its `fill` slot is template-side state and must not round-trip
  // into the stencil definition.
  const collectedNodes = new Map<string, Node>();
  const collectedSlots = new Map<string, Slot>();
  const stripFills = !options.keepFills;

  function collectDescendants(nodeId: NodeId) {
    const node = doc.nodes[nodeId];
    if (!node) return;
    collectedNodes.set(nodeId as string, node);

    const isPlaceholder = node.type === PLACEHOLDER_TYPE;
    for (const sid of node.slots) {
      const s = (doc.slots as Record<string, Slot>)[sid as string];
      if (!s) continue;
      if (stripFills && isPlaceholder && s.name === PLACEHOLDER_SLOT_FILL) {
        // Keep the fill slot structurally, but empty — and skip descending.
        collectedSlots.set(sid as string, { ...s, children: [] });
        continue;
      }
      collectedSlots.set(sid as string, s);
      for (const childId of s.children) {
        collectDescendants(childId);
      }
    }
  }

  for (const childId of slot.children) {
    collectDescendants(childId);
  }

  // Create a root container that holds the children
  const rootId = nanoid() as NodeId;
  const rootSlotId = nanoid() as SlotId;

  const nodes: Record<NodeId, Node> = {
    [rootId]: {
      id: rootId,
      type: 'root',
      slots: [rootSlotId],
    },
  } as Record<NodeId, Node>;

  const slots: Record<SlotId, Slot> = {
    [rootSlotId]: {
      id: rootSlotId,
      nodeId: rootId,
      name: 'children',
      children: [...slot.children],
    },
  } as Record<SlotId, Slot>;

  // Add all collected nodes and slots
  for (const [id, node] of collectedNodes) {
    (nodes as Record<string, Node>)[id] = node;
  }
  for (const [id, s] of collectedSlots) {
    (slots as Record<string, Slot>)[id] = s;
  }

  return {
    modelVersion: 1,
    root: rootId,
    nodes,
    slots,
    themeRef: { type: 'inherit' },
  };
}

function createEmptyDocument(): TemplateDocument {
  const rootId = nanoid() as NodeId;
  const rootSlotId = nanoid() as SlotId;
  return {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
    } as Record<NodeId, Node>,
    slots: {
      [rootSlotId]: { id: rootSlotId, nodeId: rootId, name: 'children', children: [] },
    } as Record<SlotId, Slot>,
    themeRef: { type: 'inherit' },
  };
}
