/**
 * Re-key a TemplateDocument's nodes and slots with fresh IDs.
 *
 * Used when inserting a stencil's content into a template — all IDs must be
 * unique within the target document. Generates new nanoid() for every node
 * and slot, rewrites all references.
 */

import { nanoid } from 'nanoid';
import type { TemplateDocument, Node, Slot, NodeId, SlotId } from '../../types/index.js';

export interface ReKeyResult {
  /** The children of the parent slot (root node's children mapped to new IDs). */
  childNodeIds: NodeId[];
  /** All re-keyed nodes from the source content. */
  nodes: Node[];
  /** All re-keyed slots from the source content. */
  slots: Slot[];
}

/**
 * Deep-copies all nodes and slots from `content` with fresh IDs.
 * Returns the re-keyed nodes/slots ready to be merged into the target document.
 */
export function reKeyContent(content: TemplateDocument): ReKeyResult {
  const nodeIdMap = new Map<string, NodeId>();
  const slotIdMap = new Map<string, SlotId>();

  // Generate new IDs for all nodes and slots
  for (const nodeId of Object.keys(content.nodes)) {
    nodeIdMap.set(nodeId, nanoid() as NodeId);
  }
  for (const slotId of Object.keys(content.slots)) {
    slotIdMap.set(slotId, nanoid() as SlotId);
  }

  // Re-key nodes
  const nodes: Node[] = Object.values(content.nodes).map((node) => ({
    ...node,
    id: nodeIdMap.get(node.id)!,
    slots: node.slots.map((sid) => slotIdMap.get(sid as string) ?? (sid as SlotId)),
  }));

  // Re-key slots
  const slots: Slot[] = Object.values(content.slots).map((slot) => ({
    ...slot,
    id: slotIdMap.get(slot.id)!,
    nodeId: nodeIdMap.get(slot.nodeId)!,
    children: slot.children.map((cid) => nodeIdMap.get(cid as string) ?? (cid as NodeId)),
  }));

  // Find the root node's slot to get the top-level children
  const rootNode = content.nodes[content.root];
  const rootSlotId = rootNode?.slots[0];
  const rootSlot = rootSlotId ? (content.slots as Record<string, Slot>)[rootSlotId as string] : null;
  const childNodeIds = rootSlot
    ? rootSlot.children.map((cid: NodeId) => nodeIdMap.get(cid as string) ?? cid)
    : [nodeIdMap.get(content.root as string)!];

  return { childNodeIds, nodes, slots };
}
