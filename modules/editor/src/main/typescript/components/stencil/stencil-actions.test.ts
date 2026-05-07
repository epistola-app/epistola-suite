/**
 * Tests for stencil inspector action logic.
 *
 * Tests the core operations that the StencilInspector triggers:
 * - Publishing as stencil (extract + callback + update props)
 * - Start editing (callback + update props)
 * - Save to draft (extract + callback)
 * - Discard changes (replace content + update props)
 * - Upgrade (replace content + update props)
 * - Detach (clear props)
 * - Replace content (remove old + insert new)
 * - getLabel dynamic labeling
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry, type ComponentRegistry } from '../../engine/registry.js';
import { createTestDocument, resetCounter, nodeId, slotId } from '../../engine/test-helpers.js';
import { createStencilDefinition } from './stencil-registration.js';
import { extractSubtree } from './extract-subtree.js';
import { reKeyContent } from './rekey-content.js';
import type { TemplateDocument, NodeId, SlotId, Node, Slot } from '../../types/index.js';
import type { StencilCallbacks } from './types.js';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createMockCallbacks(overrides?: Partial<StencilCallbacks>): StencilCallbacks {
  return {
    searchStencils: vi.fn().mockResolvedValue([]),
    listVersions: vi.fn().mockResolvedValue([]),
    getStencilVersion: vi.fn().mockResolvedValue(null),
    publishAsStencil: vi.fn().mockResolvedValue({ stencilId: 'new-stencil', version: 1 }),
    updateStencil: vi.fn().mockResolvedValue({ version: 2 }),
    startEditing: vi.fn().mockResolvedValue({ draftVersion: 2 }),
    publishDraft: vi.fn().mockResolvedValue({ version: 2 }),
    ...overrides,
  };
}

function setupEngine(callbacks?: StencilCallbacks) {
  const registry = createDefaultRegistry();
  registry.register(createStencilDefinition({ callbacks: callbacks ?? createMockCallbacks() }));
  const doc = createTestDocument();
  const engine = new EditorEngine(doc, registry);
  const rootSlotId = doc.nodes[doc.root].slots[0];
  return { engine, registry, rootSlotId };
}

function insertStencil(
  engine: EditorEngine,
  registry: ComponentRegistry,
  targetSlotId: SlotId,
  overrideProps?: Record<string, unknown>,
): NodeId {
  const { node, slots, extraNodes } = registry.createNode('stencil', overrideProps);
  engine.dispatch({
    type: 'InsertNode',
    node,
    slots,
    targetSlotId,
    index: -1,
    _restoreNodes: extraNodes,
  });
  return node.id;
}

function insertText(
  engine: EditorEngine,
  registry: ComponentRegistry,
  targetSlotId: SlotId,
  content?: string,
): NodeId {
  const { node, slots } = registry.createNode('text', content ? { content } : undefined);
  engine.dispatch({
    type: 'InsertNode',
    node,
    slots,
    targetSlotId,
    index: -1,
  });
  return node.id;
}

function getStencilSlot(engine: EditorEngine, stencilNodeId: NodeId): SlotId {
  return engine.doc.nodes[stencilNodeId].slots[0];
}

function createSampleContent(): TemplateDocument {
  const rootId = nodeId('sample-root');
  const rootSlot = slotId('sample-root-slot');
  const textId = nodeId('sample-text');

  return {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: { id: rootId, type: 'root', slots: [rootSlot] },
      [textId]: { id: textId, type: 'text', slots: [], props: { content: 'Sample' } },
    } as Record<NodeId, Node>,
    slots: {
      [rootSlot]: { id: rootSlot, nodeId: rootId, name: 'children', children: [textId] },
    } as Record<SlotId, Slot>,
    themeRef: { type: 'inherit' },
  };
}

beforeEach(() => {
  resetCounter();
});

// ---------------------------------------------------------------------------
// Extract subtree for publishing/updating
// ---------------------------------------------------------------------------

describe('extractSubtree for inspector actions', () => {
  it('extracts stencil content as standalone document for publishing', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);

    insertText(engine, registry, stencilSlot, 'Header text');
    insertText(engine, registry, stencilSlot, 'Subtitle');

    const extracted = extractSubtree(engine.doc, stencilId);

    // Should have a root + 2 text nodes
    const textNodes = Object.values(extracted.nodes).filter((n) => n.type === 'text');
    expect(textNodes).toHaveLength(2);
    expect(textNodes.some((n) => n.props?.content === 'Header text')).toBe(true);
    expect(textNodes.some((n) => n.props?.content === 'Subtitle')).toBe(true);
  });

  it('extracted content is a valid standalone document', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);
    insertText(engine, registry, stencilSlot, 'Content');

    const extracted = extractSubtree(engine.doc, stencilId);

    // Has a root
    expect(extracted.root).toBeDefined();
    expect(extracted.nodes[extracted.root]).toBeDefined();

    // Root has a slot with children
    const rootNode = extracted.nodes[extracted.root];
    expect(rootNode.slots).toHaveLength(1);
    const rootSlot = extracted.slots[rootNode.slots[0]];
    expect(rootSlot).toBeDefined();
    expect(rootSlot.children.length).toBeGreaterThan(0);

    // All referenced nodes exist
    for (const slot of Object.values(extracted.slots)) {
      for (const childId of slot.children) {
        expect(extracted.nodes[childId]).toBeDefined();
      }
    }
  });

  it('strips placeholder fill slot children but keeps the slot record', () => {
    // Build a doc directly: stencil → placeholder(default + fill, both populated).
    // The user override (fill content) must not round-trip into the stencil
    // definition — extractSubtree returns the fill slot empty.
    const doc = {
      modelVersion: 1,
      root: 'root',
      nodes: {
        root: { id: 'root', type: 'root', slots: ['rs'] },
        stencil: {
          id: 'stencil',
          type: 'stencil',
          slots: ['stencil-slot'],
          props: { stencilId: 'A', version: 1, isDraft: true },
        },
        ph: {
          id: 'ph',
          type: 'placeholder',
          slots: ['ph-default', 'ph-fill'],
          props: { name: 'body' },
        },
        defaultText: {
          id: 'defaultText',
          type: 'text',
          slots: [],
          props: { content: 'default content' },
        },
        fillText: {
          id: 'fillText',
          type: 'text',
          slots: [],
          props: { content: 'override content' },
        },
      },
      slots: {
        rs: { id: 'rs', nodeId: 'root', name: 'children', children: ['stencil'] },
        'stencil-slot': {
          id: 'stencil-slot',
          nodeId: 'stencil',
          name: 'children',
          children: ['ph'],
        },
        'ph-default': {
          id: 'ph-default',
          nodeId: 'ph',
          name: 'default',
          children: ['defaultText'],
        },
        'ph-fill': {
          id: 'ph-fill',
          nodeId: 'ph',
          name: 'fill',
          children: ['fillText'],
        },
      },
      themeRef: { type: 'inherit' },
    } as unknown as TemplateDocument;

    const extracted = extractSubtree(doc, 'stencil' as NodeId);

    // The placeholder is included.
    const ph = Object.values(extracted.nodes).find((n) => n.type === 'placeholder');
    expect(ph).toBeDefined();

    // Default content survives.
    const texts = Object.values(extracted.nodes).filter((n) => n.type === 'text');
    expect(texts.some((n) => n.props?.content === 'default content')).toBe(true);

    // Fill content was stripped.
    expect(texts.some((n) => n.props?.content === 'override content')).toBe(false);

    // Fill slot is still present (structurally) but empty.
    const fillSlot = Object.values(extracted.slots).find((s) => s.name === 'fill');
    expect(fillSlot).toBeDefined();
    expect(fillSlot!.children).toHaveLength(0);
  });
});

// ---------------------------------------------------------------------------
// Replace content (upgrade/discard flow)
// ---------------------------------------------------------------------------

describe('Content replacement flow', () => {
  it('removes existing children and inserts new content', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);

    // Add original content
    insertText(engine, registry, stencilSlot, 'Original');
    expect(engine.doc.slots[stencilSlot].children).toHaveLength(1);

    // Remove existing children
    while (engine.doc.slots[stencilSlot].children.length > 0) {
      engine.dispatch({
        type: 'RemoveNode',
        nodeId: engine.doc.slots[stencilSlot].children[0],
      });
    }
    expect(engine.doc.slots[stencilSlot].children).toHaveLength(0);

    // Insert new content (simulating upgrade)
    const newContent = createSampleContent();
    const reKeyed = reKeyContent(newContent);

    const nodeById = new Map(reKeyed.nodes.map((n) => [n.id as string, n]));
    const slotById = new Map(reKeyed.slots.map((s) => [s.id as string, s]));

    for (const childId of reKeyed.childNodeIds) {
      const childNode = nodeById.get(childId as string)!;
      const ownSlots = childNode.slots
        .map((sid) => slotById.get(sid as string))
        .filter(Boolean) as Slot[];

      engine.dispatch({
        type: 'InsertNode',
        node: childNode,
        slots: ownSlots,
        targetSlotId: stencilSlot,
        index: -1,
      });
    }

    // Verify new content is there
    expect(engine.doc.slots[stencilSlot].children).toHaveLength(1);
    const newChild = engine.doc.nodes[engine.doc.slots[stencilSlot].children[0]];
    expect(newChild.type).toBe('text');
    expect(newChild.props?.content).toBe('Sample');
  });

  it('replacement is undoable', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);

    insertText(engine, registry, stencilSlot, 'Original');
    const originalChildId = engine.doc.slots[stencilSlot].children[0];

    // Remove + insert new
    engine.dispatch({ type: 'RemoveNode', nodeId: originalChildId });
    const newContent = createSampleContent();
    const reKeyed = reKeyContent(newContent);
    for (const childId of reKeyed.childNodeIds) {
      const childNode = reKeyed.nodes.find((n) => n.id === childId)!;
      engine.dispatch({
        type: 'InsertNode',
        node: childNode,
        slots: [],
        targetSlotId: stencilSlot,
        index: -1,
      });
    }

    // Undo the insert
    engine.undo();
    // Undo the remove
    engine.undo();

    // Original content is back
    expect(engine.doc.slots[stencilSlot].children).toContain(originalChildId);
    expect(engine.doc.nodes[originalChildId].props?.content).toBe('Original');
  });
});

// ---------------------------------------------------------------------------
// Props state transitions
// ---------------------------------------------------------------------------

describe('Stencil props state transitions', () => {
  it('publish as stencil: sets stencilId, version, isDraft=false', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    // Simulate publish action
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: 'new-stencil', version: 1, isDraft: false },
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBe('new-stencil');
    expect(node.props?.version).toBe(1);
    expect(node.props?.isDraft).toBe(false);
  });

  it('start editing: sets isDraft=true', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, isDraft: true },
    });

    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(true);
  });

  it('publish draft: sets version to new, isDraft=false', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: true,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, version: 2, isDraft: false },
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(2);
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });

  it('discard: sets isDraft=false, keeps original version', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: true,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, isDraft: false },
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(1);
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });

  it('upgrade: sets version to latest', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, version: 3 },
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(3);
  });

  it('detach: clears stencilId, version, isDraft', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: null, version: null, isDraft: false },
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBeNull();
    expect(node.props?.version).toBeNull();
    expect(node.props?.isDraft).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// getLabel dynamic labeling
// ---------------------------------------------------------------------------

describe('getLabel', () => {
  it('returns "Stencil" for unlinked node', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId);

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('Stencil');
  });

  it('returns name + version for locked stencil', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 2,
      isDraft: false,
    });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('Stencil: header v2');
  });

  it('returns editing draft label', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: true,
    });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('Stencil: header — editing draft');
  });

  it('shows upgrade indicator when newer version available', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    // Set upgrade state
    engine.setComponentState('stencil:upgrades', { header: 3 });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('Stencil: header v1 ⬆ v3');
  });

  it('no upgrade indicator when on latest version', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 3,
      isDraft: false,
    });

    engine.setComponentState('stencil:upgrades', { header: 3 });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('Stencil: header v3');
  });
});

// ---------------------------------------------------------------------------
// Full inspector action flows (callback + engine interaction)
// ---------------------------------------------------------------------------

describe('Inspector action flows with callbacks', () => {
  it('publish as stencil: calls callback with extracted content and updates props', async () => {
    const callbacks = createMockCallbacks({
      publishAsStencil: vi.fn().mockResolvedValue({ stencilId: 'new-header', version: 1 }),
    });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const nodeId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, nodeId);
    insertText(engine, registry, stencilSlot, 'Header content');

    // Simulate what _handlePublish does (minus prompt)
    const content = extractSubtree(engine.doc, nodeId);
    const result = await callbacks.publishAsStencil!('new-header', 'New Header', content);

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId,
      props: {
        ...engine.doc.nodes[nodeId].props,
        stencilId: result.stencilId,
        version: result.version,
        isDraft: false,
      },
    });

    expect(callbacks.publishAsStencil).toHaveBeenCalledOnce();
    expect(engine.doc.nodes[nodeId].props?.stencilId).toBe('new-header');
    expect(engine.doc.nodes[nodeId].props?.version).toBe(1);
    expect(engine.doc.nodes[nodeId].props?.isDraft).toBe(false);
  });

  it('start editing: calls callback and sets isDraft', async () => {
    const callbacks = createMockCallbacks({
      startEditing: vi.fn().mockResolvedValue({ draftVersion: 2 }),
    });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    // Simulate _handleStartEditing
    await callbacks.startEditing!('header');

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId,
      props: { ...engine.doc.nodes[nodeId].props, isDraft: true },
    });

    expect(callbacks.startEditing).toHaveBeenCalledWith('header');
    expect(engine.doc.nodes[nodeId].props?.isDraft).toBe(true);
  });

  it('save to draft: calls callback with extracted content', async () => {
    const callbacks = createMockCallbacks({
      updateStencil: vi.fn().mockResolvedValue({ version: 2 }),
    });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: true,
    });
    const stencilSlot = getStencilSlot(engine, nodeId);
    insertText(engine, registry, stencilSlot, 'Edited content');

    // Simulate _handleSaveDraft
    const content = extractSubtree(engine.doc, nodeId);
    await callbacks.updateStencil!('header', content);

    expect(callbacks.updateStencil).toHaveBeenCalledWith(
      'header',
      expect.objectContaining({
        modelVersion: 1,
        root: expect.any(String),
      }),
    );
  });

  it('upgrade: replaces content and updates version', async () => {
    const newContent = createSampleContent();
    const callbacks = createMockCallbacks({
      getStencilVersion: vi.fn().mockResolvedValue({
        stencilId: 'header',
        stencilName: 'Header',
        version: 3,
        content: newContent,
      }),
    });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });
    const stencilSlot = getStencilSlot(engine, nodeId);
    // Seed some "old content" via bypassLock — the stencil is locked, so
    // mirroring the production inspector path which uses the same bypass
    // for whole-stencil swaps.
    {
      const { node, slots } = registry.createNode('text', { content: 'Old content' });
      engine.dispatch(
        { type: 'InsertNode', node, slots, targetSlotId: stencilSlot, index: -1 },
        { bypassLock: true },
      );
    }

    // Simulate _handleUpgrade: remove old content, insert new, update props.
    // Production passes `bypassLock: true` because the locked-stencil's
    // children slot would otherwise reject these mutations.
    const versionInfo = await callbacks.getStencilVersion('header', 3);

    // Remove old children
    while (engine.doc.slots[stencilSlot].children.length > 0) {
      engine.dispatch(
        { type: 'RemoveNode', nodeId: engine.doc.slots[stencilSlot].children[0] },
        { bypassLock: true },
      );
    }

    // Insert re-keyed new content
    const reKeyed = reKeyContent(versionInfo!.content);
    for (const childId of reKeyed.childNodeIds) {
      const childNode = reKeyed.nodes.find((n) => n.id === childId)!;
      const ownSlots = reKeyed.slots.filter((s) => s.nodeId === childId);
      const descNodes = reKeyed.nodes.filter((n) => n.id !== childId);
      engine.dispatch(
        {
          type: 'InsertNode',
          node: childNode,
          slots: ownSlots,
          targetSlotId: stencilSlot,
          index: -1,
          _restoreNodes: descNodes.length > 0 ? descNodes : undefined,
        },
        { bypassLock: true },
      );
    }

    engine.dispatch(
      {
        type: 'UpdateNodeProps',
        nodeId,
        props: { ...engine.doc.nodes[nodeId].props, version: 3 },
      },
      { bypassLock: true },
    );

    expect(callbacks.getStencilVersion).toHaveBeenCalledWith('header', 3);
    expect(engine.doc.nodes[nodeId].props?.version).toBe(3);
    // New content present
    const newChildren = engine.doc.slots[stencilSlot].children;
    expect(newChildren.length).toBeGreaterThan(0);
    const textNodes = newChildren
      .map((id) => engine.doc.nodes[id])
      .filter((n) => n?.type === 'text' && n?.props?.content === 'Sample');
    expect(textNodes).toHaveLength(1);
  });

  it('publish draft: saves content then publishes', async () => {
    const callbacks = createMockCallbacks({
      updateStencil: vi.fn().mockResolvedValue({ version: 2 }),
      publishDraft: vi.fn().mockResolvedValue({ version: 2 }),
    });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: true,
    });
    const stencilSlot = getStencilSlot(engine, nodeId);
    insertText(engine, registry, stencilSlot, 'Draft content');

    // Simulate _handlePublishDraft: save then publish
    const content = extractSubtree(engine.doc, nodeId);
    await callbacks.updateStencil!('header', content);
    const result = await callbacks.publishDraft!('header', 2);

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId,
      props: { ...engine.doc.nodes[nodeId].props, version: result.version, isDraft: false },
    });

    expect(callbacks.updateStencil).toHaveBeenCalledOnce();
    expect(callbacks.publishDraft).toHaveBeenCalledWith('header', 2);
    expect(engine.doc.nodes[nodeId].props?.version).toBe(2);
    expect(engine.doc.nodes[nodeId].props?.isDraft).toBe(false);
  });

  it('detach converts stencil to container via ReplaceNode', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });
    const stencilSlot = getStencilSlot(engine, nodeId);
    // Seed content via bypassLock — the stencil's children slot is locked
    // while published, but a real inspector flow uses bypassLock for the
    // pre-detach state too.
    {
      const { node, slots } = registry.createNode('text', { content: 'Content' });
      engine.dispatch(
        { type: 'InsertNode', node, slots, targetSlotId: stencilSlot, index: -1 },
        { bypassLock: true },
      );
    }

    // Detach itself targets the stencil node's parent slot (root), not the
    // stencil's children slot. Root isn't locked, so this works without bypass.
    engine.dispatch({
      type: 'ReplaceNode',
      nodeId,
      newType: 'container',
      newProps: {},
    });

    const node = engine.doc.nodes[nodeId];
    expect(node.type).toBe('container');
    expect(node.props).toEqual({});
    expect(engine.doc.slots[stencilSlot].children).toHaveLength(1);
  });

  it('detach is undoable — restores stencil type and props', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    engine.dispatch({
      type: 'ReplaceNode',
      nodeId,
      newType: 'container',
      newProps: {},
    });
    expect(engine.doc.nodes[nodeId].type).toBe('container');

    engine.undo();
    expect(engine.doc.nodes[nodeId].type).toBe('stencil');
    expect(engine.doc.nodes[nodeId].props?.stencilId).toBe('header');
    expect(engine.doc.nodes[nodeId].props?.version).toBe(1);
  });

  it('callback failure does not corrupt engine state', async () => {
    const callbacks = createMockCallbacks({
      startEditing: vi.fn().mockRejectedValue(new Error('Server error')),
    });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    // Simulate _handleStartEditing with failure
    try {
      await callbacks.startEditing!('header');
    } catch {
      // Error caught — engine state should NOT have changed
    }

    expect(engine.doc.nodes[nodeId].props?.isDraft).toBe(false);
    expect(engine.doc.nodes[nodeId].props?.version).toBe(1);
  });
});

// ---------------------------------------------------------------------------
// catalogKey propagation
// ---------------------------------------------------------------------------

describe('catalogKey propagation', () => {
  it('stencil node preserves catalogKey in props', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
      catalogKey: 'my-catalog',
    });

    expect(engine.doc.nodes[nodeId].props?.catalogKey).toBe('my-catalog');
  });

  it('stencil node without catalogKey defaults to null', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    expect(engine.doc.nodes[nodeId].props?.catalogKey).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Edit / Publish flow with two-slot placeholder semantics
// ---------------------------------------------------------------------------

/** Helper: insert a stencil instance with one placeholder having default + fill content. */
function setupStencilWithFilledPlaceholder(
  engine: EditorEngine,
  registry: ComponentRegistry,
  rootSlotId: SlotId,
  stencilProps: Record<string, unknown> = { stencilId: 'header', version: 1, isDraft: false },
): { stencilId: NodeId; placeholderId: NodeId; fillSlotId: SlotId; defaultSlotId: SlotId } {
  // Insert a stencil node directly via dispatch (bypassing onBeforeInsert).
  const stencilId = nodeId('stencil');
  const stencilSlotId = slotId('stencil-slot');
  const placeholderId = nodeId('ph');
  const defaultSlotId = slotId('ph-default');
  const fillSlotId = slotId('ph-fill');
  const defaultText = nodeId('default-text');
  const fillText = nodeId('fill-text');

  engine.dispatch({
    type: 'InsertNode',
    node: {
      id: stencilId,
      type: 'stencil',
      slots: [stencilSlotId],
      props: stencilProps,
    },
    slots: [
      { id: stencilSlotId, nodeId: stencilId, name: 'children', children: [placeholderId] },
      {
        id: defaultSlotId,
        nodeId: placeholderId,
        name: 'default',
        children: [defaultText],
      },
      { id: fillSlotId, nodeId: placeholderId, name: 'fill', children: [fillText] },
    ],
    targetSlotId: rootSlotId,
    index: -1,
    _restoreNodes: [
      {
        id: placeholderId,
        type: 'placeholder',
        slots: [defaultSlotId, fillSlotId],
        props: { name: 'body' },
      },
      {
        id: defaultText,
        type: 'text',
        slots: [],
        props: { content: 'default content' },
      },
      {
        id: fillText,
        type: 'text',
        slots: [],
        props: { content: 'override content' },
      },
    ],
  });
  return { stencilId, placeholderId, fillSlotId, defaultSlotId };
}

describe('Edit/Publish flow with two-slot placeholder', () => {
  it('start editing leaves the local fill content intact', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const { stencilId, fillSlotId } = setupStencilWithFilledPlaceholder(
      engine,
      registry,
      rootSlotId,
    );

    const fillBefore = [...engine.doc.slots[fillSlotId].children];

    // Simulate _handleStartEditing's only mutation: flip isDraft=true.
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, isDraft: true },
    });

    // Fill content unchanged.
    expect(engine.doc.slots[fillSlotId].children).toEqual(fillBefore);
    expect(engine.doc.nodes[fillBefore[0]]?.props?.content).toBe('override content');
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(true);
  });

  it('publish (extractSubtree + UpdateNodeProps) leaves the local fill content intact', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const { stencilId, fillSlotId } = setupStencilWithFilledPlaceholder(
      engine,
      registry,
      rootSlotId,
      { stencilId: 'header', version: 1, isDraft: true },
    );

    const fillBefore = [...engine.doc.slots[fillSlotId].children];

    // Mirror _handlePublishDraft: extract content (fills stripped), then
    // flip isDraft=false. The extracted content goes to the backend; the
    // local doc isn't mutated by extractSubtree.
    const extracted = extractSubtree(engine.doc, stencilId);
    // Fills should be stripped in the extracted content.
    const extractedFillSlot = Object.values(extracted.slots).find((s) => s.name === 'fill');
    expect(extractedFillSlot?.children).toEqual([]);
    // But locally the fill is still there.
    expect(engine.doc.slots[fillSlotId].children).toEqual(fillBefore);

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, version: 2, isDraft: false },
    });

    // Local fill survives the whole edit-and-publish cycle.
    expect(engine.doc.slots[fillSlotId].children).toEqual(fillBefore);
    expect(engine.doc.nodes[fillBefore[0]]?.props?.content).toBe('override content');
    expect(engine.doc.nodes[stencilId].props?.version).toBe(2);
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Direct tests of the stencil-actions module (no inspector / Lit involved)
// ---------------------------------------------------------------------------

import * as actions from './stencil-actions.js';

describe('stencil-actions module', () => {
  function setupCtx(stencilProps: Record<string, unknown>, callbacks: StencilCallbacks) {
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, stencilProps);
    const ctx: actions.StencilActionContext = {
      engine,
      callbacks,
      stencilNodeId: stencilId,
    };
    return { engine, registry, rootSlotId, stencilId, ctx };
  }

  it('startEditing calls the callback and flips isDraft=true on the local stencil', async () => {
    const callbacks = createMockCallbacks({
      startEditing: vi.fn().mockResolvedValue({ draftVersion: 7 }),
    });
    const { engine, stencilId, ctx } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 1, isDraft: false },
      callbacks,
    );

    const result = await actions.startEditing(ctx);

    expect(result).toEqual({ draftVersion: 7 });
    expect(callbacks.startEditing).toHaveBeenCalledWith({
      stencilId: 'header',
      catalogKey: 'cat',
    });
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(true);
  });

  it('startEditing throws when the stencil is unlinked', async () => {
    const callbacks = createMockCallbacks();
    const { ctx } = setupCtx({ stencilId: null, catalogKey: null }, callbacks);
    await expect(actions.startEditing(ctx)).rejects.toThrow(/not linked/);
  });

  it('saveDraft strips fills before sending to the backend', async () => {
    const updateStencil = vi.fn().mockResolvedValue({ version: 3 });
    const callbacks = createMockCallbacks({ updateStencil });
    const { ctx, stencilId, engine, registry, rootSlotId } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 2, isDraft: true },
      callbacks,
    );
    // Place a placeholder with both default + fill content under the stencil.
    const phNode = nodeId('ph');
    const defaultSlot = slotId('ph-default');
    const fillSlot = slotId('ph-fill');
    const defaultText = nodeId('default-text');
    const fillText = nodeId('fill-text');
    const stencilSlot = engine.doc.nodes[stencilId].slots[0];
    engine.dispatch({
      type: 'InsertNode',
      node: {
        id: phNode,
        type: 'placeholder',
        slots: [defaultSlot, fillSlot],
        props: { name: 'body' },
      },
      slots: [
        { id: defaultSlot, nodeId: phNode, name: 'default', children: [defaultText] },
        { id: fillSlot, nodeId: phNode, name: 'fill', children: [fillText] },
      ],
      targetSlotId: stencilSlot,
      index: -1,
      _restoreNodes: [
        { id: defaultText, type: 'text', slots: [], props: { content: 'default content' } },
        { id: fillText, type: 'text', slots: [], props: { content: 'override content' } },
      ],
    });

    const result = await actions.saveDraft(ctx);
    expect(result).toEqual({ version: 3 });
    expect(updateStencil).toHaveBeenCalledOnce();
    const sentContent = updateStencil.mock.calls[0][1] as TemplateDocument;
    // Fill slot exists in the sent content but is empty.
    const fillSlotInSent = Object.values(sentContent.slots).find((s) => s.name === 'fill');
    expect(fillSlotInSent).toBeDefined();
    expect(fillSlotInSent!.children).toEqual([]);
    // Default content survives.
    const defaultTextInSent = Object.values(sentContent.nodes).find(
      (n) => n.type === 'text' && n.props?.content === 'default content',
    );
    expect(defaultTextInSent).toBeDefined();
    // Override content is NOT sent to the backend.
    const fillTextInSent = Object.values(sentContent.nodes).find(
      (n) => n.type === 'text' && n.props?.content === 'override content',
    );
    expect(fillTextInSent).toBeUndefined();
  });

  it('publishDraft saves then publishes and updates local node props', async () => {
    const updateStencil = vi.fn().mockResolvedValue({ version: 5 });
    const publishDraft = vi.fn().mockResolvedValue({ version: 5 });
    const callbacks = createMockCallbacks({ updateStencil, publishDraft });
    const { engine, stencilId, ctx } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 4, isDraft: true },
      callbacks,
    );

    const result = await actions.publishDraft(ctx, 5);

    expect(result).toEqual({ version: 5 });
    expect(updateStencil).toHaveBeenCalledOnce();
    expect(publishDraft).toHaveBeenCalledWith({ stencilId: 'header', catalogKey: 'cat' }, 5);
    expect(engine.doc.nodes[stencilId].props?.version).toBe(5);
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });

  it('publishDraft does NOT swap local content — fills survive', async () => {
    const updateStencil = vi.fn().mockResolvedValue({ version: 5 });
    const publishDraft = vi.fn().mockResolvedValue({ version: 5 });
    const callbacks = createMockCallbacks({ updateStencil, publishDraft });
    const { engine, stencilId, ctx } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 4, isDraft: true },
      callbacks,
    );
    // Stencil has a placeholder with override.
    const phNode = nodeId('ph');
    const defaultSlot = slotId('ph-default');
    const fillSlot = slotId('ph-fill');
    const fillText = nodeId('fill-text');
    const stencilSlot = engine.doc.nodes[stencilId].slots[0];
    engine.dispatch({
      type: 'InsertNode',
      node: {
        id: phNode,
        type: 'placeholder',
        slots: [defaultSlot, fillSlot],
        props: { name: 'body' },
      },
      slots: [
        { id: defaultSlot, nodeId: phNode, name: 'default', children: [] },
        { id: fillSlot, nodeId: phNode, name: 'fill', children: [fillText] },
      ],
      targetSlotId: stencilSlot,
      index: -1,
      _restoreNodes: [{ id: fillText, type: 'text', slots: [], props: { content: 'override' } }],
    });

    await actions.publishDraft(ctx, 5);

    expect(engine.doc.slots[fillSlot].children).toEqual([fillText]);
    expect(engine.doc.nodes[fillText]?.props?.content).toBe('override');
  });

  it('discard reverts to the published version and preserves user fills', async () => {
    // v1 stencil content: placeholder body with default "v1 default".
    const v1: TemplateDocument = {
      modelVersion: 1,
      root: 'v1-root' as NodeId,
      nodes: {
        'v1-root': {
          id: 'v1-root' as NodeId,
          type: 'root',
          slots: ['v1-rs' as SlotId],
        },
        'v1-ph': {
          id: 'v1-ph' as NodeId,
          type: 'placeholder',
          slots: ['v1-default' as SlotId, 'v1-fill' as SlotId],
          props: { name: 'body' },
        },
        'v1-default-text': {
          id: 'v1-default-text' as NodeId,
          type: 'text',
          slots: [],
          props: { content: 'v1 default' },
        },
      },
      slots: {
        'v1-rs': {
          id: 'v1-rs' as SlotId,
          nodeId: 'v1-root' as NodeId,
          name: 'children',
          children: ['v1-ph' as NodeId],
        },
        'v1-default': {
          id: 'v1-default' as SlotId,
          nodeId: 'v1-ph' as NodeId,
          name: 'default',
          children: ['v1-default-text' as NodeId],
        },
        'v1-fill': {
          id: 'v1-fill' as SlotId,
          nodeId: 'v1-ph' as NodeId,
          name: 'fill',
          children: [],
        },
      },
      themeRef: { type: 'inherit' },
    };
    const callbacks = createMockCallbacks({
      getStencilVersion: vi.fn().mockResolvedValue({
        ref: { stencilId: 'header', catalogKey: 'cat' },
        stencilName: 'Header',
        version: 1,
        content: v1,
      }),
    });
    const { engine, stencilId, ctx } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 1, isDraft: true },
      callbacks,
    );
    // Seed the local stencil with a placeholder + edited default + filled override.
    const phNode = nodeId('ph');
    const defaultSlot = slotId('ph-default');
    const fillSlot = slotId('ph-fill');
    const editedDefaultText = nodeId('edited-default');
    const fillText = nodeId('fill-text');
    const stencilSlot = engine.doc.nodes[stencilId].slots[0];
    engine.dispatch(
      {
        type: 'InsertNode',
        node: {
          id: phNode,
          type: 'placeholder',
          slots: [defaultSlot, fillSlot],
          props: { name: 'body' },
        },
        slots: [
          { id: defaultSlot, nodeId: phNode, name: 'default', children: [editedDefaultText] },
          { id: fillSlot, nodeId: phNode, name: 'fill', children: [fillText] },
        ],
        targetSlotId: stencilSlot,
        index: -1,
        _restoreNodes: [
          {
            id: editedDefaultText,
            type: 'text',
            slots: [],
            props: { content: 'edited (to be discarded)' },
          },
          { id: fillText, type: 'text', slots: [], props: { content: 'user override' } },
        ],
      },
      { bypassLock: true },
    );

    await actions.discard(ctx, 1);

    // The edited default was discarded; the new placeholder has v1's default.
    const newPlaceholders = Object.values(engine.doc.nodes).filter((n) => n.type === 'placeholder');
    expect(newPlaceholders).toHaveLength(1);
    const newPh = newPlaceholders[0];
    const newDefaultSlotId = newPh.slots.find((s) => engine.doc.slots[s]?.name === 'default')!;
    const newFillSlotId = newPh.slots.find((s) => engine.doc.slots[s]?.name === 'fill')!;
    expect(engine.doc.nodes[engine.doc.slots[newDefaultSlotId].children[0]]?.props?.content).toBe(
      'v1 default',
    );
    // The user's fill was preserved by name.
    expect(engine.doc.slots[newFillSlotId].children).toHaveLength(1);
    expect(engine.doc.nodes[engine.doc.slots[newFillSlotId].children[0]]?.props?.content).toBe(
      'user override',
    );
    // Stencil exits draft mode.
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });

  it('detach replaces the stencil node type with container', () => {
    const callbacks = createMockCallbacks();
    const { engine, stencilId, ctx } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 1, isDraft: false },
      callbacks,
    );
    actions.detach(ctx);
    expect(engine.doc.nodes[stencilId].type).toBe('container');
  });

  it('loadDraftVersion returns the draft version when one exists', async () => {
    const listVersions = vi.fn().mockResolvedValue([
      { version: 1, status: 'published' },
      { version: 2, status: 'draft' },
    ]);
    const callbacks = createMockCallbacks({ listVersions });
    const { ctx } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 1, isDraft: true },
      callbacks,
    );
    expect(await actions.loadDraftVersion(ctx)).toBe(2);
  });

  it('loadDraftVersion returns null when there is no draft', async () => {
    const listVersions = vi.fn().mockResolvedValue([{ version: 1, status: 'published' }]);
    const callbacks = createMockCallbacks({ listVersions });
    const { ctx } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 1, isDraft: false },
      callbacks,
    );
    expect(await actions.loadDraftVersion(ctx)).toBeNull();
  });

  it('findLatestPublishedVersion picks the highest published version', async () => {
    const listVersions = vi.fn().mockResolvedValue([
      { version: 1, status: 'published' },
      { version: 2, status: 'published' },
      { version: 3, status: 'draft' },
    ]);
    const callbacks = createMockCallbacks({ listVersions });
    const { ctx } = setupCtx(
      { stencilId: 'header', catalogKey: 'cat', version: 1, isDraft: false },
      callbacks,
    );
    expect(await actions.findLatestPublishedVersion(ctx)).toBe(2);
  });
});
