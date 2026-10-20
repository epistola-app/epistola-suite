// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import type { NodeId, SlotId } from '../../types/index.js';
import { isSlotLocked } from '../../engine/locks.js';
import { canDropHere } from '../../dnd/drop-logic.js';
import { placeholderContext } from './ancestry.js';
import { prepareStencilInsert } from './stencil-registration.js';
import { expand, lexicalScopeAt, resolverOver } from './instances.js';
import { stencilIdentity } from './node-types.js';
import {
  buildDocument,
  CATALOG,
  childrenOf,
  container,
  createMockCallbacks,
  insertInstance,
  placeholder,
  setupEngine,
  storedInstance,
  text,
  textOf,
  workingCopy,
} from './stencil-test-helpers.js';
import type { StencilWorkingCopy } from './types.js';

const letter = () =>
  workingCopy('letter', [
    text('hdr', 'Header'),
    placeholder('ph', 'body', { default: [text('d', 'Default body')] }),
    container('box'),
  ]);

/** A template already holding an expanded instance `inst` of [copies][0], as the server loads it. */
function loadedTemplate(copies: StencilWorkingCopy[], fills: Record<string, never[]> = {}) {
  const callbacks = createMockCallbacks(copies);
  const stored = buildDocument([storedInstance('inst', copies[0].ref.stencilKey, fills)]);
  const fetched = new Map(copies.map((c) => [stencilIdentity(c.ref), c]));
  const { document } = expand(stored, CATALOG, resolverOver(fetched));
  return { callbacks, ...setupEngine(callbacks, document) };
}

describe('Stencil component registration (wire v8)', () => {
  it('names the stencil by stencilKey and pins no version', () => {
    const { registry } = setupEngine(null);
    const def = registry.get('stencil')!;
    expect(def.defaultProps).toEqual({ stencilKey: null, catalogKey: null });
    expect(def.slots.map((s) => s.name)).toEqual(['children']);
  });

  it('allows a stencil inside a stencil', () => {
    const { registry } = setupEngine(null);
    expect(registry.canContain('stencil', 'stencil')).toBe(true);
  });

  it('labels an instance by its stencil key', () => {
    const { engine, registry } = loadedTemplate([letter()]);
    const node = engine.doc.nodes['inst' as NodeId];
    expect(registry.get('stencil')!.getLabel!(node, engine)).toBe('letter');
  });
});

describe('Inserting an instance', () => {
  it("inlines the stencil's working copy under IDs derived from the instance, as the server expands it", async () => {
    const callbacks = createMockCallbacks([letter()]);
    const { engine, rootSlotId } = setupEngine(callbacks);

    const id = await insertInstance(engine, callbacks, rootSlotId, 'letter');

    const node = engine.doc.nodes[id];
    expect(node.props).toEqual({ stencilKey: 'letter', catalogKey: CATALOG });
    expect(node.slots).toEqual([`${id}:children`]);
    expect(childrenOf(engine, id, 'children')).toEqual([`${id}:hdr`, `${id}:ph`, `${id}:box`]);
    expect(textOf(engine, `${id}:d`)).toBe('Default body');
  });

  it("keeps the stencil's parameter schema on the instance and the picked bindings", async () => {
    const schema = { type: 'object', properties: { who: { type: 'string' } } } as const;
    const callbacks = createMockCallbacks([
      workingCopy('greeting', [text('g')], { parameterSchema: schema }),
    ]);
    const { engine, rootSlotId } = setupEngine(callbacks);

    const id = await insertInstance(engine, callbacks, rootSlotId, 'greeting', {
      parameterBindings: { who: 'customer.name' },
    });

    expect(engine.doc.nodes[id].props).toEqual({
      stencilKey: 'greeting',
      catalogKey: CATALOG,
      parameterBindings: { who: 'customer.name' },
      parameterSchemaSnapshot: schema,
    });
  });

  it('expands a stencil the inserted stencil uses', async () => {
    const callbacks = createMockCallbacks([
      workingCopy('outer', [storedInstance('in', 'inner')]),
      workingCopy('inner', [text('inner-text', 'Inner')]),
    ]);
    const { engine, rootSlotId } = setupEngine(callbacks);

    const id = await insertInstance(engine, callbacks, rootSlotId, 'outer');

    expect(textOf(engine, `${id}:in:inner-text`)).toBe('Inner');
  });
});

describe('Locking', () => {
  it("locks the stencil's content where it is used, but not its placeholders' fills", () => {
    const { engine, registry } = loadedTemplate([letter()]);
    expect(isSlotLocked(engine.doc, 'inst:children' as SlotId, engine.indexes, registry)).toBe(
      true,
    );
    expect(
      isSlotLocked(engine.doc, 'inst:s:box:children' as SlotId, engine.indexes, registry),
    ).toBe(true);
    expect(isSlotLocked(engine.doc, 'inst:s:ph:fill' as SlotId, engine.indexes, registry)).toBe(
      false,
    );
  });

  it('unlocks the content while the stencil is being edited', () => {
    const { engine, registry, editing } = loadedTemplate([letter()]);
    editing.start('inst', engine);
    expect(
      isSlotLocked(engine.doc, 'inst:s:box:children' as SlotId, engine.indexes, registry),
    ).toBe(false);
  });

  // Regression: the server expands instances without a version, so the
  // pre-2.0 "published = has a version" test showed every placeholder as the
  // stencil author's default instead of the template's fill.
  it('shows a placeholder of a used stencil as the template fill, and as its default while editing', () => {
    const { engine, registry, editing } = loadedTemplate([letter()]);
    expect(placeholderContext(engine.doc, 'inst:ph' as NodeId, engine.indexes, registry)).toBe(
      'template-fill',
    );
    editing.start('inst', engine);
    expect(placeholderContext(engine.doc, 'inst:ph' as NodeId, engine.indexes, registry)).toBe(
      'stencil-author',
    );
  });

  it('treats a placeholder an outer stencil offers inside an inner fill as the outer one', () => {
    const { engine, registry, editing } = loadedTemplate([
      workingCopy('outer', [storedInstance('in', 'inner', { slot: [placeholder('op', 'ob')] })]),
      workingCopy('inner', [placeholder('ip', 'slot')]),
    ]);
    editing.start('inst', engine);
    // Physically inside `inner` (not being edited), lexically outer's own placeholder.
    expect(placeholderContext(engine.doc, 'inst:op' as NodeId, engine.indexes, registry)).toBe(
      'stencil-author',
    );
  });
});

describe('A stencil cannot contain itself (lexically)', () => {
  it('refuses the stencil being edited inside its own content', async () => {
    const { engine, callbacks, editing } = loadedTemplate([letter()]);
    editing.start('inst', engine);
    const scope = lexicalScopeAt(
      engine.doc,
      'inst:s:box:children' as SlotId,
      engine.indexes,
      CATALOG,
    );

    const outcome = await prepareStencilInsert(
      callbacks,
      { stencilKey: 'letter', catalogKey: CATALOG },
      scope,
    );

    expect(outcome).toEqual({
      ok: false,
      message: expect.stringContaining('cannot contain itself'),
    });
  });

  it('refuses a stencil that uses the stencil being edited, further down', async () => {
    const { engine, callbacks, editing } = loadedTemplate([
      letter(),
      workingCopy('wrapper', [storedInstance('w', 'middle')]),
      workingCopy('middle', [storedInstance('m', 'letter')]),
    ]);
    editing.start('inst', engine);
    const scope = lexicalScopeAt(
      engine.doc,
      'inst:s:box:children' as SlotId,
      engine.indexes,
      CATALOG,
    );

    const outcome = await prepareStencilInsert(
      callbacks,
      { stencilKey: 'wrapper', catalogKey: CATALOG },
      scope,
    );

    expect(outcome).toEqual({
      ok: false,
      message: expect.stringContaining("it uses 'letter'"),
    });
  });

  it("refuses the stencil being edited in the fill of a stencil inside it (that fill is the stencil's own content)", async () => {
    const { engine, callbacks, editing } = loadedTemplate([
      workingCopy('page', [storedInstance('frame-inst', 'frame')]),
      workingCopy('frame', [placeholder('fp', 'inside')]),
    ]);
    editing.start('inst', engine);
    const fillSlot = 'inst:frame-inst:s:fp:fill' as SlotId;
    expect(engine.doc.slots[fillSlot]).toBeDefined();
    const scope = lexicalScopeAt(engine.doc, fillSlot, engine.indexes, CATALOG);

    const outcome = await prepareStencilInsert(
      callbacks,
      { stencilKey: 'page', catalogKey: CATALOG },
      scope,
    );

    expect(outcome.ok).toBe(false);
  });

  it('allows a template fill to hold the stencil it fills: the fill is the template content', async () => {
    const { engine, callbacks } = loadedTemplate([letter()]);

    const id = await insertInstance(engine, callbacks, 'inst:s:ph:fill' as SlotId, 'letter');

    expect(childrenOf(engine, 'inst:ph', 'fill')).toEqual([id]);
  });

  it('refuses moving an instance into the content of the stencil it is an instance of', async () => {
    const { engine, callbacks, editing, registry, rootSlotId } = loadedTemplate([letter()]);
    const second = await insertInstance(engine, callbacks, rootSlotId, 'letter');
    editing.start('inst', engine);
    const moveInto = (slot: string) =>
      canDropHere(
        { source: 'block', nodeId: second, blockType: 'stencil' },
        slot as SlotId,
        engine.doc,
        engine.indexes,
        registry,
      );

    expect(moveInto('inst:s:box:children')).toBe(false);
    // Into the template's own content, or the template's fill of the instance, it may go.
    expect(moveInto('root:children')).toBe(true);
    expect(moveInto('inst:s:ph:fill')).toBe(true);
  });
});

describe('The picker, inserting into the content of the stencil being edited', () => {
  afterEach(() => document.querySelectorAll('dialog').forEach((d) => d.remove()));

  it('disables the stencil being edited and says why', async () => {
    const { engine, callbacks, editing, registry } = loadedTemplate([
      letter(),
      workingCopy('other', [text('o')]),
    ]);
    editing.start('inst', engine);

    void registry.get('stencil')!.onBeforeInsert!(engine, {
      targetSlotId: 'inst:s:box:children' as SlotId,
    });
    await new Promise((r) => setTimeout(r, 0));

    const cards = [...document.querySelectorAll<HTMLElement>('.stencil-picker-card')];
    const letterCard = cards.find((c) => c.dataset.stencilKey === 'letter')!;
    const otherCard = cards.find((c) => c.dataset.stencilKey === 'other')!;
    expect(letterCard.classList.contains('stencil-picker-card--disabled')).toBe(true);
    expect(letterCard.textContent).toContain('would contain itself');
    expect(otherCard.classList.contains('stencil-picker-card--disabled')).toBe(false);
  });

  it('shows the refusal for a stencil that uses it further down, and inserts nothing', async () => {
    const { engine, callbacks, editing, registry } = loadedTemplate([
      letter(),
      workingCopy('uses-letter', [storedInstance('u', 'letter')]),
    ]);
    editing.start('inst', engine);

    const result = registry.get('stencil')!.onBeforeInsert!(engine, {
      targetSlotId: 'inst:s:box:children' as SlotId,
    });
    await new Promise((r) => setTimeout(r, 0));
    document
      .querySelector<HTMLElement>('.stencil-picker-card[data-stencil-key="uses-letter"]')!
      .click();
    document.querySelector<HTMLButtonElement>('[data-testid="stencil-picker-insert"]')!.click();
    await new Promise((r) => setTimeout(r, 0));

    const error = document.querySelector<HTMLElement>('[data-testid="stencil-picker-error"]')!;
    expect(error.style.display).not.toBe('none');
    expect(error.textContent).toContain("it uses 'letter'");
    document.querySelector<HTMLElement>('.stencil-picker-btn.cancel')!.click();
    await expect(result).resolves.toBeNull();
    expect(callbacks.getStencil).toHaveBeenCalled();
  });
});
