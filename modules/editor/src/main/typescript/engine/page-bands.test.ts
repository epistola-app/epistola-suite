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
  it('describes the legacy shape: first-page and running header, one footer at the end', () => {
    const labels = describePageBands(
      doc('pageheader:first', 'pageheader:running', 'text:body', 'pagefooter:footer'),
    );

    expect(labels.get('first' as NodeId)).toBe('first page of section');
    expect(labels.get('running' as NodeId)).toBe("from the section's second page");
    expect(labels.get('footer' as NodeId)).toBe('this section');
  });

  it('tells a section-start header from a header after content', () => {
    const labels = describePageBands(
      doc(
        'pageheader:intro',
        'text:a',
        'pageheader:chapter',
        'text:b',
        'pagebreak:br',
        'pageheader:appendix',
        'text:c',
      ),
    );

    expect(labels.get('intro' as NodeId)).toBe('from this page');
    expect(labels.get('chapter' as NodeId)).toBe('from the next page');
    expect(labels.get('appendix' as NodeId)).toBe('from this page');
  });

  it('counts a header at the top of a stencil as the start of its section', () => {
    const labels = describePageBands(
      doc('text:cover', 'pagebreak:br', [
        'stencil:shell',
        ['pageheader:letterhead', 'text:letter', 'pagefooter:foot'],
      ]),
    );

    expect(labels.get('letterhead' as NodeId)).toBe('from this page');
    expect(labels.get('foot' as NodeId)).toBe('this section and the sections above');
  });

  it('marks bands inside conditionals and loops as depending on data', () => {
    const labels = describePageBands(doc(['conditional:if', ['pageheader:maybe']], 'text:body'));

    expect(labels.get('maybe' as NodeId)).toBe('from this page · depends on data');
  });

  it('flags footers in one section that are not next to each other', () => {
    const scattered = describePageBands(doc('pagefooter:a', 'text:body', 'pagefooter:b'));
    const adjacent = describePageBands(doc('text:body', 'pagefooter:a', 'pagefooter:b'));

    expect(scattered.get('a' as NodeId)).toContain('not next to the section');
    expect(adjacent.get('a' as NodeId)).toBe('first page of section');
    expect(adjacent.get('b' as NodeId)).toBe("from the section's second page");
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

describe('page band canvas label', () => {
  it('appends where the band applies to the block label', () => {
    const d = doc('pageheader:first', 'pageheader:running', 'text:body');
    const engine = new EditorEngine(d, createDefaultRegistry());
    const def = engine.registry.get('pageheader');

    expect(def?.getLabel?.(d.nodes['first'], engine)).toBe('Page Header · first page of section');
    expect(def?.getLabel?.(d.nodes['running'], engine)).toBe(
      "Page Header · from the section's second page",
    );
  });
});
