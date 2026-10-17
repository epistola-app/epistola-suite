// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Verifies that mutation reducers reject commands targeting locked slots,
 * and that `bypassLock: true` skips the check.
 *
 * Uses real stencil + placeholder definitions to exercise the integrated
 * behaviour. The generic mechanism itself is tested in `locks.test.ts`.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { applyCommand } from './commands.js';
import { ComponentRegistry, createDefaultRegistry } from './registry.js';
import { buildIndexes } from './indexes.js';
import { createStencilDefinition } from '../components/stencil/stencil-registration.js';
import { StencilEditingState } from '../components/stencil/editing-state.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';

function richText(text: string) {
  return {
    type: 'doc',
    content: [{ type: 'paragraph', content: [{ type: 'text', text }] }],
  };
}

function buildRegistry(editing = new StencilEditingState()): ComponentRegistry {
  const registry = createDefaultRegistry();
  // Stencil is normally added by lib.ts at runtime.
  registry.register(createStencilDefinition({ callbacks: null, editing }));
  return registry;
}

/** A registry in which the author is editing the stencil `stencil` in place. */
function editingRegistry(editing = new StencilEditingState()): ComponentRegistry {
  editing.start('stencil');
  return buildRegistry(editing);
}

/**
 * Build a doc with one stencil instance holding one placeholder. Its children
 * are locked unless the author is editing the stencil (see `editingRegistry`).
 * The placeholder has both `default` and `fill` slots; the fill is `editable`.
 */
function buildDoc(): TemplateDocument {
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      stencil: {
        id: 'stencil' as NodeId,
        type: 'stencil',
        slots: ['stencil-children' as SlotId],
        props: { stencilKey: 'A' },
      },
      ph: {
        id: 'ph' as NodeId,
        type: 'placeholder',
        slots: ['ph-default' as SlotId, 'ph-fill' as SlotId],
        props: { name: 'body' },
      },
      defaultText: {
        id: 'defaultText' as NodeId,
        type: 'text',
        slots: [],
        props: { content: richText('default') },
      },
      fillText: {
        id: 'fillText' as NodeId,
        type: 'text',
        slots: [],
        props: { content: richText('override') },
      },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['stencil' as NodeId],
      },
      'stencil-children': {
        id: 'stencil-children' as SlotId,
        nodeId: 'stencil' as NodeId,
        name: 'children',
        children: ['ph' as NodeId],
      },
      'ph-default': {
        id: 'ph-default' as SlotId,
        nodeId: 'ph' as NodeId,
        name: 'default',
        children: ['defaultText' as NodeId],
      },
      'ph-fill': {
        id: 'ph-fill' as SlotId,
        nodeId: 'ph' as NodeId,
        name: 'fill',
        children: ['fillText' as NodeId],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

describe('commands — slot lock enforcement', () => {
  let registry: ComponentRegistry;

  beforeEach(() => {
    registry = buildRegistry();
  });

  // ---- Locked stencil (used, not being edited) ----

  it('UpdateNodeProps on a placeholder inside a locked stencil is rejected', () => {
    const doc = buildDoc();
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      { type: 'UpdateNodeProps', nodeId: 'ph' as NodeId, props: { name: 'changed' } },
      registry,
    );
    expect(result.ok).toBe(false);
  });

  it('UpdateNodeProps on a node inside a placeholder fill (editable break) is allowed', () => {
    const doc = buildDoc();
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      {
        type: 'UpdateNodeProps',
        nodeId: 'fillText' as NodeId,
        props: { content: richText('new') },
      },
      registry,
    );
    expect(result.ok).toBe(true);
  });

  it('UpdateNodeProps on a node inside a placeholder default (inherited lock) is rejected', () => {
    const doc = buildDoc();
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      {
        type: 'UpdateNodeProps',
        nodeId: 'defaultText' as NodeId,
        props: { content: richText('new') },
      },
      registry,
    );
    expect(result.ok).toBe(false);
  });

  it('RemoveNode on a child of a locked stencil children slot is rejected', () => {
    const doc = buildDoc();
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      { type: 'RemoveNode', nodeId: 'ph' as NodeId },
      registry,
    );
    expect(result.ok).toBe(false);
  });

  it('RemoveNode on a child of a placeholder fill is allowed', () => {
    const doc = buildDoc();
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      { type: 'RemoveNode', nodeId: 'fillText' as NodeId },
      registry,
    );
    expect(result.ok).toBe(true);
  });

  it('MoveNode out of a locked slot is rejected', () => {
    const doc = buildDoc();
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      {
        type: 'MoveNode',
        nodeId: 'ph' as NodeId,
        targetSlotId: 'root-slot' as SlotId,
        index: 0,
      },
      registry,
    );
    expect(result.ok).toBe(false);
  });

  it('MoveNode into a locked slot is rejected', () => {
    const doc = buildDoc();
    // Try to move the existing fillText (in editable fill) into the locked
    // stencil-children slot. Should be rejected by the target lock check.
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      {
        type: 'MoveNode',
        nodeId: 'fillText' as NodeId,
        targetSlotId: 'stencil-children' as SlotId,
        index: 0,
      },
      registry,
    );
    expect(result.ok).toBe(false);
  });

  // ---- Stencil being edited (mutable) ----

  it('all mutations succeed while the stencil is being edited', () => {
    registry = editingRegistry();
    const doc = buildDoc();
    const indexes = buildIndexes(doc);
    expect(
      applyCommand(
        doc,
        indexes,
        { type: 'UpdateNodeProps', nodeId: 'ph' as NodeId, props: { name: 'changed' } },
        registry,
      ).ok,
    ).toBe(true);
    expect(
      applyCommand(
        doc,
        indexes,
        {
          type: 'UpdateNodeProps',
          nodeId: 'defaultText' as NodeId,
          props: { content: richText('new') },
        },
        registry,
      ).ok,
    ).toBe(true);
    expect(
      applyCommand(doc, indexes, { type: 'RemoveNode', nodeId: 'ph' as NodeId }, registry).ok,
    ).toBe(true);
  });

  // ---- Bypass ----

  it('bypassLock skips the check on an otherwise-rejected mutation', () => {
    const doc = buildDoc();
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      { type: 'UpdateNodeProps', nodeId: 'ph' as NodeId, props: { name: 'changed' } },
      registry,
      { bypassLock: true },
    );
    expect(result.ok).toBe(true);
  });

  it('bypassLock allows MoveNode into an otherwise-locked slot', () => {
    const doc = buildDoc();
    const result = applyCommand(
      doc,
      buildIndexes(doc),
      {
        type: 'MoveNode',
        nodeId: 'fillText' as NodeId,
        targetSlotId: 'stencil-children' as SlotId,
        index: 0,
      },
      registry,
      { bypassLock: true },
    );
    expect(result.ok).toBe(true);
  });
});

describe('commands — undo/redo bypasses the lock', () => {
  // Verifies the `_dispatchSilent` invariant: undoing a previously-allowed
  // mutation works even after the surrounding lock state has changed.
  // (We test the engine reducer directly with bypassLock: true; the
  // EditorEngine wires this up automatically for undo/redo.)
  it('replaying an inverse on a now-locked slot succeeds with bypassLock', () => {
    const editing = new StencilEditingState();
    const doc = buildDoc();
    // Forward op: remove the placeholder while the stencil is being edited.
    const forward = applyCommand(
      doc,
      buildIndexes(doc),
      { type: 'RemoveNode', nodeId: 'ph' as NodeId },
      editingRegistry(editing),
    );
    expect(forward.ok).toBe(true);
    if (!forward.ok) return; // type narrowing

    // Now stop editing — the stencil's children slot becomes locked.
    editing.stop('stencil');
    const flippedDoc = forward.doc;

    // Replaying the inverse (re-inserting the placeholder) without bypass
    // would now be rejected by the lock; with bypass it succeeds.
    const inverse = forward.inverse;
    expect(inverse).toBeTruthy();
    const undoNoBypass = applyCommand(
      flippedDoc,
      buildIndexes(flippedDoc),
      inverse!,
      buildRegistry(),
    );
    expect(undoNoBypass.ok).toBe(false);
    const undoBypassed = applyCommand(
      flippedDoc,
      buildIndexes(flippedDoc),
      inverse!,
      buildRegistry(),
      { bypassLock: true },
    );
    expect(undoBypassed.ok).toBe(true);
  });
});
