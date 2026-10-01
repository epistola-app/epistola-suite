// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, it, expect } from 'vitest';
import { render, html } from 'lit';
import { EditorEngine } from '../engine/EditorEngine.js';
import { createDefaultRegistry } from '../engine/registry.js';
import './EpistolaCanvas.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';

const id = (s: string) => s as NodeId;
const sid = (s: string) => s as SlotId;
const text = (nodeId: string, value: string) => ({
  id: id(nodeId),
  type: 'text',
  slots: [],
  props: {
    content: {
      type: 'doc',
      content: [{ type: 'paragraph', content: [{ type: 'text', text: value }] }],
    },
  },
});

/** root → [text "body", pageheader → [text "header"]] */
function buildDoc(): TemplateDocument {
  return {
    modelVersion: 1,
    root: id('root'),
    nodes: {
      root: { id: id('root'), type: 'root', slots: [sid('root-slot')] },
      body: text('body', 'body text'),
      header: { id: id('header'), type: 'pageheader', slots: [sid('header-slot')] },
      'header-text': text('header-text', 'header text'),
    },
    slots: {
      'root-slot': {
        id: sid('root-slot'),
        nodeId: id('root'),
        name: 'children',
        children: [id('body'), id('header')],
      },
      'header-slot': {
        id: sid('header-slot'),
        nodeId: id('header'),
        name: 'children',
        children: [id('header-text')],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

async function renderCanvas(container: HTMLElement, engine: EditorEngine) {
  render(html`<epistola-canvas .engine=${engine} .doc=${engine.doc}></epistola-canvas>`, container);
  await Promise.resolve();
  await Promise.resolve();
}

function shownText(container: HTMLElement, nodeId: string): string | null {
  return (
    container.querySelector(`.canvas-block[data-node-id="${nodeId}"] .ProseMirror`)?.textContent ??
    null
  );
}

afterEach(() => {
  document.body.replaceChildren();
});

describe('EpistolaCanvas — moving blocks', () => {
  it('keeps showing text after a block is moved above another', async () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const engine = new EditorEngine(buildDoc(), createDefaultRegistry());
    await renderCanvas(container, engine);
    expect(shownText(container, 'header-text')).toBe('header text');
    expect(shownText(container, 'body')).toBe('body text');

    expect(
      engine.dispatch({
        type: 'MoveNode',
        nodeId: id('header'),
        targetSlotId: sid('root-slot'),
        index: 0,
      }).ok,
    ).toBe(true);
    await renderCanvas(container, engine);

    expect(shownText(container, 'header-text')).toBe('header text');
    expect(shownText(container, 'body')).toBe('body text');
  });
});
