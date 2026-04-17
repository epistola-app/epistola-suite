import type { Node, NodeId, Slot, TemplateDocument } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import { reKeyContent } from './rekey-content.js';

type ReKeyedContent = ReturnType<typeof reKeyContent>;

export function replaceStencilSlotContent(
  engine: Pick<EditorEngine, 'doc' | 'dispatch'>,
  stencilNode: Pick<Node, 'slots'>,
  content: TemplateDocument,
  rekey: (value: TemplateDocument) => ReKeyedContent = reKeyContent,
): void {
  const slotId = stencilNode.slots[0];
  if (!slotId) return;

  while (true) {
    const currentSlot = engine.doc.slots[slotId];
    if (!currentSlot || currentSlot.children.length === 0) break;
    engine.dispatch({ type: 'RemoveNode', nodeId: currentSlot.children[0] });
  }

  const reKeyed = rekey(content);
  const nodeById = new Map(reKeyed.nodes.map((node) => [node.id as string, node]));
  const slotById = new Map(reKeyed.slots.map((slot) => [slot.id as string, slot]));

  for (const childId of reKeyed.childNodeIds) {
    const childNode = nodeById.get(childId as string);
    if (!childNode) continue;

    const descNodes: Node[] = [];
    const descSlots: Slot[] = [];

    function collectDescendants(nodeId: NodeId) {
      const node = nodeById.get(nodeId as string);
      if (!node) return;
      for (const sid of node.slots) {
        const slot = slotById.get(sid as string);
        if (!slot) continue;
        descSlots.push(slot);
        for (const cid of slot.children) {
          const child = nodeById.get(cid as string);
          if (!child) continue;
          descNodes.push(child);
          collectDescendants(cid);
        }
      }
    }

    collectDescendants(childId);

    const ownSlots = childNode.slots
      .map((sid) => slotById.get(sid as string))
      .filter(Boolean) as Slot[];

    engine.dispatch({
      type: 'InsertNode',
      node: childNode,
      slots: [...ownSlots, ...descSlots],
      targetSlotId: slotId,
      index: -1,
      _restoreNodes: descNodes.length > 0 ? descNodes : undefined,
    });
  }
}
