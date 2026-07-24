// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, it, expect } from 'vitest';
import { captureFillsByName, reKeyCapturedFill } from './preserve-fills.js';
import type { TemplateDocument, NodeId, SlotId } from '../../types/index.js';

describe('preserve-fills', () => {
  /**
   * Build a stencil-extracted-style doc: root → placeholder(default + fill).
   * The placeholder has the given fill children.
   */
  function makeDoc(opts: {
    placeholderName: string;
    fillTextContent: string | null; // null = empty fill
  }): TemplateDocument {
    const fillChildren = opts.fillTextContent === null ? [] : ['fill-text'];
    const baseNodes: Record<string, unknown> = {
      root: { id: 'root', type: 'root', slots: ['rs'] },
      ph: {
        id: 'ph',
        type: 'placeholder',
        slots: ['ph-default', 'ph-fill'],
        props: { name: opts.placeholderName },
      },
    };
    if (opts.fillTextContent !== null) {
      baseNodes['fill-text'] = {
        id: 'fill-text',
        type: 'text',
        slots: [],
        props: { content: opts.fillTextContent },
      };
    }
    return {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: baseNodes as TemplateDocument['nodes'],
      slots: {
        rs: { id: 'rs', nodeId: 'root', name: 'children', children: ['ph'] },
        'ph-default': { id: 'ph-default', nodeId: 'ph', name: 'default', children: [] },
        'ph-fill': { id: 'ph-fill', nodeId: 'ph', name: 'fill', children: fillChildren },
      } as unknown as TemplateDocument['slots'],
      themeRef: { type: 'inherit' },
    };
  }

  it('captures only fill slot content, keyed by placeholder name', () => {
    const doc = makeDoc({ placeholderName: 'body', fillTextContent: 'override' });
    const captured = captureFillsByName(doc);
    expect(captured.size).toBe(1);
    expect(captured.has('body')).toBe(true);
    const cap = captured.get('body')!;
    expect(cap.rootChildIds).toEqual(['fill-text']);
    expect(cap.nodes.size).toBe(1);
    expect(cap.nodes.get('fill-text')?.props?.content).toBe('override');
  });

  it('skips placeholders with empty fill', () => {
    const doc = makeDoc({ placeholderName: 'body', fillTextContent: null });
    const captured = captureFillsByName(doc);
    expect(captured.size).toBe(0);
  });

  it('skips placeholders with no name', () => {
    const doc = makeDoc({ placeholderName: '', fillTextContent: 'override' });
    const captured = captureFillsByName(doc);
    expect(captured.size).toBe(0);
  });

  it('reKeyCapturedFill produces fresh IDs and preserves content', () => {
    const doc = makeDoc({ placeholderName: 'body', fillTextContent: 'override' });
    const captured = captureFillsByName(doc);
    const cap = captured.get('body')!;

    const reKeyed = reKeyCapturedFill(cap);
    // One node total (the text), one re-keyed root child.
    expect(reKeyed.nodes).toHaveLength(1);
    expect(reKeyed.rootChildIds).toHaveLength(1);
    // ID is fresh (not 'fill-text' anymore).
    expect(reKeyed.rootChildIds[0]).not.toBe('fill-text');
    expect(reKeyed.nodes[0].id).toBe(reKeyed.rootChildIds[0]);
    // Content preserved.
    expect(reKeyed.nodes[0].props?.content).toBe('override');
  });

  it('reKeyCapturedFill assigns different IDs each call', () => {
    const doc = makeDoc({ placeholderName: 'body', fillTextContent: 'override' });
    const captured = captureFillsByName(doc);
    const cap = captured.get('body')!;
    const a = reKeyCapturedFill(cap);
    const b = reKeyCapturedFill(cap);
    expect(a.rootChildIds[0]).not.toEqual(b.rootChildIds[0]);
  });

  it('captures multiple placeholders by name', () => {
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root', type: 'root', slots: ['rs'] },
        ph1: {
          id: 'ph1',
          type: 'placeholder',
          slots: ['ph1-d', 'ph1-f'],
          props: { name: 'header' },
        },
        ph2: {
          id: 'ph2',
          type: 'placeholder',
          slots: ['ph2-d', 'ph2-f'],
          props: { name: 'footer' },
        },
        t1: { id: 't1', type: 'text', slots: [], props: { content: 'h-override' } },
        t2: { id: 't2', type: 'text', slots: [], props: { content: 'f-override' } },
      } as unknown as TemplateDocument['nodes'],
      slots: {
        rs: { id: 'rs', nodeId: 'root', name: 'children', children: ['ph1', 'ph2'] },
        'ph1-d': { id: 'ph1-d', nodeId: 'ph1', name: 'default', children: [] },
        'ph1-f': { id: 'ph1-f', nodeId: 'ph1', name: 'fill', children: ['t1'] },
        'ph2-d': { id: 'ph2-d', nodeId: 'ph2', name: 'default', children: [] },
        'ph2-f': { id: 'ph2-f', nodeId: 'ph2', name: 'fill', children: ['t2'] },
      } as unknown as TemplateDocument['slots'],
      themeRef: { type: 'inherit' },
    };
    const captured = captureFillsByName(doc);
    expect(captured.size).toBe(2);
    expect(captured.get('header')?.nodes.get('t1')?.props?.content).toBe('h-override');
    expect(captured.get('footer')?.nodes.get('t2')?.props?.content).toBe('f-override');
  });
});
