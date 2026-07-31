// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, it, expect, beforeEach } from 'vitest';
import { render, html } from 'lit';
import { EditorEngine } from '../engine/EditorEngine.js';
import { createDefaultRegistry } from '../engine/registry.js';
import { createStencilDefinition } from '../components/stencil/stencil-registration.js';
import './EpistolaCanvas.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';

/**
 * Build a doc shaped:
 *
 *   root → [stencil(published) → [text-locked, placeholder], text-toplevel]
 *
 * The placeholder has a default text and a fill text; whether the default slot
 * is rendered on the canvas depends on whether the fill is populated. Each test
 * sets the children array as needed.
 */
function buildDoc({ fillPopulated }: { fillPopulated: boolean }): TemplateDocument {
  const id = (s: string) => s as NodeId;
  const sid = (s: string) => s as SlotId;

  return {
    modelVersion: 1,
    root: id('root'),
    nodes: {
      root: { id: id('root'), type: 'root', slots: [sid('root-slot')] },
      stencil: {
        id: id('stencil'),
        type: 'stencil',
        slots: [sid('stencil-slot')],
        props: { stencilId: 'A', version: 1 },
      },
      'text-locked': {
        id: id('text-locked'),
        type: 'text',
        slots: [],
        props: { content: null },
      },
      placeholder: {
        id: id('placeholder'),
        type: 'placeholder',
        slots: [sid('ph-default'), sid('ph-fill')],
        props: { name: 'body', description: '' },
      },
      'text-default': {
        id: id('text-default'),
        type: 'text',
        slots: [],
        props: { content: null },
      },
      'text-fill': {
        id: id('text-fill'),
        type: 'text',
        slots: [],
        props: { content: null },
      },
      'text-toplevel': {
        id: id('text-toplevel'),
        type: 'text',
        slots: [],
        props: { content: null },
      },
    },
    slots: {
      'root-slot': {
        id: sid('root-slot'),
        nodeId: id('root'),
        name: 'children',
        children: [id('stencil'), id('text-toplevel')],
      },
      'stencil-slot': {
        id: sid('stencil-slot'),
        nodeId: id('stencil'),
        name: 'children',
        children: [id('text-locked'), id('placeholder')],
      },
      'ph-default': {
        id: sid('ph-default'),
        nodeId: id('placeholder'),
        name: 'default',
        children: [id('text-default')],
      },
      'ph-fill': {
        id: sid('ph-fill'),
        nodeId: id('placeholder'),
        name: 'fill',
        children: fillPopulated ? [id('text-fill')] : [],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

function tabindexFor(container: HTMLElement, nodeId: string): string | null {
  return (
    container
      .querySelector<HTMLElement>(`.canvas-block[data-node-id="${nodeId}"]`)
      ?.getAttribute('tabindex') ?? null
  );
}

async function renderCanvas(container: HTMLElement, engine: EditorEngine) {
  render(html`<epistola-canvas .engine=${engine} .doc=${engine.doc}></epistola-canvas>`, container);
  // Lit needs two microtasks for nested template updates to settle.
  await Promise.resolve();
  await Promise.resolve();
}

function setupEngine(fillPopulated: boolean): EditorEngine {
  const registry = createDefaultRegistry();
  registry.register(createStencilDefinition({ callbacks: null }));
  return new EditorEngine(buildDoc({ fillPopulated }), registry);
}

describe('EpistolaCanvas — read-only blocks are not user-focusable', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  it('top-level blocks (including the published stencil itself) stay focusable', async () => {
    const engine = setupEngine(true);
    await renderCanvas(container, engine);

    expect(tabindexFor(container, 'text-toplevel')).toBe('0');
    expect(tabindexFor(container, 'stencil')).toBe('0');
  });

  it('moves focus into an empty text editor when its block is selected', async () => {
    const engine = setupEngine(true);
    await renderCanvas(container, engine);

    const block = container.querySelector<HTMLElement>(
      '.canvas-block[data-node-id="text-toplevel"]',
    );
    expect(block).not.toBeNull();

    block!.click();
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));

    expect(engine.selectedNodeId).toBe('text-toplevel');
    expect(document.activeElement?.classList.contains('ProseMirror')).toBe(true);
  });

  it('blocks inside the locked stencil children slot have no tabindex (out of tab cycle and click cycle)', async () => {
    const engine = setupEngine(true);
    await renderCanvas(container, engine);

    expect(tabindexFor(container, 'text-locked')).toBeNull();
    expect(tabindexFor(container, 'placeholder')).toBeNull();
  });

  it('blocks inside the placeholder fill slot are focusable (editable: true breaks the lock)', async () => {
    const engine = setupEngine(true);
    await renderCanvas(container, engine);

    expect(tabindexFor(container, 'text-fill')).toBe('0');
  });

  it('blocks inside the placeholder default slot are non-focusable when fill is empty (preview)', async () => {
    // With fill empty, the placeholder renderCanvas exposes the default slot as a
    // greyed-out aria-hidden preview. The block is in the DOM, but its parent
    // slot inherits the stencil's lock — it must not be a tab stop or click target.
    const engine = setupEngine(false);
    await renderCanvas(container, engine);

    expect(tabindexFor(container, 'text-default')).toBeNull();
  });

  it('offers an add-block action for an empty editable fill slot', async () => {
    const engine = setupEngine(false);
    await renderCanvas(container, engine);

    const canvas = container.querySelector('epistola-canvas');
    const received: string[] = [];
    canvas?.addEventListener('epistola-insert-block-at-slot', (event) => {
      received.push((event as CustomEvent<{ slotId: string }>).detail.slotId);
    });

    const addButton = container.querySelector<HTMLButtonElement>(
      '[data-testid="placeholder-fill-add"]',
    );
    expect(addButton).not.toBeNull();
    addButton!.click();

    expect(received).toEqual(['ph-fill']);
    expect(engine.selectedNodeId).toBeNull();
  });

  it('does not offer the empty-fill action once the fill has content', async () => {
    const engine = setupEngine(true);
    await renderCanvas(container, engine);

    expect(container.querySelector('[data-testid="placeholder-fill-add"]')).toBeNull();
  });

  it('clicking a read-only block bubbles up — selection lands on the nearest unlocked ancestor', async () => {
    // Click on the placeholder canvas-block (which is locked). Since we removed
    // its @click handler and propagation isn't stopped, the click bubbles up to
    // the published stencil's canvas-block (unlocked) and selects the stencil.
    const engine = setupEngine(true);
    await renderCanvas(container, engine);

    const placeholderBlock = container.querySelector<HTMLElement>(
      '.canvas-block[data-node-id="placeholder"]',
    )!;
    placeholderBlock.click();
    await Promise.resolve();

    expect(engine.selectedNodeId).toBe('stencil');
  });
});
