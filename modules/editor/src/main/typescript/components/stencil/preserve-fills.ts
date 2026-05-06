/**
 * Client-side equivalent of the server's `StencilContentReplacer.captureFillsByName`
 * + splice logic. Used by `StencilInspector` to round-trip template overrides
 * through a "Start Editing → … → Publish" cycle without losing them.
 *
 * The shape and slot conventions match the server: every `placeholder` node has
 * two slots, identified by name (`default`, `fill`); only the `fill` slot's
 * subtree is the user's override.
 */

import type { TemplateDocument, Node, Slot, NodeId } from '../../types/index.js';
import { nanoid } from 'nanoid';

/** A snapshot of one placeholder fill, captured by name. */
export interface CapturedFill {
  rootChildIds: string[];
  nodes: Map<string, Node>;
  slots: Map<string, Slot>;
}

/**
 * Walks `doc` looking for `placeholder` nodes; captures every non-empty
 * `fill` slot's subtree by reference. The capture is a shallow snapshot —
 * later splices re-key fresh IDs.
 *
 * @param doc Source document (typically a stencil's extracted subtree).
 * @returns Map keyed by placeholder name.
 */
export function captureFillsByName(doc: TemplateDocument): Map<string, CapturedFill> {
  const result = new Map<string, CapturedFill>();
  for (const node of Object.values(doc.nodes)) {
    if (node.type !== 'placeholder') continue;
    const name = (node.props?.name as string | undefined) ?? '';
    if (!name) continue;
    const fillSlot = node.slots
      .map((sid) => (doc.slots as Record<string, Slot>)[sid as string])
      .find((s) => s && s.name === 'fill');
    if (!fillSlot || fillSlot.children.length === 0) continue;

    const collectedNodes = new Map<string, Node>();
    const collectedSlots = new Map<string, Slot>();
    for (const childId of fillSlot.children) {
      walkSubtree(childId as string, doc, collectedNodes, collectedSlots);
    }
    result.set(name, {
      rootChildIds: [...fillSlot.children] as string[],
      nodes: collectedNodes,
      slots: collectedSlots,
    });
  }
  return result;
}

/**
 * Re-keys a captured fill with fresh IDs, returning the splice payload —
 * ready to merge into a host doc and assign to a placeholder's fill slot.
 */
export interface ReKeyedFill {
  rootChildIds: NodeId[];
  nodes: Node[];
  slots: Slot[];
}

export function reKeyCapturedFill(capture: CapturedFill): ReKeyedFill {
  const nodeIdMap = new Map<string, NodeId>();
  const slotIdMap = new Map<string, string>();
  for (const id of capture.nodes.keys()) nodeIdMap.set(id, nanoid() as NodeId);
  for (const id of capture.slots.keys()) slotIdMap.set(id, nanoid());

  const nodes: Node[] = [];
  for (const [oldId, n] of capture.nodes) {
    const newId = nodeIdMap.get(oldId)!;
    nodes.push({
      ...n,
      id: newId,
      slots: n.slots.map(
        (sid) => slotIdMap.get(sid as string)! as unknown as (typeof n.slots)[number],
      ),
    });
  }
  const slots: Slot[] = [];
  for (const [oldId, s] of capture.slots) {
    const newId = slotIdMap.get(oldId)!;
    slots.push({
      ...s,
      id: newId as unknown as Slot['id'],
      nodeId: nodeIdMap.get(s.nodeId as string)!,
      children: s.children.map((c) => nodeIdMap.get(c as string)!) as Slot['children'],
    });
  }
  const rootChildIds = capture.rootChildIds.map((c) => nodeIdMap.get(c)!);
  return { rootChildIds, nodes, slots };
}

function walkSubtree(
  nodeId: string,
  doc: TemplateDocument,
  outNodes: Map<string, Node>,
  outSlots: Map<string, Slot>,
) {
  if (outNodes.has(nodeId)) return;
  const node = doc.nodes[nodeId as NodeId];
  if (!node) return;
  outNodes.set(nodeId, node);
  for (const sid of node.slots) {
    const slot = (doc.slots as Record<string, Slot>)[sid as string];
    if (!slot) continue;
    outSlots.set(sid as string, slot);
    for (const childId of slot.children) {
      walkSubtree(childId as string, doc, outNodes, outSlots);
    }
  }
}
