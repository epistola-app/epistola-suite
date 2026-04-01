import { beforeEach, describe, expect, it } from 'vitest';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry } from '../../engine/registry.js';
import { createTestDocument, resetCounter } from '../../engine/test-helpers.js';
import type { NodeId } from '../../types/index.js';

beforeEach(() => {
  resetCounter();
});

function setupQrCodeEngine(overrideProps?: Record<string, unknown>) {
  const registry = createDefaultRegistry();
  const doc = createTestDocument();
  const engine = new EditorEngine(doc, registry);
  const rootSlotId = doc.nodes[doc.root].slots[0];

  const { node, slots } = registry.createNode('qrcode', overrideProps);
  engine.dispatch({
    type: 'InsertNode',
    node,
    slots,
    targetSlotId: rootSlotId,
    index: -1,
  });

  return { engine, registry, qrCodeNodeId: node.id, rootSlotId };
}

function getQrCodeNode(engine: EditorEngine, qrCodeNodeId: NodeId) {
  return engine.doc.nodes[qrCodeNodeId];
}

// ---------------------------------------------------------------------------
// createNode
// ---------------------------------------------------------------------------

describe('QR code createNode', () => {
  it('produces a leaf node with QR defaults', () => {
    const registry = createDefaultRegistry();
    const { node, slots } = registry.createNode('qrcode');

    expect(node.type).toBe('qrcode');
    expect(node.slots).toEqual([]);
    expect(slots).toEqual([]);
    expect(node.props).toEqual({
      value: { raw: '', language: 'jsonata' },
      size: '120pt',
    });
  });

  it('merges override props on top of defaults', () => {
    const registry = createDefaultRegistry();
    const { node } = registry.createNode('qrcode', {
      value: { raw: 'customer.paymentLink', language: 'jsonata' },
      size: '96pt',
    });

    expect(node.props).toEqual({
      value: { raw: 'customer.paymentLink', language: 'jsonata' },
      size: '96pt',
    });
  });
});

// ---------------------------------------------------------------------------
// InsertNode / RemoveNode
// ---------------------------------------------------------------------------

describe('QR code InsertNode', () => {
  it('adds the node to the root slot', () => {
    const { engine, qrCodeNodeId, rootSlotId } = setupQrCodeEngine();

    expect(getQrCodeNode(engine, qrCodeNodeId)).toBeDefined();
    expect(engine.doc.slots[rootSlotId].children).toContain(qrCodeNodeId);
  });

  it('RemoveNode removes the QR code node', () => {
    const { engine, qrCodeNodeId } = setupQrCodeEngine();

    engine.dispatch({ type: 'RemoveNode', nodeId: qrCodeNodeId });

    expect(getQrCodeNode(engine, qrCodeNodeId)).toBeUndefined();
  });

  it('undo of RemoveNode restores the QR code node', () => {
    const { engine, qrCodeNodeId } = setupQrCodeEngine({ size: '88pt' });

    engine.dispatch({ type: 'RemoveNode', nodeId: qrCodeNodeId });
    engine.undo();

    expect(getQrCodeNode(engine, qrCodeNodeId)).toBeDefined();
    expect(getQrCodeNode(engine, qrCodeNodeId)?.props?.size).toBe('88pt');
  });
});

// ---------------------------------------------------------------------------
// Registry behavior
// ---------------------------------------------------------------------------

describe('QR code registry behavior', () => {
  it('is available in insertable()', () => {
    const registry = createDefaultRegistry();
    const types = registry.insertable().map((def) => def.type);

    expect(types).toContain('qrcode');
  });

  it('cannot contain child nodes', () => {
    const registry = createDefaultRegistry();

    expect(registry.canContain('qrcode', 'text')).toBe(false);
    expect(registry.canContain('qrcode', 'container')).toBe(false);
  });
});
