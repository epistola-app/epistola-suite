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

  // Identify the root node and its slot — these should NOT be included in the
  // result because only the root's children are injected into the stencil's slot.
  // Including the root would create duplicate parent references for child nodes.
  const rootNode = content.nodes[content.root];
  const rootSlotId = rootNode?.slots[0] as string | undefined;

  // Re-key nodes (exclude the root node)
  const nodes: Node[] = Object.values(content.nodes)
    .filter((node) => (node.id as string) !== (content.root as string))
    .map((node) => {
      const newId = nodeIdMap.get(node.id as string);
      if (!newId) throw new Error(`reKeyContent: no mapping for node ${node.id}`);
      return {
        ...node,
        id: newId,
        slots: node.slots.map((sid) => {
          const newSid = slotIdMap.get(sid as string);
          if (!newSid) throw new Error(`reKeyContent: no mapping for slot ${sid}`);
          return newSid;
        }),
      };
    });

  // Re-key slots (exclude the root's slot)
  const slots: Slot[] = Object.values(content.slots)
    .filter((slot) => (slot.id as string) !== rootSlotId)
    .map((slot) => {
      const newId = slotIdMap.get(slot.id as string);
      if (!newId) throw new Error(`reKeyContent: no mapping for slot ${slot.id}`);
      const newNodeId = nodeIdMap.get(slot.nodeId as string);
      if (!newNodeId) throw new Error(`reKeyContent: no mapping for node ${slot.nodeId}`);
      return {
        ...slot,
        id: newId,
        nodeId: newNodeId,
        children: slot.children.map((cid) => {
          const newCid = nodeIdMap.get(cid as string);
          if (!newCid) throw new Error(`reKeyContent: no mapping for child node ${cid}`);
          return newCid;
        }),
      };
    });

  // Get the root's children as the top-level children for the stencil's slot
  const rootSlot = rootSlotId ? (content.slots as Record<string, Slot>)[rootSlotId] : null;
  const childNodeIds = rootSlot
    ? rootSlot.children.map((cid: NodeId) => {
        const newCid = nodeIdMap.get(cid as string);
        if (!newCid) throw new Error(`reKeyContent: no mapping for root child ${cid}`);
        return newCid;
      })
    : (() => {
        const newRoot = nodeIdMap.get(content.root as string);
        if (!newRoot) throw new Error(`reKeyContent: no mapping for root ${content.root}`);
        return [newRoot];
      })();

  return { childNodeIds, nodes, slots };
}
