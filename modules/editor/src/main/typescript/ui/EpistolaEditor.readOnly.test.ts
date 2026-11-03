// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { html, render } from 'lit';
import type { EditorView } from 'prosemirror-view';
import { EditorEngine } from '../engine/EditorEngine.js';
import { createDefaultRegistry } from '../engine/registry.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';
import './EpistolaCanvas.js';
import { EpistolaSidebar } from './EpistolaSidebar.js';
import { EpistolaTextEditor } from './EpistolaTextEditor.js';

/**
 * A template in a catalog that cannot be changed here opens in the editor to show how it is built.
 * It saved nothing, but every block could still be typed into, dragged, deleted and restyled, so
 * the editor looked editable and quietly dropped the work. Read-only, nothing changes it.
 */

const CONTENT = {
  type: 'doc',
  content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Dear customer' }] }],
};

function doc(): TemplateDocument {
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      greeting: { id: 'greeting' as NodeId, type: 'text', slots: [], props: { content: CONTENT } },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['greeting' as NodeId],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

const engineOf = (readOnly: boolean) =>
  new EditorEngine(doc(), createDefaultRegistry(), { readOnly });

afterEach(() => {
  document.body.replaceChildren();
});

describe('a read-only editor', () => {
  it('applies no command, and leaves nothing to undo', () => {
    const engine = engineOf(true);
    const before = engine.doc;

    const result = engine.dispatch({ type: 'RemoveNode', nodeId: 'greeting' as NodeId });

    expect(result.ok).toBe(false);
    expect(engine.doc).toBe(before);
    expect(engine.canUndo).toBe(false);
    // An editable engine takes the same command.
    expect(engineOf(false).dispatch({ type: 'RemoveNode', nodeId: 'greeting' as NodeId }).ok).toBe(
      true,
    );
  });

  it('offers no blocks to add, and its inspector fields are disabled', async () => {
    const engine = engineOf(true);
    const sidebar = new EpistolaSidebar();
    sidebar.engine = engine;
    sidebar.doc = engine.doc;
    sidebar.selectedNodeId = 'greeting' as NodeId;
    document.body.appendChild(sidebar);
    await sidebar.updateComplete;

    expect(sidebar.querySelector('[data-tab-id="blocks"]')).toBeNull();
    expect(sidebar.querySelector('epistola-palette')).toBeNull();
    expect(
      sidebar.querySelector<HTMLFieldSetElement>('fieldset[data-testid="inspector-read-only"]')
        ?.disabled,
    ).toBe(true);
  });

  it('shows no delete button on a selected block, and its text cannot be typed into', async () => {
    const engine = engineOf(true);
    engine.selectNode('greeting' as NodeId);
    render(
      html`<epistola-canvas
        .engine=${engine}
        .doc=${engine.doc}
        .selectedNodeId=${'greeting'}
      ></epistola-canvas>`,
      document.body,
    );
    await (
      document.querySelector('epistola-canvas') as unknown as { updateComplete: Promise<unknown> }
    ).updateComplete;
    expect(document.querySelector('.canvas-block-delete')).toBeNull();

    const text = new EpistolaTextEditor();
    text.engine = engine;
    text.nodeId = 'greeting' as NodeId;
    text.content = CONTENT;
    document.body.appendChild(text);
    await text.updateComplete;
    const view = (text as unknown as { _pmView: EditorView })._pmView;
    expect(view.editable).toBe(false);
  });
});
