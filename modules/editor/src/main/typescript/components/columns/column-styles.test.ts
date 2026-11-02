// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, it, expect, beforeEach } from 'vitest';
import { render } from 'lit';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry } from '../../engine/registry.js';
import { boxStylesToCss } from '../../ui/inputs/box-style-fields.js';
import type { NodeId, SlotId, TemplateDocument } from '../../types/index.js';

function columnsEngine(columnStyles?: Record<string, unknown>[]): EditorEngine {
  const doc: TemplateDocument = {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      c: {
        id: 'c' as NodeId,
        type: 'columns',
        slots: ['c-0' as SlotId, 'c-1' as SlotId],
        props: { columnSizes: [1, 1], gap: 0, ...(columnStyles ? { columnStyles } : {}) },
      },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['c' as NodeId],
      },
      'c-0': { id: 'c-0' as SlotId, nodeId: 'c' as NodeId, name: 'column-0', children: [] },
      'c-1': { id: 'c-1' as SlotId, nodeId: 'c' as NodeId, name: 'column-1', children: [] },
    },
    themeRef: { type: 'inherit' },
  };
  return new EditorEngine(doc, createDefaultRegistry());
}

describe('columns — column styles', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  it('writes a column style at its index, padding earlier columns with {}', async () => {
    const engine = columnsEngine();
    const def = engine.registry.get('columns')!;
    render(
      def.renderInspector!({ node: engine.doc.nodes['c' as NodeId], engine }) as never,
      container,
    );
    await Promise.resolve();

    const select = container.querySelector<HTMLSelectElement>(
      'select[id="columns-c-1-vertical-align"]',
    )!;
    select.value = 'bottom';
    select.dispatchEvent(new Event('change'));

    expect(engine.doc.nodes['c' as NodeId].props?.columnStyles).toEqual([
      {},
      { verticalAlign: 'bottom' },
    ]);
  });

  it('drops the removed column’s styles, so the array is never longer than the columns', () => {
    const engine = columnsEngine([{ backgroundColor: '#eee' }, { verticalAlign: 'bottom' }]);
    engine.dispatch({ type: 'RemoveColumnSlot', nodeId: 'c' as NodeId });

    expect(engine.doc.nodes['c' as NodeId].props?.columnStyles).toEqual([
      { backgroundColor: '#eee' },
    ]);
  });

  it('shows a column’s vertical align on the canvas as a flex column', () => {
    expect(
      boxStylesToCss({ verticalAlign: 'bottom', paddingTop: '2sp' }, (v) =>
        v.replace('2sp', '8pt'),
      ),
    ).toEqual({
      'padding-top': '8pt',
      display: 'flex',
      'flex-direction': 'column',
      'justify-content': 'flex-end',
    });
  });
});
