// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, it, expect, beforeEach } from 'vitest';
import { resetCounter } from '../../engine/test-helpers.js';
import { extractSubtree } from './extract-subtree.js';
import { setupEngine, insertStencil, insertText, getStencilSlot } from './stencil-test-helpers.js';
import type { TemplateDocument, NodeId } from '../../types/index.js';

beforeEach(() => {
  resetCounter();
});

describe('extractSubtree for inspector actions', () => {
  it('extracts stencil content as standalone document for publishing', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);

    insertText(engine, registry, stencilSlot, 'Header text');
    insertText(engine, registry, stencilSlot, 'Subtitle');

    const extracted = extractSubtree(engine.doc, stencilId);

    // Should have a root + 2 text nodes
    const textNodes = Object.values(extracted.nodes).filter((n) => n.type === 'text');
    expect(textNodes).toHaveLength(2);
    expect(textNodes.some((n) => n.props?.content === 'Header text')).toBe(true);
    expect(textNodes.some((n) => n.props?.content === 'Subtitle')).toBe(true);
  });

  it('extracted content is a valid standalone document', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);
    const stencilSlot = getStencilSlot(engine, stencilId);
    insertText(engine, registry, stencilSlot, 'Content');

    const extracted = extractSubtree(engine.doc, stencilId);

    // Has a root
    expect(extracted.root).toBeDefined();
    expect(extracted.nodes[extracted.root]).toBeDefined();

    // Root has a slot with children
    const rootNode = extracted.nodes[extracted.root];
    expect(rootNode.slots).toHaveLength(1);
    const rootSlot = extracted.slots[rootNode.slots[0]];
    expect(rootSlot).toBeDefined();
    expect(rootSlot.children.length).toBeGreaterThan(0);

    // All referenced nodes exist
    for (const slot of Object.values(extracted.slots)) {
      for (const childId of slot.children) {
        expect(extracted.nodes[childId]).toBeDefined();
      }
    }
  });

  it('strips placeholder fill slot children but keeps the slot record', () => {
    // Build a doc directly: stencil → placeholder(default + fill, both populated).
    // The user override (fill content) must not round-trip into the stencil
    // definition — extractSubtree returns the fill slot empty.
    const doc = {
      modelVersion: 1,
      root: 'root',
      nodes: {
        root: { id: 'root', type: 'root', slots: ['rs'] },
        stencil: {
          id: 'stencil',
          type: 'stencil',
          slots: ['stencil-slot'],
          props: { stencilId: 'A', version: 1, isDraft: true },
        },
        ph: {
          id: 'ph',
          type: 'placeholder',
          slots: ['ph-default', 'ph-fill'],
          props: { name: 'body' },
        },
        defaultText: {
          id: 'defaultText',
          type: 'text',
          slots: [],
          props: { content: 'default content' },
        },
        fillText: {
          id: 'fillText',
          type: 'text',
          slots: [],
          props: { content: 'override content' },
        },
      },
      slots: {
        rs: { id: 'rs', nodeId: 'root', name: 'children', children: ['stencil'] },
        'stencil-slot': {
          id: 'stencil-slot',
          nodeId: 'stencil',
          name: 'children',
          children: ['ph'],
        },
        'ph-default': {
          id: 'ph-default',
          nodeId: 'ph',
          name: 'default',
          children: ['defaultText'],
        },
        'ph-fill': { id: 'ph-fill', nodeId: 'ph', name: 'fill', children: ['fillText'] },
      },
      themeRef: { type: 'inherit' },
    } as unknown as TemplateDocument;

    const extracted = extractSubtree(doc, 'stencil' as NodeId);

    // The placeholder is included.
    const ph = Object.values(extracted.nodes).find((n) => n.type === 'placeholder');
    expect(ph).toBeDefined();

    // Default content survives.
    const texts = Object.values(extracted.nodes).filter((n) => n.type === 'text');
    expect(texts.some((n) => n.props?.content === 'default content')).toBe(true);

    // Fill content was stripped.
    expect(texts.some((n) => n.props?.content === 'override content')).toBe(false);

    // Fill slot is still present (structurally) but empty.
    const fillSlot = Object.values(extracted.slots).find((s) => s.name === 'fill');
    expect(fillSlot).toBeDefined();
    expect(fillSlot!.children).toHaveLength(0);
  });
});
