import { describe, expect, it } from 'vitest';
import { isCollapsible, countChildren } from './collapse.js';
import {
  createTestDocument,
  createTestDocumentWithChildren,
  nodeId,
  slotId,
} from '../engine/test-helpers.js';

describe('isCollapsible', () => {
  it('returns false for the root node', () => {
    const doc = createTestDocument();
    expect(isCollapsible(doc, doc.root)).toBe(false);
  });

  it('returns false for a leaf node (no slots)', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    expect(isCollapsible(doc, textNodeId)).toBe(false);
  });

  it('returns true for a container node with slots', () => {
    const { doc, containerNodeId } = createTestDocumentWithChildren();
    expect(isCollapsible(doc, containerNodeId)).toBe(true);
  });

  it('returns false for a non-existent node', () => {
    const doc = createTestDocument();
    expect(isCollapsible(doc, nodeId('nonexistent'))).toBe(false);
  });
});

describe('countChildren', () => {
  it('returns 0 for a container with an empty slot', () => {
    const { doc, containerNodeId } = createTestDocumentWithChildren();
    expect(countChildren(doc, containerNodeId)).toBe(0);
  });

  it('counts children across all slots', () => {
    const rootId = nodeId('r');
    const rootSlot = slotId('rs');
    const containerId = nodeId('c');
    const slot1 = slotId('s1');
    const slot2 = slotId('s2');
    const child1 = nodeId('ch1');
    const child2 = nodeId('ch2');
    const child3 = nodeId('ch3');

    const doc = createTestDocument({
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlot] },
        [containerId]: { id: containerId, type: 'columns', slots: [slot1, slot2] },
        [child1]: { id: child1, type: 'text', slots: [] },
        [child2]: { id: child2, type: 'text', slots: [] },
        [child3]: { id: child3, type: 'text', slots: [] },
      },
      slots: {
        [rootSlot]: { id: rootSlot, nodeId: rootId, name: 'children', children: [containerId] },
        [slot1]: { id: slot1, nodeId: containerId, name: 'column-0', children: [child1, child2] },
        [slot2]: { id: slot2, nodeId: containerId, name: 'column-1', children: [child3] },
      },
    });

    expect(countChildren(doc, containerId)).toBe(3);
  });

  it('returns 0 for a non-existent node', () => {
    const doc = createTestDocument();
    expect(countChildren(doc, nodeId('nonexistent'))).toBe(0);
  });

  it('returns 0 for a leaf node', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    expect(countChildren(doc, textNodeId)).toBe(0);
  });
});
