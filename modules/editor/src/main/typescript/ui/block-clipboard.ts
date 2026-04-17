import { nanoid } from 'nanoid';
import type { TemplateDocument, Node, Slot, NodeId, SlotId } from '../types/index.js';

export const BLOCK_CLIPBOARD_KIND = 'epistola/block';
export const BLOCK_CLIPBOARD_MIME = 'application/x.epistola-block+json';
export const BLOCK_CLIPBOARD_TEXT_LABEL = 'Epistola block';

const BLOCK_CLIPBOARD_VERSION = 1;

export interface BlockSubtree {
  node: Node;
  slots: Slot[];
  extraNodes?: Node[];
}

interface BlockClipboardEnvelope {
  kind: typeof BLOCK_CLIPBOARD_KIND;
  version: typeof BLOCK_CLIPBOARD_VERSION;
  content: BlockSubtree;
}

function isNodeLike(value: unknown): value is Node {
  if (!value || typeof value !== 'object') return false;

  const candidate = value as Partial<Node>;
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.type === 'string' &&
    Array.isArray(candidate.slots) &&
    candidate.slots.every((slotId) => typeof slotId === 'string')
  );
}

function isSlotLike(value: unknown): value is Slot {
  if (!value || typeof value !== 'object') return false;

  const candidate = value as Partial<Slot>;
  return (
    typeof candidate.id === 'string' &&
    typeof candidate.nodeId === 'string' &&
    typeof candidate.name === 'string' &&
    Array.isArray(candidate.children) &&
    candidate.children.every((childId) => typeof childId === 'string')
  );
}

function isBlockSubtree(value: unknown): value is BlockSubtree {
  if (!value || typeof value !== 'object') return false;

  const candidate = value as Partial<BlockSubtree>;
  if (!isNodeLike(candidate.node)) return false;
  if (!Array.isArray(candidate.slots) || !candidate.slots.every((slot) => isSlotLike(slot))) {
    return false;
  }
  if (
    'extraNodes' in candidate &&
    (!Array.isArray(candidate.extraNodes) ||
      !candidate.extraNodes.every((node) => isNodeLike(node)))
  ) {
    return false;
  }

  return true;
}

function isBlockClipboardEnvelope(value: unknown): value is BlockClipboardEnvelope {
  if (!value || typeof value !== 'object') return false;

  const candidate = value as Partial<BlockClipboardEnvelope>;
  return (
    candidate.kind === BLOCK_CLIPBOARD_KIND &&
    candidate.version === BLOCK_CLIPBOARD_VERSION &&
    isBlockSubtree(candidate.content)
  );
}

function getRequiredMapValue<TKey, TValue>(
  map: Map<TKey, TValue>,
  key: TKey,
  label: string,
): TValue {
  const value = map.get(key);
  if (value === null || typeof value === 'undefined') {
    throw new Error(`Missing ${label}`);
  }
  return value;
}

export function extractBlockSubtree(doc: TemplateDocument, nodeId: NodeId): BlockSubtree | null {
  const rootNode = doc.nodes[nodeId];
  if (!rootNode) return null;

  const nodeIds: NodeId[] = [];
  const slotIds: SlotId[] = [];

  const visit = (currentId: NodeId): void => {
    const node = doc.nodes[currentId];
    if (!node) return;

    nodeIds.push(currentId);
    for (const slotId of node.slots) {
      const slot = doc.slots[slotId];
      if (!slot) continue;
      slotIds.push(slotId);
      for (const childId of slot.children) {
        visit(childId);
      }
    }
  };

  visit(nodeId);

  const nodeCopies = nodeIds
    .map((id) => doc.nodes[id])
    .filter((node): node is Node => !!node)
    .map((node) => structuredClone(node));
  const slotCopies = slotIds
    .map((id) => doc.slots[id])
    .filter((slot): slot is Slot => !!slot)
    .map((slot) => structuredClone(slot));

  const [node, ...extraNodes] = nodeCopies;
  if (!node) return null;

  if (extraNodes.length === 0) {
    return {
      node,
      slots: slotCopies,
    };
  }

  return {
    node,
    slots: slotCopies,
    extraNodes,
  };
}

export function rekeyBlockSubtree(subtree: BlockSubtree): BlockSubtree {
  if (!isNodeLike(subtree.node)) {
    throw new Error('Re-keyed block subtree is missing a root node');
  }

  const sourceNodes = [subtree.node, ...(subtree.extraNodes ?? [])];
  const nodeIdMap = new Map<NodeId, NodeId>();
  const slotIdMap = new Map<SlotId, SlotId>();

  for (const node of sourceNodes) {
    nodeIdMap.set(node.id, nanoid());
  }
  for (const slot of subtree.slots) {
    slotIdMap.set(slot.id, nanoid());
  }

  const clonedNodes = sourceNodes.map((node) => {
    const clonedNode = structuredClone(node);
    clonedNode.id = getRequiredMapValue(nodeIdMap, node.id, `node id mapping for ${node.id}`);
    clonedNode.slots = node.slots.map((slotId) =>
      getRequiredMapValue(slotIdMap, slotId, `slot id mapping for ${slotId}`),
    );
    return clonedNode;
  });
  const clonedSlots = subtree.slots.map((slot) => {
    const clonedSlot = structuredClone(slot);
    clonedSlot.id = getRequiredMapValue(slotIdMap, slot.id, `slot id mapping for ${slot.id}`);
    clonedSlot.nodeId = getRequiredMapValue(
      nodeIdMap,
      slot.nodeId,
      `node id mapping for ${slot.nodeId}`,
    );
    clonedSlot.children = slot.children.map((childId) =>
      getRequiredMapValue(nodeIdMap, childId, `child node id mapping for ${childId}`),
    );
    return clonedSlot;
  });

  const [node, ...extraNodes] = clonedNodes;
  if (!node) {
    throw new Error('Re-keyed block subtree is missing a root node');
  }

  if (extraNodes.length === 0) {
    return {
      node,
      slots: clonedSlots,
    };
  }

  return {
    node,
    slots: clonedSlots,
    extraNodes,
  };
}

export function serializeBlockClipboard(subtree: BlockSubtree): string {
  const payload: BlockClipboardEnvelope = {
    kind: BLOCK_CLIPBOARD_KIND,
    version: BLOCK_CLIPBOARD_VERSION,
    content: structuredClone(subtree),
  };
  return JSON.stringify(payload);
}

export function parseBlockClipboard(raw: string): BlockSubtree | null {
  try {
    const payload = JSON.parse(raw) as unknown;
    if (!isBlockClipboardEnvelope(payload)) {
      return null;
    }
    return payload.content;
  } catch {
    return null;
  }
}

export function writeBlockClipboardData(
  clipboardData: DataTransfer | null,
  subtree: BlockSubtree,
): boolean {
  if (!clipboardData) return false;

  clipboardData.setData(BLOCK_CLIPBOARD_MIME, serializeBlockClipboard(subtree));
  clipboardData.setData('text/plain', BLOCK_CLIPBOARD_TEXT_LABEL);
  return true;
}

export function readBlockClipboardData(clipboardData: DataTransfer | null): BlockSubtree | null {
  if (!clipboardData) return null;
  return parseBlockClipboard(clipboardData.getData(BLOCK_CLIPBOARD_MIME));
}
