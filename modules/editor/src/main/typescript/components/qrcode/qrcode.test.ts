import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry } from '../../engine/registry.js';
import { createTestDocument, resetCounter } from '../../engine/test-helpers.js';
import {
  createQrCodeDefinition,
  createQrCodeLogoActions,
  resolveQrCodeLogoSrc,
} from './qrcode-registration.js';
import type { NodeId } from '../../types/index.js';
import { openAssetPickerDialog } from '../image/asset-picker-dialog.js';

vi.mock('../image/asset-picker-dialog.js', () => ({
  openAssetPickerDialog: vi.fn(),
}));

beforeEach(() => {
  resetCounter();
  vi.clearAllMocks();
});

function setupQrCodeEngine(overrideProps?: Record<string, unknown>) {
  const registry = createDefaultRegistry();
  registry.register(createQrCodeDefinition());
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

function templateToHtml(value: unknown): string {
  if (value == null || value === false) return '';
  if (typeof value === 'symbol') return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(templateToHtml).join('');
  if (typeof value === 'object' && 'strings' in value && 'values' in value) {
    const template = value as { strings: ArrayLike<string>; values: unknown[] };
    return Array.from(template.strings)
      .map(
        (part, index) =>
          part + (index < template.values.length ? templateToHtml(template.values[index]) : ''),
      )
      .join('');
  }
  return '';
}

// ---------------------------------------------------------------------------
// createNode
// ---------------------------------------------------------------------------

describe('QR code createNode', () => {
  it('produces a leaf node with QR defaults', () => {
    const registry = createDefaultRegistry();
    registry.register(createQrCodeDefinition());
    const { node, slots } = registry.createNode('qrcode');

    expect(node.type).toBe('qrcode');
    expect(node.slots).toEqual([]);
    expect(slots).toEqual([]);
    expect(node.props).toEqual({
      value: { raw: '', language: 'jsonata' },
      size: '120pt',
      qrType: 'standard',
      logoAssetId: null,
    });
  });

  it('merges override props on top of defaults', () => {
    const registry = createDefaultRegistry();
    registry.register(createQrCodeDefinition());
    const { node } = registry.createNode('qrcode', {
      value: { raw: 'customer.paymentLink', language: 'jsonata' },
      size: '96pt',
    });

    expect(node.props).toEqual({
      value: { raw: 'customer.paymentLink', language: 'jsonata' },
      size: '96pt',
      qrType: 'standard',
      logoAssetId: null,
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
    registry.register(createQrCodeDefinition());
    const types = registry.insertable().map((def) => def.type);

    expect(types).toContain('qrcode');
  });

  it('cannot contain child nodes', () => {
    const registry = createDefaultRegistry();
    registry.register(createQrCodeDefinition());

    expect(registry.canContain('qrcode', 'text')).toBe(false);
    expect(registry.canContain('qrcode', 'container')).toBe(false);
  });

  it('renderInspectorAfterProps returns null for standard type', () => {
    const def = createQrCodeDefinition();
    const out = def.renderInspectorAfterProps?.({
      node: {
        id: 'qr-1',
        type: 'qrcode',
        props: { qrType: 'standard', logoAssetId: null },
        slots: [],
      },
      engine: {} as EditorEngine,
    });

    expect(out).toBeNull();
  });

  it('renderInspectorAfterProps renders logo actions for logo type', () => {
    const def = createQrCodeDefinition();
    const out = def.renderInspectorAfterProps?.({
      node: {
        id: 'qr-1',
        type: 'qrcode',
        props: { qrType: 'logo', logoAssetId: null },
        slots: [],
      },
      engine: {} as EditorEngine,
    });

    const html = templateToHtml(out);
    expect(html).toContain('qrcode-logo-actions');
    expect(html).toContain('Select');
  });

  it('resolveQrCodeLogoSrc resolves logo URL when logo mode has asset and pattern', () => {
    expect(resolveQrCodeLogoSrc('logo', 'asset-123', '/api/assets/{assetId}/content')).toBe(
      '/api/assets/asset-123/content',
    );
  });

  it('resolveQrCodeLogoSrc returns null outside logo mode or without content pattern', () => {
    expect(
      resolveQrCodeLogoSrc('standard', 'asset-123', '/api/assets/{assetId}/content'),
    ).toBeNull();
    expect(resolveQrCodeLogoSrc('logo', 'asset-123')).toBeNull();
    expect(resolveQrCodeLogoSrc('logo', null, '/api/assets/{assetId}/content')).toBeNull();
  });

  it('pickLogo updates node props when asset is selected', async () => {
    const dispatch = vi.fn();
    vi.mocked(openAssetPickerDialog).mockResolvedValue({ id: 'asset-123' } as never);

    const node = {
      id: 'qr-1',
      type: 'qrcode',
      props: { qrType: 'logo', logoAssetId: null },
      slots: [],
    };

    const actions = createQrCodeLogoActions(node, { dispatch } as unknown as EditorEngine, {
      assetPicker: {} as never,
    });
    await actions.pickLogo();

    expect(openAssetPickerDialog).toHaveBeenCalledOnce();
    expect(dispatch).toHaveBeenCalledWith({
      type: 'UpdateNodeProps',
      nodeId: 'qr-1',
      props: {
        qrType: 'logo',
        logoAssetId: 'asset-123',
      },
    });
  });

  it('removeLogo clears logoAssetId', () => {
    const dispatch = vi.fn();

    const node = {
      id: 'qr-1',
      type: 'qrcode',
      props: { qrType: 'logo', logoAssetId: 'asset-123' },
      slots: [],
    };

    const actions = createQrCodeLogoActions(node, { dispatch } as unknown as EditorEngine);
    actions.removeLogo();

    expect(dispatch).toHaveBeenCalledWith({
      type: 'UpdateNodeProps',
      nodeId: 'qr-1',
      props: {
        qrType: 'logo',
        logoAssetId: null,
      },
    });
  });
});
