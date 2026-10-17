// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it, vi } from 'vitest';
import { MAX_STENCIL_NESTING_DEPTH } from '@epistola.app/epistola-catalog';
import type { NodeId, SlotId } from '../../types/index.js';
import { buildIndexes } from '../../engine/indexes.js';
import {
  checkStencilNesting,
  collapse,
  expand,
  fetchStencilTree,
  lexicalOwnerOf,
  lexicalScopeAt,
  resolverOver,
  type FetchedStencils,
} from './instances.js';
import {
  buildDocument,
  CATALOG,
  container,
  placeholder,
  storedInstance,
  text,
  textOf,
  workingCopy,
  type Spec,
} from './stencil-test-helpers.js';
import { stencilIdentity } from './node-types.js';
import type { StencilWorkingCopy } from './types.js';

function fetchedOf(...copies: StencilWorkingCopy[]): FetchedStencils {
  return new Map(copies.map((c) => [stencilIdentity(c.ref), c]));
}

const letter = workingCopy(
  'letter',
  [text('hdr', 'Header'), placeholder('ph', 'body', { default: [text('d', 'Default body')] })],
  { parameterSchema: { type: 'object', properties: { name: { type: 'string' } } } },
);

describe('expand (port of StencilInstances.expand)', () => {
  it('inlines the stencil with IDs derived from the instance and puts the fill in its placeholder', () => {
    const doc = buildDocument([storedInstance('inst', 'letter', { body: [text('mine', 'Mine')] })]);

    const { document, findings } = expand(doc, CATALOG, resolverOver(fetchedOf(letter)));

    expect(findings).toEqual([]);
    expect(document.slots['inst:children' as SlotId].children).toEqual(['inst:hdr', 'inst:ph']);
    expect(document.slots['inst:s:ph:fill' as SlotId].children).toEqual(['mine']);
    expect(textOf(document, 'inst:d')).toBe('Default body');
    expect(textOf(document, 'mine')).toBe('Mine');
    // The stored fill carrier is gone; the schema rides on the expanded instance.
    expect(document.nodes['inst:fill:body' as NodeId]).toBeUndefined();
    expect(document.nodes['inst' as NodeId].props?.parameterSchemaSnapshot).toEqual(
      letter.parameterSchema,
    );
  });

  it('expands the same document twice to the same IDs', () => {
    const doc = buildDocument([storedInstance('inst', 'letter', { body: [text('mine')] })]);
    const resolver = resolverOver(fetchedOf(letter));

    const first = expand(doc, CATALOG, resolver).document;
    const second = expand(first, CATALOG, resolver).document;

    expect(Object.keys(second.nodes).toSorted()).toEqual(Object.keys(first.nodes).toSorted());
    expect(second.slots).toEqual(first.slots);
  });

  it('collapses an expanded document back to the stored form, and collapsing is idempotent', () => {
    const stored = collapse(
      buildDocument([storedInstance('inst', 'letter', { body: [text('mine')] })]),
    );
    const expanded = expand(stored, CATALOG, resolverOver(fetchedOf(letter))).document;

    const collapsed = collapse(expanded);

    expect(collapsed.nodes).toEqual(stored.nodes);
    expect(collapsed.slots).toEqual(stored.slots);
    expect(collapse(collapsed)).toEqual(collapsed);
    // Transient props are not stored.
    expect(collapsed.nodes['inst' as NodeId].props?.parameterSchemaSnapshot).toBeUndefined();
  });

  it('expands a stencil inside a stencil, and the template fills a placeholder the outer one offers in an inner fill', () => {
    const inner = workingCopy('inner', [
      text('inner-title', 'Inner'),
      placeholder('inner-ph', 'slot'),
    ]);
    // `outer` uses `inner` and offers its own `outer-body` placeholder inside inner's fill.
    const outer = workingCopy('outer', [
      storedInstance('in', 'inner', { slot: [placeholder('op', 'outer-body')] }),
    ]);
    const doc = buildDocument([storedInstance('inst', 'outer', { 'outer-body': [text('mine')] })]);

    const { document, findings } = expand(doc, CATALOG, resolverOver(fetchedOf(outer, inner)));

    expect(findings).toEqual([]);
    expect(document.slots['inst:children' as SlotId].children).toEqual(['inst:in']);
    expect(document.slots['inst:s:in:children' as SlotId].children).toEqual([
      'inst:in:inner-title',
      'inst:in:inner-ph',
    ]);
    // inner's placeholder holds outer's fill (outer's own placeholder), which holds the template's fill.
    expect(document.slots['inst:in:s:inner-ph:fill' as SlotId].children).toEqual(['inst:op']);
    expect(document.slots['inst:s:op:fill' as SlotId].children).toEqual(['mine']);
  });

  it('reports a stencil that contains itself and does not inline it again', () => {
    const loop = workingCopy('loop', [text('l'), storedInstance('again', 'loop')]);
    const doc = buildDocument([storedInstance('inst', 'loop')]);

    const { findings } = expand(doc, CATALOG, resolverOver(fetchedOf(loop)));

    expect(findings).toEqual([
      expect.objectContaining({ instanceNodeId: 'inst:again', problem: 'RECURSION' }),
    ]);
  });

  it('lets a template fill hold the stencil it fills: the fill is the template content', () => {
    const box = workingCopy('box', [placeholder('ph', 'inside')]);
    const doc = buildDocument([
      storedInstance('outer', 'box', { inside: [storedInstance('inner', 'box')] }),
    ]);

    const { findings } = expand(doc, CATALOG, resolverOver(fetchedOf(box)));

    expect(findings).toEqual([]);
  });

  it('reports a recursion against the stencils the document is placed into (outerPath)', () => {
    const user = workingCopy('user', [storedInstance('n', 'host')]);
    const host = workingCopy('host', [text('h')]);
    const doc = buildDocument([storedInstance('inst', 'user')]);

    const { findings } = expand(doc, CATALOG, resolverOver(fetchedOf(user, host)), [
      { stencilKey: 'host', catalogKey: CATALOG },
    ]);

    expect(findings.map((f) => [f.ref.stencilKey, f.problem])).toEqual([['host', 'RECURSION']]);
  });

  it('reports nesting deeper than the limit', () => {
    const copies = Array.from({ length: MAX_STENCIL_NESTING_DEPTH + 1 }, (_, i) =>
      workingCopy(
        `s${i}`,
        i < MAX_STENCIL_NESTING_DEPTH ? [storedInstance(`n${i}`, `s${i + 1}`)] : [],
      ),
    );
    const doc = buildDocument([storedInstance('inst', 's0')]);

    const { findings } = expand(doc, CATALOG, resolverOver(fetchedOf(...copies)));

    expect(findings.map((f) => f.problem)).toEqual(['DEPTH_EXCEEDED']);
  });

  it('reports a fill for a placeholder the stencil does not have, and drops it', () => {
    const doc = buildDocument([storedInstance('inst', 'letter', { gone: [text('lost')] })]);

    const { document, findings } = expand(doc, CATALOG, resolverOver(fetchedOf(letter)));

    expect(findings).toEqual([
      expect.objectContaining({ problem: 'UNKNOWN_PLACEHOLDER', name: 'gone' }),
    ]);
    expect(document.nodes['lost' as NodeId]).toBeUndefined();
  });

  it('reports a stencil that does not exist and keeps the instance', () => {
    const doc = buildDocument([storedInstance('inst', 'missing', { body: [text('kept')] })]);

    const { document, findings } = expand(doc, CATALOG, resolverOver(new Map()));

    expect(findings.map((f) => f.problem)).toEqual(['STENCIL_NOT_FOUND']);
    expect(document.nodes['kept' as NodeId]).toBeDefined();
  });
});

describe('fetchStencilTree', () => {
  it('fetches a stencil and the stencils it uses, each once', async () => {
    const inner = workingCopy('inner', [text('i')]);
    const outer = workingCopy('outer', [
      storedInstance('a', 'inner'),
      storedInstance('b', 'inner'),
    ]);
    const getStencil = vi.fn(async (ref: { stencilKey: string }) =>
      ref.stencilKey === 'outer' ? outer : ref.stencilKey === 'inner' ? inner : null,
    );

    const fetched = await fetchStencilTree(
      { stencilKey: 'outer', catalogKey: CATALOG },
      getStencil,
    );

    expect([...fetched.keys()].toSorted()).toEqual([`${CATALOG}/inner`, `${CATALOG}/outer`]);
    expect(getStencil).toHaveBeenCalledTimes(2);
  });

  it('stops at a stencil that uses itself', async () => {
    const loop = workingCopy('loop', [storedInstance('again', 'loop')]);
    const getStencil = vi.fn(async () => loop);

    const fetched = await fetchStencilTree({ stencilKey: 'loop', catalogKey: CATALOG }, getStencil);

    expect(fetched.size).toBe(1);
    expect(getStencil).toHaveBeenCalledTimes(1);
  });
});

/** An expanded template: `outer` (content: inner instance whose fill offers outer's `ob`). */
function expandedTemplate() {
  const inner = workingCopy('inner', [placeholder('ip', 'slot')]);
  const outer = workingCopy('outer', [
    storedInstance('in', 'inner', { slot: [placeholder('op', 'ob')] }),
    container('box'),
  ]);
  const doc = buildDocument([storedInstance('inst', 'outer', { ob: [container('template-box')] })]);
  const expanded = expand(doc, CATALOG, resolverOver(fetchedOf(outer, inner))).document;
  return { doc: expanded, indexes: buildIndexes(expanded) };
}

describe('lexicalScopeAt', () => {
  const keys = (scope: ReturnType<typeof lexicalScopeAt>) =>
    scope.entries.map((e) => e.ref.stencilKey);

  it('places the template body in no stencil', () => {
    const { doc, indexes } = expandedTemplate();
    expect(keys(lexicalScopeAt(doc, 'root:children' as SlotId, indexes))).toEqual([]);
  });

  it("places a stencil's own content in that stencil", () => {
    const { doc, indexes } = expandedTemplate();
    expect(keys(lexicalScopeAt(doc, 'inst:s:box:children' as SlotId, indexes))).toEqual(['outer']);
  });

  it('places a fill written in the template outside the stencil it fills', () => {
    const { doc, indexes } = expandedTemplate();
    expect(keys(lexicalScopeAt(doc, 'template-box:children' as SlotId, indexes))).toEqual([]);
    expect(lexicalOwnerOf(doc, 'template-box' as NodeId, indexes)).toBeUndefined();
  });

  it('places a fill written in a stencil (of an inner stencil) in that stencil', () => {
    const { doc, indexes } = expandedTemplate();
    // inner's placeholder fill is written by outer: it is outer's content, not inner's.
    expect(keys(lexicalScopeAt(doc, 'inst:in:s:ip:fill' as SlotId, indexes))).toEqual(['outer']);
    // outer's placeholder offered there belongs to outer.
    expect(lexicalOwnerOf(doc, 'inst:op' as NodeId, indexes)).toBe('inst');
  });
});

describe('checkStencilNesting (as the contract validator, lexically)', () => {
  const scopeOf = (spec: Spec[], slot: string) => {
    const doc = buildDocument(spec);
    return { doc, scope: lexicalScopeAt(doc, slot as SlotId, buildIndexes(doc)) };
  };

  it('refuses an instance of a stencil the place is already the content of', () => {
    // Expanded `box` instance; a block holding another `box` would go into box's own content.
    const { doc, scope } = scopeOf(
      [
        {
          id: 'inst',
          type: 'stencil',
          props: { stencilKey: 'box', catalogKey: CATALOG },
          slots: { children: [container('c'), storedInstance('moving', 'box')] },
        },
      ],
      'c:children',
    );

    const problem = checkStencilNesting(doc.nodes as never, doc.slots as never, 'moving', scope);

    expect(problem).toEqual(
      expect.objectContaining({ problem: 'RECURSION', instanceNodeId: 'moving' }),
    );
  });

  it('allows the same instance in the template body', () => {
    const { doc, scope } = scopeOf([container('c'), storedInstance('moving', 'box')], 'c:children');
    expect(checkStencilNesting(doc.nodes as never, doc.slots as never, 'moving', scope)).toBeNull();
  });
});
