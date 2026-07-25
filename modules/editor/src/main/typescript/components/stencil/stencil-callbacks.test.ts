// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Inspector flow tests that simulate the inspector's logic with mocked
 * callbacks. These pre-date the `stencil-actions.ts` extraction; they are
 * retained as integration coverage of the same behaviour at a different
 * granularity. Direct tests of the actions module live in
 * `stencil-actions.test.ts`.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { resetCounter, nodeId, slotId } from '../../engine/test-helpers.js';
import { extractSubtree } from './extract-subtree.js';
import { reKeyContent } from './rekey-content.js';
import {
  setupEngine,
  insertStencil,
  insertText,
  getStencilSlot,
  createSampleContent,
  createMockCallbacks,
} from './stencil-test-helpers.js';
import type { TemplateDocument, NodeId, SlotId } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { ComponentRegistry } from '../../engine/registry.js';

beforeEach(() => {
  resetCounter();
});

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
    {
      const { node, slots } = registry.createNode('text', { content: 'Old content' });
      engine.dispatch(
        { type: 'InsertNode', node, slots, targetSlotId: stencilSlot, index: -1 },
        { bypassLock: true },
      );
    }

    const versionInfo = await callbacks.getStencilVersion('header', 3);

    while (engine.doc.slots[stencilSlot].children.length > 0) {
      engine.dispatch(
        { type: 'RemoveNode', nodeId: engine.doc.slots[stencilSlot].children[0] },
        { bypassLock: true },
      );
    }

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
    {
      const { node, slots } = registry.createNode('text', { content: 'Content' });
      engine.dispatch(
        { type: 'InsertNode', node, slots, targetSlotId: stencilSlot, index: -1 },
        { bypassLock: true },
      );
    }

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
// Two-slot placeholder edit/publish flow (simulated)
// ---------------------------------------------------------------------------

/** Helper: insert a stencil with one placeholder having default + fill content. */
function setupStencilWithFilledPlaceholder(
  engine: EditorEngine,
  _registry: ComponentRegistry,
  rootSlotId: SlotId,
  stencilProps: Record<string, unknown> = { stencilId: 'header', version: 1, isDraft: false },
): { stencilId: NodeId; placeholderId: NodeId; fillSlotId: SlotId; defaultSlotId: SlotId } {
  const stencilId = nodeId('stencil');
  const stencilSlotId = slotId('stencil-slot');
  const placeholderId = nodeId('ph');
  const defaultSlotId = slotId('ph-default');
  const fillSlotId = slotId('ph-fill');
  const defaultText = nodeId('default-text');
  const fillText = nodeId('fill-text');

  engine.dispatch({
    type: 'InsertNode',
    node: { id: stencilId, type: 'stencil', slots: [stencilSlotId], props: stencilProps },
    slots: [
      { id: stencilSlotId, nodeId: stencilId, name: 'children', children: [placeholderId] },
      { id: defaultSlotId, nodeId: placeholderId, name: 'default', children: [defaultText] },
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
      { id: defaultText, type: 'text', slots: [], props: { content: 'default content' } },
      { id: fillText, type: 'text', slots: [], props: { content: 'override content' } },
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

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, isDraft: true },
    });

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

    const extracted = extractSubtree(engine.doc, stencilId);
    const extractedFillSlot = Object.values(extracted.slots).find((s) => s.name === 'fill');
    expect(extractedFillSlot?.children).toEqual([]);
    expect(engine.doc.slots[fillSlotId].children).toEqual(fillBefore);

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, version: 2, isDraft: false },
    });

    expect(engine.doc.slots[fillSlotId].children).toEqual(fillBefore);
    expect(engine.doc.nodes[fillBefore[0]]?.props?.content).toBe('override content');
    expect(engine.doc.nodes[stencilId].props?.version).toBe(2);
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });
});
