/**
 * Stencil component — comprehensive test suite.
 *
 * Tests cover:
 * - Component registration and creation
 * - Inserting empty stencils
 * - Inserting stencils with existing content (re-keying)
 * - Multiple stencils on one page
 * - Stencil nesting prevention
 * - Props and state management (stencilId, version, isDraft)
 * - extractSubtree utility
 * - reKeyContent utility
 * - Undo/redo for stencil operations
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry, type ComponentRegistry } from '../../engine/registry.js';
import { createTestDocument, resetCounter, nodeId, slotId } from '../../engine/test-helpers.js';
import { createStencilDefinition } from './stencil-registration.js';
import { reKeyContent } from './rekey-content.js';
import { extractSubtree } from './extract-subtree.js';
import type { TemplateDocument, NodeId, SlotId, Node, Slot } from '../../types/index.js';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function setupEngine(opts?: { withCallbacks?: boolean }) {
  const registry = createDefaultRegistry();
  registry.register(
    createStencilDefinition({
      callbacks: opts?.withCallbacks
        ? {
            searchStencils: async () => [],
            getStencilVersion: async () => null,
          }
        : null,
    }),
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
  const result = engine.dispatch({
    type: 'InsertNode',
    node,
    slots,
    targetSlotId,
    index: -1,
    _restoreNodes: extraNodes,
  });
  expect(result.ok).toBe(true);
  return node.id;
}

function insertText(
  engine: EditorEngine,
  registry: ComponentRegistry,
  targetSlotId: SlotId,
  content?: string,
): NodeId {
  const { node, slots } = registry.createNode('text', content ? { content } : undefined);
  const result = engine.dispatch({
    type: 'InsertNode',
    node,
    slots,
    targetSlotId,
    index: -1,
  });
  expect(result.ok).toBe(true);
  return node.id;
}

function getStencilSlot(engine: EditorEngine, stencilNodeId: NodeId): SlotId {
  const node = engine.doc.nodes[stencilNodeId];
  expect(node).toBeDefined();
  expect(node.slots.length).toBeGreaterThan(0);
  return node.slots[0];
}

/** Create a sample TemplateDocument to simulate stencil version content. */
function createSampleStencilContent(): TemplateDocument {
  const rootId = nodeId('stencil-root');
  const rootSlot = slotId('stencil-root-slot');
  const textId = nodeId('stencil-text');
  const containerId = nodeId('stencil-container');
  const containerSlot = slotId('stencil-container-slot');
  const innerTextId = nodeId('stencil-inner-text');

  return {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: { id: rootId, type: 'root', slots: [rootSlot] },
      [textId]: { id: textId, type: 'text', slots: [], props: { content: 'Hello' } },
      [containerId]: { id: containerId, type: 'container', slots: [containerSlot] },
      [innerTextId]: { id: innerTextId, type: 'text', slots: [], props: { content: 'Inner' } },
    } as Record<NodeId, Node>,
    slots: {
      [rootSlot]: { id: rootSlot, nodeId: rootId, name: 'children', children: [textId, containerId] },
      [containerSlot]: { id: containerSlot, nodeId: containerId, name: 'children', children: [innerTextId] },
    } as Record<SlotId, Slot>,
    themeRef: { type: 'inherit' },
  };
}

beforeEach(() => {
  resetCounter();
});

// ---------------------------------------------------------------------------
// Component registration
// ---------------------------------------------------------------------------

describe('Stencil component registration', () => {
  it('registers with correct type and category', () => {
    const { registry } = setupEngine();
    const def = registry.get('stencil');
    expect(def).toBeDefined();
    expect(def!.type).toBe('stencil');
    expect(def!.category).toBe('layout');
  });

  it('has a children slot template', () => {
    const { registry } = setupEngine();
    const def = registry.get('stencil');
    expect(def!.slots).toEqual([{ name: 'children' }]);
  });

  it('prevents nesting stencils via denylist', () => {
    const { registry } = setupEngine();
    expect(registry.canContain('stencil', 'text')).toBe(true);
    expect(registry.canContain('stencil', 'container')).toBe(true);
    expect(registry.canContain('stencil', 'stencil')).toBe(false);
  });

  it('has default props with null stencilId, version, isDraft=false', () => {
    const { registry } = setupEngine();
    const def = registry.get('stencil');
    expect(def!.defaultProps).toEqual({
      stencilId: null,
      version: null,
      isDraft: false,
    });
  });
});

// ---------------------------------------------------------------------------
// Empty stencil insertion
// ---------------------------------------------------------------------------

describe('Insert empty stencil', () => {
  it('creates a stencil node with a children slot', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    const node = engine.doc.nodes[stencilId];
    expect(node).toBeDefined();
    expect(node.type).toBe('stencil');
    expect(node.slots).toHaveLength(1);

    const slot = engine.doc.slots[node.slots[0]];
    expect(slot).toBeDefined();
    expect(slot.name).toBe('children');
    expect(slot.children).toHaveLength(0);
  });

  it('has null stencilId and version by default', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBeNull();
    expect(node.props?.version).toBeNull();
  });

  it('allows adding children to empty stencil', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlotId = getStencilSlot(engine, stencilId);

    const textId = insertText(engine, registry, stencilSlotId, 'Hello');
    const slot = engine.doc.slots[stencilSlotId];
    expect(slot.children).toContain(textId);
  });
});

// ---------------------------------------------------------------------------
// Inserting stencil with existing content
// ---------------------------------------------------------------------------

describe('Insert stencil with content (re-keying)', () => {
  it('creates a stencil with re-keyed content from _content prop', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const content = createSampleStencilContent();

    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'test-stencil',
      version: 1,
      _content: content,
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBe('test-stencil');
    expect(node.props?.version).toBe(1);
    // _content should be stripped from persisted props
    expect(node.props?._content).toBeUndefined();

    // Stencil should have children
    const stencilSlot = engine.doc.slots[node.slots[0]];
    expect(stencilSlot.children.length).toBe(2); // text + container from sample content
  });

  it('re-keyed nodes have different IDs from source', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const content = createSampleStencilContent();
    const originalNodeIds = Object.keys(content.nodes);

    insertStencil(engine, registry, rootSlotId, {
      stencilId: 'test-stencil',
      version: 1,
      _content: content,
    });

    // None of the original IDs should be in the document
    for (const origId of originalNodeIds) {
      // The root of the content is not inserted — only its children
      if (origId === (content.root as string)) continue;
      expect(engine.doc.nodes[origId as NodeId]).toBeUndefined();
    }
  });

  it('re-keyed nodes preserve type and props', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const content = createSampleStencilContent();

    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'test-stencil',
      version: 1,
      _content: content,
    });

    // Find the re-keyed text node by type and props
    const allNodes = Object.values(engine.doc.nodes);
    const textNodes = allNodes.filter(
      (n) => n.type === 'text' && n.props?.content === 'Hello',
    );
    expect(textNodes.length).toBe(1);

    const innerTextNodes = allNodes.filter(
      (n) => n.type === 'text' && n.props?.content === 'Inner',
    );
    expect(innerTextNodes.length).toBe(1);
  });
});

// ---------------------------------------------------------------------------
// Multiple stencils on one page
// ---------------------------------------------------------------------------

describe('Multiple stencils on one page', () => {
  it('can insert two stencils independently', () => {
    const { engine, registry, rootSlotId } = setupEngine();

    const stencil1 = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
    });
    const stencil2 = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'footer',
      version: 1,
    });

    expect(engine.doc.nodes[stencil1]).toBeDefined();
    expect(engine.doc.nodes[stencil2]).toBeDefined();
    expect(stencil1).not.toBe(stencil2);

    const rootSlot = engine.doc.slots[rootSlotId];
    expect(rootSlot.children).toContain(stencil1);
    expect(rootSlot.children).toContain(stencil2);
  });

  it('each stencil has its own independent slot', () => {
    const { engine, registry, rootSlotId } = setupEngine();

    const stencil1 = insertStencil(engine, registry, rootSlotId);
    const stencil2 = insertStencil(engine, registry, rootSlotId);

    const slot1 = getStencilSlot(engine, stencil1);
    const slot2 = getStencilSlot(engine, stencil2);
    expect(slot1).not.toBe(slot2);
  });

  it('adding content to one stencil does not affect the other', () => {
    const { engine, registry, rootSlotId } = setupEngine();

    const stencil1 = insertStencil(engine, registry, rootSlotId);
    const stencil2 = insertStencil(engine, registry, rootSlotId);

    const slot1 = getStencilSlot(engine, stencil1);
    const slot2 = getStencilSlot(engine, stencil2);

    insertText(engine, registry, slot1, 'In stencil 1');

    expect(engine.doc.slots[slot1].children).toHaveLength(1);
    expect(engine.doc.slots[slot2].children).toHaveLength(0);
  });

  it('can insert two stencils with different existing content', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const content = createSampleStencilContent();

    const stencil1 = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      _content: content,
    });

    // Reset counter so we get different IDs for the second content
    resetCounter();
    const content2 = createSampleStencilContent();

    const stencil2 = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'footer',
      version: 1,
      _content: content2,
    });

    const slot1 = engine.doc.slots[getStencilSlot(engine, stencil1)];
    const slot2 = engine.doc.slots[getStencilSlot(engine, stencil2)];

    expect(slot1.children.length).toBe(2);
    expect(slot2.children.length).toBe(2);

    // Children should be different node IDs (re-keyed independently)
    const allChildren = [...slot1.children, ...slot2.children];
    const uniqueChildren = new Set(allChildren);
    expect(uniqueChildren.size).toBe(allChildren.length);
  });

  it('can insert the SAME stencil content twice and add children to both', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const content = createSampleStencilContent();

    // Insert the same content object for both
    const stencil1 = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      _content: content,
    });
    const stencil2 = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      _content: content,
    });

    const slot1 = getStencilSlot(engine, stencil1);
    const slot2 = getStencilSlot(engine, stencil2);

    // Both should have content
    expect(engine.doc.slots[slot1].children.length).toBe(2);
    expect(engine.doc.slots[slot2].children.length).toBe(2);

    // No ID collisions — all node IDs in the document should be unique
    const allNodeIds = Object.keys(engine.doc.nodes);
    expect(new Set(allNodeIds).size).toBe(allNodeIds.length);

    const allSlotIds = Object.keys(engine.doc.slots);
    expect(new Set(allSlotIds).size).toBe(allSlotIds.length);

    // Can add new content to both
    const text1 = insertText(engine, registry, slot1, 'Added to first');
    const text2 = insertText(engine, registry, slot2, 'Added to second');

    expect(engine.doc.slots[slot1].children).toContain(text1);
    expect(engine.doc.slots[slot2].children).toContain(text2);
    expect(engine.doc.slots[slot1].children).not.toContain(text2);
    expect(engine.doc.slots[slot2].children).not.toContain(text1);
  });

  it('can add content to a published stencil after setting isDraft=true', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const content = createSampleStencilContent();

    const stencilNodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      _content: content,
    });

    // Stencil is locked (isDraft defaults to false)
    expect(engine.doc.nodes[stencilNodeId].props?.isDraft).toBeFalsy();

    // Simulate "Start Editing"
    const result = engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilNodeId,
      props: { ...engine.doc.nodes[stencilNodeId].props, isDraft: true },
    });
    expect(result.ok).toBe(true);
    expect(engine.doc.nodes[stencilNodeId].props?.isDraft).toBe(true);

    // Now add content — should work
    const stencilSlot = getStencilSlot(engine, stencilNodeId);
    const textId = insertText(engine, registry, stencilSlot, 'New content');
    expect(engine.doc.slots[stencilSlot].children).toContain(textId);
  });

  it('published stencil with content: verify slot structure is intact', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const content = createSampleStencilContent();

    const stencilNodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      _content: content,
    });

    const node = engine.doc.nodes[stencilNodeId];
    // Node should have exactly one slot
    expect(node.slots).toHaveLength(1);

    const slot = engine.doc.slots[node.slots[0]];
    // Slot should exist and reference the stencil node
    expect(slot).toBeDefined();
    expect(slot.nodeId).toBe(stencilNodeId);
    expect(slot.name).toBe('children');
    // Should have 2 children from the sample content (text + container)
    expect(slot.children).toHaveLength(2);

    // All children should exist as nodes in the document
    for (const childId of slot.children) {
      expect(engine.doc.nodes[childId]).toBeDefined();
    }
  });

  it('two instances of the same stencil have independent node trees', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const content = createSampleStencilContent();

    const s1 = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header', version: 1, _content: content,
    });
    const s2 = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header', version: 1, _content: content,
    });

    const slot1 = engine.doc.slots[getStencilSlot(engine, s1)];
    const slot2 = engine.doc.slots[getStencilSlot(engine, s2)];

    // Both have children but none are shared
    expect(slot1.children.length).toBeGreaterThan(0);
    expect(slot2.children.length).toBeGreaterThan(0);

    for (const childId of slot1.children) {
      expect(slot2.children).not.toContain(childId);
    }

    // Check ALL nodes in both subtrees are independent
    const collectDescendants = (nodeId: NodeId): Set<string> => {
      const ids = new Set<string>();
      ids.add(nodeId as string);
      const node = engine.doc.nodes[nodeId];
      if (!node) return ids;
      for (const sid of node.slots) {
        const slot = engine.doc.slots[sid];
        if (!slot) continue;
        ids.add(sid as string);
        for (const cid of slot.children) {
          for (const id of collectDescendants(cid)) ids.add(id);
        }
      }
      return ids;
    };

    const tree1 = collectDescendants(s1);
    const tree2 = collectDescendants(s2);

    // No overlap except for the root slot/document level
    for (const id of tree1) {
      if (id === (s1 as string)) continue;
      expect(tree2.has(id)).toBe(false);
    }
  });
});

// ---------------------------------------------------------------------------
// Nesting prevention
// ---------------------------------------------------------------------------

describe('Stencil nesting prevention', () => {
  it('rejects inserting a stencil into another stencil', () => {
    const { engine, registry, rootSlotId } = setupEngine();

    const outerStencil = insertStencil(engine, registry, rootSlotId);
    const outerSlot = getStencilSlot(engine, outerStencil);

    const { node, slots } = registry.createNode('stencil');
    const result = engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: outerSlot,
      index: -1,
    });

    expect(result.ok).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Props and state management
// ---------------------------------------------------------------------------

describe('Stencil props and state', () => {
  it('can update stencilId and version via UpdateNodeProps', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: 'my-stencil', version: 1, isDraft: false },
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBe('my-stencil');
    expect(node.props?.version).toBe(1);
    expect(node.props?.isDraft).toBe(false);
  });

  it('can switch to draft mode', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'my-stencil',
      version: 1,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, isDraft: true },
    });

    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(true);
  });

  it('can detach by clearing stencilId and version', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'my-stencil',
      version: 1,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: null, version: null, isDraft: false },
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBeNull();
    expect(node.props?.version).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// reKeyContent utility
// ---------------------------------------------------------------------------

describe('reKeyContent', () => {
  it('generates new IDs for all nodes and slots', () => {
    const content = createSampleStencilContent();
    const result = reKeyContent(content);

    const originalNodeIds = new Set(Object.keys(content.nodes));
    const originalSlotIds = new Set(Object.keys(content.slots));

    for (const node of result.nodes) {
      expect(originalNodeIds.has(node.id as string)).toBe(false);
    }
    for (const slot of result.slots) {
      expect(originalSlotIds.has(slot.id as string)).toBe(false);
    }
  });

  it('preserves node types and props', () => {
    const content = createSampleStencilContent();
    const result = reKeyContent(content);

    const textNodes = result.nodes.filter((n) => n.type === 'text');
    expect(textNodes.length).toBe(2);
    expect(textNodes.some((n) => n.props?.content === 'Hello')).toBe(true);
    expect(textNodes.some((n) => n.props?.content === 'Inner')).toBe(true);
  });

  it('returns correct childNodeIds (root children)', () => {
    const content = createSampleStencilContent();
    const result = reKeyContent(content);

    // Root has 2 children: text + container
    expect(result.childNodeIds).toHaveLength(2);
  });

  it('rewrites slot references in nodes', () => {
    const content = createSampleStencilContent();
    const result = reKeyContent(content);

    const containerNode = result.nodes.find((n) => n.type === 'container');
    expect(containerNode).toBeDefined();
    expect(containerNode!.slots).toHaveLength(1);

    // The slot ID should exist in the result slots
    const slotId = containerNode!.slots[0];
    const matchingSlot = result.slots.find((s) => s.id === slotId);
    expect(matchingSlot).toBeDefined();
    expect(matchingSlot!.nodeId).toBe(containerNode!.id);
  });

  it('rewrites child references in slots', () => {
    const content = createSampleStencilContent();
    const result = reKeyContent(content);

    const containerSlot = result.slots.find((s) => s.name === 'children' && s.children.length === 1);
    expect(containerSlot).toBeDefined();

    // The child should be the re-keyed inner text node
    const childId = containerSlot!.children[0];
    const childNode = result.nodes.find((n) => n.id === childId);
    expect(childNode).toBeDefined();
    expect(childNode!.type).toBe('text');
    expect(childNode!.props?.content).toBe('Inner');
  });
});

// ---------------------------------------------------------------------------
// extractSubtree utility
// ---------------------------------------------------------------------------

describe('extractSubtree', () => {
  it('extracts a stencil node children as a standalone document', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);

    // Add content
    insertText(engine, registry, stencilSlot, 'Extracted text');

    const extracted = extractSubtree(engine.doc, stencilId);

    expect(extracted.modelVersion).toBe(1);
    expect(extracted.root).toBeDefined();
    expect(Object.keys(extracted.nodes).length).toBeGreaterThanOrEqual(2); // root + text

    // Find the text node in the extracted document
    const textNodes = Object.values(extracted.nodes).filter(
      (n) => n.type === 'text' && n.props?.content === 'Extracted text',
    );
    expect(textNodes).toHaveLength(1);
  });

  it('returns empty document when stencil has no children', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    const extracted = extractSubtree(engine.doc, stencilId);

    expect(extracted.root).toBeDefined();
    const rootSlot = Object.values(extracted.slots).find((s) => s.nodeId === extracted.root);
    expect(rootSlot).toBeDefined();
    expect(rootSlot!.children).toHaveLength(0);
  });

  it('extracts nested content correctly', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);

    // Add a container with a text node inside
    const { node: containerNode, slots: containerSlots } = registry.createNode('container');
    engine.dispatch({
      type: 'InsertNode',
      node: containerNode,
      slots: containerSlots,
      targetSlotId: stencilSlot,
      index: -1,
    });
    const containerSlotId = engine.doc.nodes[containerNode.id].slots[0];
    insertText(engine, registry, containerSlotId, 'Nested text');

    const extracted = extractSubtree(engine.doc, stencilId);

    const containerNodes = Object.values(extracted.nodes).filter((n) => n.type === 'container');
    expect(containerNodes).toHaveLength(1);

    const nestedTexts = Object.values(extracted.nodes).filter(
      (n) => n.type === 'text' && n.props?.content === 'Nested text',
    );
    expect(nestedTexts).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// Undo/redo
// ---------------------------------------------------------------------------

describe('Undo/redo', () => {
  it('undoes stencil insertion', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    expect(engine.doc.nodes[stencilId]).toBeDefined();

    engine.undo();

    expect(engine.doc.nodes[stencilId]).toBeUndefined();
    expect(engine.doc.slots[rootSlotId].children).not.toContain(stencilId);
  });

  it('redoes stencil insertion', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    engine.undo();
    engine.redo();

    expect(engine.doc.nodes[stencilId]).toBeDefined();
  });

  it('undoes adding content to a stencil', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);

    const textId = insertText(engine, registry, stencilSlot, 'Hello');

    expect(engine.doc.slots[stencilSlot].children).toContain(textId);

    engine.undo();

    expect(engine.doc.slots[stencilSlot].children).not.toContain(textId);
  });

  it('undoes prop update (isDraft toggle)', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'test',
      version: 1,
      isDraft: false,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: 'test', version: 1, isDraft: true },
    });

    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(true);

    engine.undo();

    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });
});
