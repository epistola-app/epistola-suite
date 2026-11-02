// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, it, expect, beforeEach } from 'vitest';
import { render } from 'lit';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry } from '../../engine/registry.js';
import { elseSlotOf } from './conditional-else.js';
import type { NodeId, SlotId, TemplateDocument } from '../../types/index.js';

/** A conditional as it was made before the else slot existed: only `body`. */
function engineWithOldConditional(): EditorEngine {
  const doc: TemplateDocument = {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      cond: {
        id: 'cond' as NodeId,
        type: 'conditional',
        slots: ['cond-body' as SlotId],
        props: { condition: { raw: 'paid', language: 'jsonata' }, inverse: false },
      },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['cond' as NodeId],
      },
      'cond-body': {
        id: 'cond-body' as SlotId,
        nodeId: 'cond' as NodeId,
        name: 'body',
        children: [],
      },
    },
    themeRef: { type: 'inherit' },
  };
  return new EditorEngine(doc, createDefaultRegistry());
}

describe('conditional — add else branch', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  function renderInspector(engine: EditorEngine) {
    const def = engine.registry.get('conditional')!;
    render(
      def.renderInspector!({ node: engine.doc.nodes['cond' as NodeId], engine }) as never,
      container,
    );
  }

  it('offers the action on a conditional without an else slot, and adds an empty one', () => {
    const engine = engineWithOldConditional();
    renderInspector(engine);

    container.querySelector<HTMLButtonElement>('#conditional-cond-add-else')!.click();

    const node = engine.doc.nodes['cond' as NodeId];
    const elseId = elseSlotOf(engine.doc, node)!;
    expect(engine.doc.slots[elseId]).toMatchObject({ name: 'else', nodeId: 'cond', children: [] });
    expect(node.slots).toEqual(['cond-body', elseId]);
  });

  it('offers nothing once the conditional has an else slot', () => {
    const engine = engineWithOldConditional();
    engine.dispatch({ type: 'AddConditionalElse', nodeId: 'cond' as NodeId });
    renderInspector(engine);

    expect(container.querySelector('#conditional-cond-add-else')).toBeNull();
  });

  it('undo removes the added else slot again', () => {
    const engine = engineWithOldConditional();
    engine.dispatch({ type: 'AddConditionalElse', nodeId: 'cond' as NodeId });
    engine.undo();

    expect(elseSlotOf(engine.doc, engine.doc.nodes['cond' as NodeId])).toBeUndefined();
    expect(engine.doc.nodes['cond' as NodeId].slots).toEqual(['cond-body']);
  });
});
