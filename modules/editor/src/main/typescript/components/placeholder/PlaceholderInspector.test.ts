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

describe('PlaceholderInspector — name uniqueness scoping', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  /**
   * Build a doc with two stencils, each containing one placeholder. The first
   * stencil's placeholder is named `body`. The second's is editable and is the
   * one we render in the inspector — we verify whether typing `body` triggers
   * a duplicate-name error.
   */
  function setupTwoStencilsWith(
    firstName: string,
    secondInitialName: string,
    secondStencilProps: Record<string, unknown>,
  ): { engine: EditorEngine; placeholder: Node } {
    const registry = createDefaultRegistry();
    registry.register(createStencilDefinition({ callbacks: null }));
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
        'stencil-a': {
          id: 'stencil-a' as NodeId,
          type: 'stencil',
          slots: ['stencil-a-slot' as SlotId],
          props: { stencilId: 'A', version: 1, isDraft: false },
        },
        'stencil-b': {
          id: 'stencil-b' as NodeId,
          type: 'stencil',
          slots: ['stencil-b-slot' as SlotId],
          props: secondStencilProps,
        },
        'ph-a': {
          id: 'ph-a' as NodeId,
          type: 'placeholder',
          slots: ['ph-a-default' as SlotId, 'ph-a-fill' as SlotId],
          props: { name: firstName, description: '' },
        },
        'ph-b': {
          id: 'ph-b' as NodeId,
          type: 'placeholder',
          slots: ['ph-b-default' as SlotId, 'ph-b-fill' as SlotId],
          props: { name: secondInitialName, description: '' },
        },
      },
      slots: {
        'root-slot': {
          id: 'root-slot' as SlotId,
          nodeId: 'root' as NodeId,
          name: 'children',
          children: ['stencil-a' as NodeId, 'stencil-b' as NodeId],
        },
        'stencil-a-slot': {
          id: 'stencil-a-slot' as SlotId,
          nodeId: 'stencil-a' as NodeId,
          name: 'children',
          children: ['ph-a' as NodeId],
        },
        'stencil-b-slot': {
          id: 'stencil-b-slot' as SlotId,
          nodeId: 'stencil-b' as NodeId,
          name: 'children',
          children: ['ph-b' as NodeId],
        },
        'ph-a-default': {
          id: 'ph-a-default' as SlotId,
          nodeId: 'ph-a' as NodeId,
          name: 'default',
          children: [],
        },
        'ph-a-fill': {
          id: 'ph-a-fill' as SlotId,
          nodeId: 'ph-a' as NodeId,
          name: 'fill',
          children: [],
        },
        'ph-b-default': {
          id: 'ph-b-default' as SlotId,
          nodeId: 'ph-b' as NodeId,
          name: 'default',
          children: [],
        },
        'ph-b-fill': {
          id: 'ph-b-fill' as SlotId,
          nodeId: 'ph-b' as NodeId,
          name: 'fill',
          children: [],
        },
      },
      themeRef: { type: 'inherit' },
    };
    const engine = new EditorEngine(doc, registry);
    return { engine, placeholder: engine.doc.nodes['ph-b' as NodeId] };
  }

  it('allows the same placeholder name in a sibling stencil', async () => {
    const { engine, placeholder } = setupTwoStencilsWith('body', 'placeholder-1', {
      stencilId: 'B',
      version: 1,
      isDraft: true,
    });
    render(
      html`<placeholder-inspector .node=${placeholder} .engine=${engine}></placeholder-inspector>`,
      container,
    );
    await Promise.resolve();
    const nameInput = container.querySelector<HTMLInputElement>('#placeholder-name')!;
    nameInput.value = 'body';
    nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    await Promise.resolve();
    expect(container.querySelector('.inspector-field-error')).toBeNull();
  });

  it('rejects a duplicate name within the same stencil', async () => {
    // Two placeholders inside stencil-b; rename ph-b to match ph-b-sibling.
    const registry = createDefaultRegistry();
    registry.register(createStencilDefinition({ callbacks: null }));
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
        'stencil-b': {
          id: 'stencil-b' as NodeId,
          type: 'stencil',
          slots: ['stencil-b-slot' as SlotId],
          props: { stencilId: 'B', version: 1, isDraft: true },
        },
        'ph-x': {
          id: 'ph-x' as NodeId,
          type: 'placeholder',
          slots: ['ph-x-fill' as SlotId],
          props: { name: 'header', description: '' },
        },
        'ph-y': {
          id: 'ph-y' as NodeId,
          type: 'placeholder',
          slots: ['ph-y-fill' as SlotId],
          props: { name: 'footer', description: '' },
        },
      },
      slots: {
        'root-slot': {
          id: 'root-slot' as SlotId,
          nodeId: 'root' as NodeId,
          name: 'children',
          children: ['stencil-b' as NodeId],
        },
        'stencil-b-slot': {
          id: 'stencil-b-slot' as SlotId,
          nodeId: 'stencil-b' as NodeId,
          name: 'children',
          children: ['ph-x' as NodeId, 'ph-y' as NodeId],
        },
        'ph-x-fill': {
          id: 'ph-x-fill' as SlotId,
          nodeId: 'ph-x' as NodeId,
          name: 'fill',
          children: [],
        },
        'ph-y-fill': {
          id: 'ph-y-fill' as SlotId,
          nodeId: 'ph-y' as NodeId,
          name: 'fill',
          children: [],
        },
      },
      themeRef: { type: 'inherit' },
    };
    const engine = new EditorEngine(doc, registry);
    const placeholder = engine.doc.nodes['ph-y' as NodeId];
    render(
      html`<placeholder-inspector .node=${placeholder} .engine=${engine}></placeholder-inspector>`,
      container,
    );
    await Promise.resolve();
    const nameInput = container.querySelector<HTMLInputElement>('#placeholder-name')!;
    nameInput.value = 'header';
    nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    await Promise.resolve();
    const error = container.querySelector('.inspector-field-error');
    expect(error?.textContent).toContain("'header'");
    expect(error?.textContent).toContain('already used');
  });
});
