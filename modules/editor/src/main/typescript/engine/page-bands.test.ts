// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import type { Node, NodeId, SlotId, TemplateDocument } from '../types/index.js';
import { buildIndexes } from './indexes.js';
import {
  NESTED_PAGE_BAND_ERROR,
  describePageBands,
  pageBandNestingError,
  rootBodyBounds,
  subtreeOf,
} from './page-bands.js';
import { createDefaultRegistry } from './registry.js';
import { EditorEngine } from './EditorEngine.js';

/**
 * Builds a document from a nested spec, in flow order. A spec is `type:id`, optionally with
 * children, e.g. `['container:c', ['pageheader:h']]`.
 */
type Spec = string | [string, Spec[]];

function doc(...rootChildren: Spec[]): TemplateDocument {
  const nodes: Record<string, Node> = {};
  const slots: TemplateDocument['slots'] = {};
  const add = (spec: Spec): NodeId => {
    const [head, children] = typeof spec === 'string' ? [spec, [] as Spec[]] : spec;
    const [type, id] = head.split(':');
    const slotId = `${id}-slot` as SlotId;
    const leaf = type === 'text' || type === 'pagebreak';
    nodes[id] = { id: id as NodeId, type, slots: leaf ? [] : [slotId] };
    if (!leaf) {
      slots[slotId] = {
        id: slotId,
        nodeId: id as NodeId,
        name: 'children',
        children: children.map(add),
      };
    }
    return id as NodeId;
  };
  const children = rootChildren.map(add);
  nodes.root = { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] };
  slots['root-slot' as SlotId] = {
    id: 'root-slot' as SlotId,
    nodeId: 'root' as NodeId,
    name: 'children',
    children,
  };
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes,
    slots,
    themeRef: { type: 'inherit' },
  } as TemplateDocument;
}

describe('describePageBands', () => {
  const hintOf = (d: TemplateDocument, id: string) => describePageBands(d).get(id as NodeId);

  it('describes the legacy shape: first-page and running header, one footer at the end', () => {
    const d = doc('pageheader:first', 'pageheader:running', 'text:body', 'pagefooter:footer');

    expect(hintOf(d, 'first')).toEqual({
      text: 'Applies to the first page of its section only; the next header takes the page after it.',
      tone: 'info',
    });
    expect(hintOf(d, 'running')?.text).toBe(
      'Applies from the second page of its section on, until another header takes over.',
    );
    expect(hintOf(d, 'footer')).toEqual({
      text: 'Applies from the page it lands on, and to the pages before it, until another footer lands.',
      tone: 'info',
    });
  });

  it('tells a section-start header from a header after content', () => {
    const d = doc(
      'pageheader:intro',
      'text:a',
      'pageheader:chapter',
      'text:b',
      'pagebreak:br',
      'pageheader:appendix',
      'text:c',
    );

    expect(hintOf(d, 'intro')?.text).toBe(
      'Applies from this page on, until another header takes over.',
    );
    expect(hintOf(d, 'chapter')?.text).toContain(
      'Takes over from the page after the one it lands on',
    );
    expect(hintOf(d, 'appendix')?.text).toBe(
      'Applies from this page on, until another header takes over.',
    );
  });

  it('counts a header at the top of a stencil as the start of its section', () => {
    const d = doc('text:cover', 'pagebreak:br', [
      'stencil:shell',
      ['pageheader:letterhead', 'text:letter', 'pagefooter:foot'],
    ]);

    expect(hintOf(d, 'letterhead')?.text).toBe(
      'Applies from this page on, until another header takes over.',
    );
    expect(hintOf(d, 'foot')?.text).toContain('and to the pages before it');
  });

  it('says when a band only applies if its conditional or loop renders it', () => {
    const d = doc(['conditional:if', ['pageheader:maybe']], 'text:body');

    expect(hintOf(d, 'maybe')?.text).toContain('inside a conditional or loop');
  });

  it('warns when a footer may be, or will be, skipped for the footer before it', () => {
    const d = doc(
      'pagefooter:first',
      'pagefooter:skipped',
      'text:body',
      'pagefooter:maybe',
      'pagebreak:br',
      'pagefooter:next-page',
    );

    expect(hintOf(d, 'first')?.tone).toBe('info');
    expect(hintOf(d, 'skipped')).toMatchObject({ tone: 'warning' });
    expect(hintOf(d, 'skipped')?.text).toMatch(
      /^Skipped: it lands on the same page as the footer before it/,
    );
    expect(hintOf(d, 'maybe')).toMatchObject({ tone: 'warning' });
    expect(hintOf(d, 'maybe')?.text).toContain(
      'only if the footer before it did not land on that page too',
    );
    expect(hintOf(d, 'next-page')).toEqual({
      text: 'Applies from the page it lands on, until another footer lands.',
      tone: 'info',
    });
  });
});

describe('rootBodyBounds', () => {
  it('spans between the headers that open the document and the footers that close it', () => {
    const d = doc(
      'pageheader:h1',
      'pageheader:h2',
      'text:a',
      'pageheader:mid',
      'text:b',
      'pagefooter:f1',
      'pagefooter:f2',
    );

    expect(rootBodyBounds(d)).toEqual({ slotId: 'root-slot', startIndex: 2, endIndex: 5 });
  });

  it('is the whole root slot without headers or footers', () => {
    expect(rootBodyBounds(doc('text:a', 'text:b'))).toEqual({
      slotId: 'root-slot',
      startIndex: 0,
      endIndex: 2,
    });
  });
});

describe('pageBandNestingError', () => {
  const d = doc('pageheader:h', ['container:c', ['pagefooter:f']], 'text:t');
  const indexes = buildIndexes(d);

  it('rejects a band into a band, and a block holding a band into a band', () => {
    expect(pageBandNestingError(d, indexes, 'h-slot' as SlotId, subtreeOf(d, 'f' as NodeId)!)).toBe(
      NESTED_PAGE_BAND_ERROR,
    );
    expect(pageBandNestingError(d, indexes, 'h-slot' as SlotId, subtreeOf(d, 'c' as NodeId)!)).toBe(
      NESTED_PAGE_BAND_ERROR,
    );
  });

  it('allows ordinary content into a band, and bands into ordinary containers', () => {
    expect(
      pageBandNestingError(d, indexes, 'h-slot' as SlotId, subtreeOf(d, 't' as NodeId)!),
    ).toBeNull();
    expect(
      pageBandNestingError(d, indexes, 'c-slot' as SlotId, subtreeOf(d, 'h' as NodeId)!),
    ).toBeNull();
  });

  it('rejects a subtree that nests bands on its own', () => {
    const nested = doc(['pageheader:outer', ['pagefooter:inner']]);
    const target = buildIndexes(d);

    expect(
      pageBandNestingError(d, target, 'c-slot' as SlotId, subtreeOf(nested, 'outer' as NodeId)!),
    ).toBe(NESTED_PAGE_BAND_ERROR);
  });
});

describe('page band canvas hint', () => {
  it('keeps the plain label and puts where the band applies in a hint', () => {
    const d = doc('pageheader:first', 'pageheader:running', 'text:body');
    const engine = new EditorEngine(d, createDefaultRegistry());
    const def = engine.registry.get('pageheader');

    expect(def?.getLabel).toBeUndefined();
    expect(def?.label).toBe('Page Header');
    expect(def?.getHint?.(d.nodes['first'], engine)).toMatchObject({ tone: 'info' });
    expect(def?.getHint?.(d.nodes['running'], engine)?.text).toContain(
      'second page of its section',
    );
  });
});
