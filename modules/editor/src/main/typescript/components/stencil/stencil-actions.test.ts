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
  registry.register(
    createStencilDefinition({ callbacks: callbacks ?? createMockCallbacks() }),
  );
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
      stencilId: 'header', version: 1, isDraft: false,
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
      stencilId: 'header', version: 1, isDraft: true,
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
      stencilId: 'header', version: 1, isDraft: true,
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
      stencilId: 'header', version: 1, isDraft: false,
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
      stencilId: 'header', version: 1, isDraft: false,
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
      stencilId: 'header', version: 2, isDraft: false,
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
      stencilId: 'header', version: 1, isDraft: true,
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
      stencilId: 'header', version: 1, isDraft: false,
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
      stencilId: 'header', version: 3, isDraft: false,
    });

    engine.setComponentState('stencil:upgrades', { header: 3 });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('Stencil: header v3');
  });
});
