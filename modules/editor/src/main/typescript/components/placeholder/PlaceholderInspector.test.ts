// @vitest-environment happy-dom

import { describe, it, expect, beforeEach } from 'vitest';
import { render, html } from 'lit';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createDefaultRegistry } from '../../engine/registry.js';
import { createStencilDefinition } from '../stencil/stencil-registration.js';
import './PlaceholderInspector.js';
import type { Node, NodeId, SlotId, TemplateDocument } from '../../types/index.js';

/**
 * Build a minimal doc where a placeholder lives inside a stencil with the
 * given props. Returns the engine and the placeholder node.
 */
function setupEngineWithPlaceholder(stencilProps: Record<string, unknown>): {
  engine: EditorEngine;
  placeholder: Node;
} {
  const registry = createDefaultRegistry();
  registry.register(createStencilDefinition({ callbacks: null }));
  const doc: TemplateDocument = {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: {
      root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      stencil: {
        id: 'stencil' as NodeId,
        type: 'stencil',
        slots: ['stencil-slot' as SlotId],
        props: stencilProps,
      },
      ph: {
        id: 'ph' as NodeId,
        type: 'placeholder',
        slots: ['ph-default' as SlotId, 'ph-fill' as SlotId],
        props: { name: 'body', description: 'Body' },
      },
    },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: ['stencil' as NodeId],
      },
      'stencil-slot': {
        id: 'stencil-slot' as SlotId,
        nodeId: 'stencil' as NodeId,
        name: 'children',
        children: ['ph' as NodeId],
      },
      'ph-default': {
        id: 'ph-default' as SlotId,
        nodeId: 'ph' as NodeId,
        name: 'default',
        children: [],
      },
      'ph-fill': {
        id: 'ph-fill' as SlotId,
        nodeId: 'ph' as NodeId,
        name: 'fill',
        children: [],
      },
    },
    themeRef: { type: 'inherit' },
  };
  const engine = new EditorEngine(doc, registry);
  return { engine, placeholder: engine.doc.nodes['ph' as NodeId] };
}

describe('PlaceholderInspector — context-aware rendering', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  it('renders editable inputs when the surrounding stencil is in draft mode', async () => {
    const { engine, placeholder } = setupEngineWithPlaceholder({
      stencilId: 'A',
      version: 1,
      isDraft: true,
    });
    render(
      html`<placeholder-inspector .node=${placeholder} .engine=${engine}></placeholder-inspector>`,
      container,
    );
    // Wait a microtask for Lit to render.
    await Promise.resolve();
    const inputs = container.querySelectorAll('input');
    expect(inputs.length).toBeGreaterThan(0);
    const nameInput = container.querySelector<HTMLInputElement>('#placeholder-name');
    expect(nameInput).toBeTruthy();
    expect(nameInput!.value).toBe('body');
  });

  it('renders read-only display when the surrounding stencil is published', async () => {
    const { engine, placeholder } = setupEngineWithPlaceholder({
      stencilId: 'A',
      version: 1,
      isDraft: false,
    });
    render(
      html`<placeholder-inspector .node=${placeholder} .engine=${engine}></placeholder-inspector>`,
      container,
    );
    await Promise.resolve();
    // No editable inputs.
    expect(container.querySelectorAll('input').length).toBe(0);
    // Read-only name shown.
    const readonlyName = container.querySelector('.placeholder-inspector-readonly-name');
    expect(readonlyName?.textContent?.trim()).toBe('body');
    // Hint about the stencil being authoritative.
    expect(container.textContent).toContain('Managed by the stencil definition');
  });

  it('renders editable when the surrounding stencil is unlinked (no stencilId)', async () => {
    const { engine, placeholder } = setupEngineWithPlaceholder({
      stencilId: null,
      version: 1,
      isDraft: false,
    });
    render(
      html`<placeholder-inspector .node=${placeholder} .engine=${engine}></placeholder-inspector>`,
      container,
    );
    await Promise.resolve();
    expect(container.querySelectorAll('input').length).toBeGreaterThan(0);
  });
});
