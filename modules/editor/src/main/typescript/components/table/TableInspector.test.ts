// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, it, expect, beforeEach } from 'vitest';
import { render, html } from 'lit';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry } from '../../engine/registry.js';
import './TableInspector.js';
import type { NodeId, SlotId, TemplateDocument } from '../../types/index.js';

/** A 2×2 table in a root, with one cell selected. */
function setupEngineWithSelectedCell(): EditorEngine {
  const cells = ['cell-0-0', 'cell-0-1', 'cell-1-0', 'cell-1-1'];
  const doc: TemplateDocument = {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      t: {
        id: 't' as NodeId,
        type: 'table',
        slots: cells.map((name) => `t-${name}` as SlotId),
        props: { rows: 2, columns: 2, columnWidths: [50, 50], headerRows: 0, merges: [] },
      },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['t' as NodeId],
      },
      ...Object.fromEntries(
        cells.map((name) => [
          `t-${name}`,
          { id: `t-${name}` as SlotId, nodeId: 't' as NodeId, name, children: [] },
        ]),
      ),
    },
    themeRef: { type: 'inherit' },
  };
  const engine = new EditorEngine(doc, createDefaultRegistry());
  engine.setComponentState('table:cellSelection', {
    startRow: 0,
    startCol: 0,
    endRow: 0,
    endCol: 0,
  });
  return engine;
}

describe('TableInspector — cell styles', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  async function renderInspector(engine: EditorEngine) {
    render(
      html`<table-inspector
        .node=${engine.doc.nodes['t' as NodeId]}
        .engine=${engine}
      ></table-inspector>`,
      container,
    );
    await Promise.resolve();
  }

  it('offers the cell styles the contract allows, and no text align', async () => {
    const engine = setupEngineWithSelectedCell();
    await renderInspector(engine);

    expect(container.textContent).toContain('Cell Style');
    expect(container.querySelector('epistola-border-input')).not.toBeNull();
    expect(container.textContent).toContain('Background');
    expect(container.textContent).toContain('Padding');
    expect(container.textContent).toContain('Vertical Align');
    expect(container.textContent).not.toContain('Text Align');
    // The selection section above it is still there.
    expect(container.textContent).toContain('Selection (0,0)');
  });

  it('writes a vertical align to every selected cell', async () => {
    const engine = setupEngineWithSelectedCell();
    engine.setComponentState('table:cellSelection', {
      startRow: 0,
      startCol: 0,
      endRow: 0,
      endCol: 1,
    });
    await renderInspector(engine);

    const select = container.querySelector<HTMLSelectElement>('select[id$="-vertical-align"]')!;
    select.value = 'middle';
    select.dispatchEvent(new Event('change'));

    const cellStyles = engine.doc.nodes['t' as NodeId].props?.cellStyles as Record<
      string,
      Record<string, unknown>
    >;
    expect(cellStyles['0-0']).toEqual({ verticalAlign: 'middle' });
    expect(cellStyles['0-1']).toEqual({ verticalAlign: 'middle' });
    expect(cellStyles['1-0']).toBeUndefined();
  });
});
