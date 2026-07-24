// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Shared test helpers for the stencil + placeholder test suites.
 *
 * Used by:
 *   - extract-subtree.test.ts
 *   - replace-content.test.ts
 *   - stencil-state.test.ts
 *   - stencil-callbacks.test.ts
 *   - stencil-actions.test.ts
 */

import { vi } from 'vitest';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry, type ComponentRegistry } from '../../engine/registry.js';
import { createTestDocument, nodeId, slotId } from '../../engine/test-helpers.js';
import { createStencilDefinition } from './stencil-registration.js';
import type { TemplateDocument, NodeId, SlotId, Node, Slot } from '../../types/index.js';
import type { StencilCallbacks } from './types.js';

export function createMockCallbacks(overrides?: Partial<StencilCallbacks>): StencilCallbacks {
  return {
    searchStencils: vi.fn().mockResolvedValue([]),
    listVersions: vi.fn().mockResolvedValue([]),
    getStencilVersion: vi.fn().mockResolvedValue(null),
    updateStencil: vi.fn().mockResolvedValue({ version: 2 }),
    startEditing: vi.fn().mockResolvedValue({ draftVersion: 2 }),
    publishDraft: vi.fn().mockResolvedValue({ version: 2 }),
    ...overrides,
  };
}

export function setupEngine(callbacks?: StencilCallbacks) {
  const registry = createDefaultRegistry();
  registry.register(createStencilDefinition({ callbacks: callbacks ?? createMockCallbacks() }));
  const doc = createTestDocument();
  const engine = new EditorEngine(doc, registry);
  const rootSlotId = doc.nodes[doc.root].slots[0];
  return { engine, registry, rootSlotId };
}

export function insertStencil(
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

export function insertText(
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

export function getStencilSlot(engine: EditorEngine, stencilNodeId: NodeId): SlotId {
  return engine.doc.nodes[stencilNodeId].slots[0];
}

export function createSampleContent(): TemplateDocument {
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
