import { beforeEach, describe, expect, it } from 'vitest';
import {
  nodeId,
  slotId,
  resetCounter,
  createTestDocumentWithChildren,
} from '../engine/test-helpers.js';
import type { Node, SlotId } from '../types/index.js';
import {
  BLOCK_CLIPBOARD_MIME,
  extractBlockSubtree,
  parseBlockClipboard,
  readBlockClipboardData,
  rekeyBlockSubtree,
  serializeBlockClipboard,
  writeBlockClipboardData,
} from './block-clipboard.js';

beforeEach(() => {
  resetCounter();
});

describe('block clipboard helpers', () => {
  it('extracts the selected subtree including descendants', () => {
    const { doc, containerNodeId, containerSlotId } = createTestDocumentWithChildren();
    const childNodeId = nodeId('child');

    const childNode: Node = {
      id: childNodeId,
      type: 'text',
      slots: [],
      props: { content: null },
    };
    doc.nodes[childNodeId] = childNode;
    doc.slots[containerSlotId].children.push(childNodeId);

    const subtree = extractBlockSubtree(doc, containerNodeId);

    expect(subtree).not.toBeNull();
    expect(subtree?.node.id).toBe(containerNodeId);
    expect(subtree?.slots).toHaveLength(1);
    expect(subtree?.slots[0].children).toEqual([childNodeId]);
    expect(subtree?.extraNodes?.map((node) => node.id)).toEqual([childNodeId]);
  });

  it('rekeys nodes and slots on paste', () => {
    const { doc, containerNodeId } = createTestDocumentWithChildren();
    const subtree = extractBlockSubtree(doc, containerNodeId);
    expect(subtree).not.toBeNull();

    const rekeyed = rekeyBlockSubtree(subtree!);

    expect(rekeyed.node.id).not.toBe(subtree!.node.id);
    expect(rekeyed.slots[0].id).not.toBe(subtree!.slots[0].id);
    expect(rekeyed.node.slots).toEqual([rekeyed.slots[0].id]);
    expect(rekeyed.slots[0].nodeId).toBe(rekeyed.node.id);
  });

  it('rekeys a subtree with descendants and extra nodes', () => {
    const { doc, containerNodeId, containerSlotId } = createTestDocumentWithChildren();
    const childNodeId = nodeId('child');

    doc.nodes[childNodeId] = {
      id: childNodeId,
      type: 'text',
      slots: [],
      props: { content: null },
    };
    doc.slots[containerSlotId].children.push(childNodeId);

    const subtree = extractBlockSubtree(doc, containerNodeId);
    expect(subtree).not.toBeNull();

    const rekeyed = rekeyBlockSubtree(subtree!);

    expect(rekeyed.extraNodes).toHaveLength(1);
    expect(rekeyed.extraNodes?.[0].id).not.toBe(childNodeId);
    expect(rekeyed.slots[0].children).toEqual([rekeyed.extraNodes?.[0].id]);
  });

  it('throws when rekeying a malformed subtree with missing slot mappings', () => {
    expect(() =>
      rekeyBlockSubtree({
        node: {
          id: nodeId('broken-root'),
          type: 'container',
          slots: [slotId('missing-slot')],
        },
        slots: [],
      }),
    ).toThrow(/Missing slot id mapping/);
  });

  it('serializes and parses clipboard payloads', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const serialized = serializeBlockClipboard(subtree!);
    const parsed = parseBlockClipboard(serialized);

    expect(parsed).toEqual(subtree);
  });

  it('writes and reads clipboard data using the custom mime type', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const store = new Map<string, string>();
    const clipboardData = {
      setData: (type: string, value: string) => {
        store.set(type, value);
      },
      getData: (type: string) => store.get(type) ?? '',
    } as unknown as DataTransfer;

    const wrote = writeBlockClipboardData(clipboardData, subtree!);
    const read = readBlockClipboardData(clipboardData);

    expect(wrote).toBe(true);
    expect(store.has(BLOCK_CLIPBOARD_MIME)).toBe(true);
    expect(read).toEqual(subtree);
  });

  it('returns false when clipboard data is unavailable for writes', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    expect(writeBlockClipboardData(null, subtree!)).toBe(false);
  });

  it('returns null when clipboard data is unavailable for reads', () => {
    expect(readBlockClipboardData(null)).toBeNull();
  });

  it('returns null when extracting a missing node subtree', () => {
    const { doc } = createTestDocumentWithChildren();

    expect(extractBlockSubtree(doc, nodeId('missing-node'))).toBeNull();
  });

  it('skips missing child nodes and missing slots while extracting', () => {
    const { doc, containerNodeId, containerSlotId } = createTestDocumentWithChildren();
    const missingSlotId = slotId('missing-slot') as SlotId;
    const missingChildId = nodeId('missing-child');

    doc.nodes[containerNodeId].slots.push(missingSlotId);
    doc.slots[containerSlotId].children.push(missingChildId);

    const subtree = extractBlockSubtree(doc, containerNodeId);
    expect(subtree).not.toBeNull();

    expect(subtree!.slots).toHaveLength(1);
    expect(subtree!.extraNodes).toBeUndefined();
  });

  it('rejects malformed clipboard data', () => {
    const clipboardData = {
      getData: (type: string) => (type === BLOCK_CLIPBOARD_MIME ? '{"kind":"wrong"}' : ''),
    } as unknown as DataTransfer;

    expect(readBlockClipboardData(clipboardData)).toBeNull();
  });

  it('returns null for invalid JSON payloads', () => {
    expect(parseBlockClipboard('{not-json')).toBeNull();
  });

  it('rejects payloads with invalid subtree shapes', () => {
    expect(
      parseBlockClipboard(
        JSON.stringify({
          kind: 'epistola/block',
          version: 1,
          content: {
            node: { id: 'node-1', type: 'text', slots: [123] },
            slots: [],
          },
        }),
      ),
    ).toBeNull();

    expect(
      parseBlockClipboard(
        JSON.stringify({
          kind: 'epistola/block',
          version: 1,
          content: {
            node: { id: 'node-1', type: 'text', slots: [] },
            slots: [{ id: 'slot-1', nodeId: 'node-1', name: 'children', children: [123] }],
          },
        }),
      ),
    ).toBeNull();

    expect(
      parseBlockClipboard(
        JSON.stringify({
          kind: 'epistola/block',
          version: 1,
          content: {
            node: { id: 'node-1', type: 'text', slots: [] },
            slots: [],
            extraNodes: {},
          },
        }),
      ),
    ).toBeNull();

    expect(
      parseBlockClipboard(
        JSON.stringify({
          kind: 'epistola/block',
          version: 1,
          content: {
            node: null,
            slots: [],
          },
        }),
      ),
    ).toBeNull();
  });

  it('throws when rekeying a subtree without a root node', () => {
    expect(() =>
      rekeyBlockSubtree({
        node: null,
        slots: [],
      } as unknown as Parameters<typeof rekeyBlockSubtree>[0]),
    ).toThrow(/missing a root node/i);
  });
});
