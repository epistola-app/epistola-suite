// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, it, expect, beforeEach } from 'vitest';
import { render, html } from 'lit';
import { EditorEngine } from '../engine/EditorEngine.js';
import { createDefaultRegistry } from '../engine/registry.js';
import './EpistolaCanvas.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';

/** root → [pagefooter first, pagefooter second, text] — the second footer is skipped. */
function buildDoc(): TemplateDocument {
  const id = (s: string) => s as NodeId;
  const sid = (s: string) => s as SlotId;
  const footer = (name: string) => ({
    id: id(name),
    type: 'pagefooter',
    slots: [sid(`${name}-slot`)],
  });
  return {
    modelVersion: 1,
    root: id('root'),
    nodes: {
      root: { id: id('root'), type: 'root', slots: [sid('root-slot')] },
      first: footer('first'),
      second: footer('second'),
      body: { id: id('body'), type: 'text', slots: [], props: { content: null } },
    },
    slots: {
      'root-slot': {
        id: sid('root-slot'),
        nodeId: id('root'),
        name: 'children',
        children: [id('first'), id('second'), id('body')],
      },
      'first-slot': { id: sid('first-slot'), nodeId: id('first'), name: 'children', children: [] },
      'second-slot': {
        id: sid('second-slot'),
        nodeId: id('second'),
        name: 'children',
        children: [],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

function blockHeader(container: HTMLElement, nodeId: string): HTMLElement {
  return container.querySelector<HTMLElement>(
    `.canvas-block[data-node-id="${nodeId}"] .canvas-block-header`,
  )!;
}

describe('EpistolaCanvas — block hints', () => {
  let container: HTMLElement;

  beforeEach(async () => {
    container = document.createElement('div');
    document.body.appendChild(container);
    const engine = new EditorEngine(buildDoc(), createDefaultRegistry());
    render(
      html`<epistola-canvas .engine=${engine} .doc=${engine.doc}></epistola-canvas>`,
      container,
    );
    await Promise.resolve();
    await Promise.resolve();
  });

  it('keeps the page band label plain and explains it through an icon', () => {
    const header = blockHeader(container, 'first');
    const hint = header.querySelector<HTMLElement>('[data-testid="canvas-block-hint"]')!;

    expect(header.querySelector('.canvas-block-label')?.textContent).toBe('Page Footer');
    expect(hint.classList.contains('info')).toBe(true);
    expect(hint.getAttribute('title')).toContain('and to the pages before it');
    expect(hint.getAttribute('aria-label')).toBe(hint.getAttribute('title'));
  });

  it('shows a warning icon for a footer that is skipped', () => {
    const hint = blockHeader(container, 'second').querySelector<HTMLElement>(
      '[data-testid="canvas-block-hint"]',
    )!;

    expect(hint.classList.contains('warning')).toBe(true);
    expect(hint.getAttribute('title')).toMatch(/^Skipped:/);
  });

  it('shows no hint on other blocks', () => {
    expect(
      blockHeader(container, 'body').querySelector('[data-testid="canvas-block-hint"]'),
    ).toBeNull();
  });
});
