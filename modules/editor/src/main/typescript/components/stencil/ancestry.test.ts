// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, it, expect } from 'vitest';
import { computeAncestorScope, placeholderContext } from './ancestry.js';
import { buildIndexes } from '../../engine/indexes.js';
import type { NodeId, SlotId, TemplateDocument } from '../../types/index.js';

describe('computeAncestorScope', () => {
  it('reports no ancestors for a flat root slot', () => {
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] },
      },
      slots: {
        'root-slot': {
          id: 'root-slot' as SlotId,
          nodeId: 'root' as NodeId,
          name: 'children',
          children: [],
        },
      },
      themeRef: { type: 'inherit' },
    };
    const scope = computeAncestorScope(doc, 'root-slot' as SlotId, buildIndexes(doc));
    expect(scope.stencilIds.size).toBe(0);
    expect(scope.hasStencilAncestor).toBe(false);
    expect(scope.hasPlaceholderAncestor).toBe(false);
  });

  it('detects a stencil ancestor and collects its stencilId', () => {
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['rs' as SlotId] },
        s1: {
          id: 's1' as NodeId,
          type: 'stencil',
          slots: ['s1-slot' as SlotId],
          props: { stencilId: 'header', version: 1 },
        },
      },
      slots: {
        rs: {
          id: 'rs' as SlotId,
          nodeId: 'root' as NodeId,
          name: 'children',
          children: ['s1' as NodeId],
        },
        's1-slot': {
          id: 's1-slot' as SlotId,
          nodeId: 's1' as NodeId,
          name: 'children',
          children: [],
        },
      },
      themeRef: { type: 'inherit' },
    };
    const scope = computeAncestorScope(doc, 's1-slot' as SlotId, buildIndexes(doc));
    expect(scope.stencilIds.has('header')).toBe(true);
    expect(scope.hasStencilAncestor).toBe(true);
    expect(scope.hasPlaceholderAncestor).toBe(false);
  });

  it('detects nested placeholder + stencil ancestors', () => {
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['rs' as SlotId] },
        s1: {
          id: 's1' as NodeId,
          type: 'stencil',
          slots: ['s1-slot' as SlotId],
          props: { stencilId: 'A', version: 1 },
        },
        ph: {
          id: 'ph' as NodeId,
          type: 'placeholder',
          slots: ['ph-fill' as SlotId],
          props: { name: 'body' },
        },
      },
      slots: {
        rs: {
          id: 'rs' as SlotId,
          nodeId: 'root' as NodeId,
          name: 'children',
          children: ['s1' as NodeId],
        },
        's1-slot': {
          id: 's1-slot' as SlotId,
          nodeId: 's1' as NodeId,
          name: 'children',
          children: ['ph' as NodeId],
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
    const scope = computeAncestorScope(doc, 'ph-fill' as SlotId, buildIndexes(doc));
    expect(scope.stencilIds.has('A')).toBe(true);
    expect(scope.hasStencilAncestor).toBe(true);
    expect(scope.hasPlaceholderAncestor).toBe(true);
  });

  it('collects all stencilIds when stencils are nested', () => {
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['rs' as SlotId] },
        sA: {
          id: 'sA' as NodeId,
          type: 'stencil',
          slots: ['sA-slot' as SlotId],
          props: { stencilId: 'A', version: 1 },
        },
        sB: {
          id: 'sB' as NodeId,
          type: 'stencil',
          slots: ['sB-slot' as SlotId],
          props: { stencilId: 'B', version: 1 },
        },
      },
      slots: {
        rs: {
          id: 'rs' as SlotId,
          nodeId: 'root' as NodeId,
          name: 'children',
          children: ['sA' as NodeId],
        },
        'sA-slot': {
          id: 'sA-slot' as SlotId,
          nodeId: 'sA' as NodeId,
          name: 'children',
          children: ['sB' as NodeId],
        },
        'sB-slot': {
          id: 'sB-slot' as SlotId,
          nodeId: 'sB' as NodeId,
          name: 'children',
          children: [],
        },
      },
      themeRef: { type: 'inherit' },
    };
    const scope = computeAncestorScope(doc, 'sB-slot' as SlotId, buildIndexes(doc));
    expect(scope.stencilIds.has('A')).toBe(true);
    expect(scope.stencilIds.has('B')).toBe(true);
    expect(scope.stencilIds.size).toBe(2);
  });
});

describe('placeholderContext', () => {
  function docWithPlaceholderInStencil(stencilProps: Record<string, unknown>): {
    doc: TemplateDocument;
  } {
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['rs' as SlotId] },
        stencil: {
          id: 'stencil' as NodeId,
          type: 'stencil',
          slots: ['stencil-slot' as SlotId],
          props: stencilProps,
        },
        ph: {
          id: 'ph' as NodeId,
          type: 'placeholder',
          slots: ['ph-d' as SlotId, 'ph-f' as SlotId],
          props: { name: 'body' },
        },
      },
      slots: {
        rs: {
          id: 'rs' as SlotId,
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
        'ph-d': { id: 'ph-d' as SlotId, nodeId: 'ph' as NodeId, name: 'default', children: [] },
        'ph-f': { id: 'ph-f' as SlotId, nodeId: 'ph' as NodeId, name: 'fill', children: [] },
      },
      themeRef: { type: 'inherit' },
    };
    return { doc };
  }

  it('reports stencil-author when the nearest stencil is a draft', () => {
    const { doc } = docWithPlaceholderInStencil({ stencilId: 'A', version: 1, isDraft: true });
    expect(placeholderContext(doc, 'ph' as NodeId, buildIndexes(doc))).toBe('stencil-author');
  });

  it('reports stencil-author when the nearest stencil is unlinked', () => {
    const { doc } = docWithPlaceholderInStencil({ stencilId: null, isDraft: false });
    expect(placeholderContext(doc, 'ph' as NodeId, buildIndexes(doc))).toBe('stencil-author');
  });

  it('reports template-fill when the nearest stencil is a published locked stencil', () => {
    const { doc } = docWithPlaceholderInStencil({ stencilId: 'A', version: 1, isDraft: false });
    expect(placeholderContext(doc, 'ph' as NodeId, buildIndexes(doc))).toBe('template-fill');
  });

  it('reports stencil-author when there is no stencil ancestor', () => {
    // Placeholder at the top of a stencil definition (no embedding stencil yet).
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['rs' as SlotId] },
        ph: {
          id: 'ph' as NodeId,
          type: 'placeholder',
          slots: ['ph-d' as SlotId, 'ph-f' as SlotId],
          props: { name: 'body' },
        },
      },
      slots: {
        rs: {
          id: 'rs' as SlotId,
          nodeId: 'root' as NodeId,
          name: 'children',
          children: ['ph' as NodeId],
        },
        'ph-d': { id: 'ph-d' as SlotId, nodeId: 'ph' as NodeId, name: 'default', children: [] },
        'ph-f': { id: 'ph-f' as SlotId, nodeId: 'ph' as NodeId, name: 'fill', children: [] },
      },
      themeRef: { type: 'inherit' },
    };
    expect(placeholderContext(doc, 'ph' as NodeId, buildIndexes(doc))).toBe('stencil-author');
  });
});
