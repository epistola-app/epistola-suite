// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Direct tests of the `stencil-actions.ts` module.
 *
 * These call the action functions with mocked callbacks and assert engine
 * state — no inspector / Lit / DOM involvement. Sibling files cover other
 * angles:
 *   - `extract-subtree.test.ts`     — extractSubtree + fill stripping
 *   - `replace-content.test.ts`     — RemoveNode/InsertNode swap flow
 *   - `stencil-state.test.ts`       — props transitions + getLabel + catalogKey
 *   - `stencil-callbacks.test.ts`   — simulated inspector flows (callbacks)
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { resetCounter, nodeId, slotId } from '../../engine/test-helpers.js';
import * as actions from './stencil-actions.js';
import { setupEngine, insertStencil, createMockCallbacks } from './stencil-test-helpers.js';
import type { TemplateDocument, NodeId, SlotId } from '../../types/index.js';
import type { StencilCallbacks } from './types.js';

beforeEach(() => {
  resetCounter();
});

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
    const { ctx, stencilId, engine } = setupCtx(
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
