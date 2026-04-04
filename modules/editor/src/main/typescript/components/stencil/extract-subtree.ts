/**
 * Extract a stencil node's children as a standalone TemplateDocument.
 *
 * Given a stencil node in a document, collects the node's slot → children →
 * all descendant nodes/slots recursively, and wraps them in a new
 * TemplateDocument with a root container.
 */

import type { TemplateDocument, Node, Slot, NodeId, SlotId } from '../../types/index.js';
import { nanoid } from 'nanoid';

/**
 * Extracts the content of a stencil node as a standalone TemplateDocument.
 * The stencil node itself is NOT included — only its children and their descendants.
 */
export function extractSubtree(doc: TemplateDocument, stencilNodeId: NodeId): TemplateDocument {
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

  // Collect all descendant nodes and slots recursively
  const collectedNodes = new Map<string, Node>();
  const collectedSlots = new Map<string, Slot>();

  function collectDescendants(nodeId: NodeId) {
    const node = doc.nodes[nodeId];
    if (!node) return;
    collectedNodes.set(nodeId as string, node);

    for (const sid of node.slots) {
      const s = (doc.slots as Record<string, Slot>)[sid as string];
      if (!s) continue;
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
