// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, it, expect, beforeEach } from 'vitest';
import { resetCounter } from '../../engine/test-helpers.js';
import { reKeyContent } from './rekey-content.js';
import {
  setupEngine,
  insertStencil,
  insertText,
  getStencilSlot,
  createSampleContent,
} from './stencil-test-helpers.js';
import type { Slot } from '../../types/index.js';

beforeEach(() => {
  resetCounter();
});

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
