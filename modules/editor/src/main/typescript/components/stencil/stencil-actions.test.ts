// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import type { NodeId, SlotId, TemplateDocument } from '../../types/index.js';
import { isSlotLocked } from '../../engine/locks.js';
import * as actions from './stencil-actions.js';
import { expand, resolverOver } from './instances.js';
import { stencilIdentity } from './node-types.js';
import {
  buildDocument,
  CATALOG,
  childrenOf,
  container,
  createMockCallbacks,
  insertText,
  placeholder,
  setupEngine,
  storedInstance,
  text,
  textOf,
  workingCopy,
} from './stencil-test-helpers.js';
import type { StencilWorkingCopy } from './types.js';

const letter = (header = 'Header') =>
  workingCopy('letter', [
    text('hdr', header),
    placeholder('ph', 'body', { default: [text('d', 'Default body')] }),
    container('box'),
  ]);

/** A template with expanded instances of `letter` (`inst`, and `other` when asked), `inst` filled by the template. */
function setup(copies: StencilWorkingCopy[] = [letter()], withOther = false) {
  const callbacks = createMockCallbacks(copies);
  const stored = buildDocument([
    storedInstance('inst', 'letter', { body: [text('mine', 'Mine')] }),
    ...(withOther ? [storedInstance('other', 'letter')] : []),
  ]);
  const fetched = new Map(copies.map((c) => [stencilIdentity(c.ref), c]));
  const { document } = expand(stored, CATALOG, resolverOver(fetched));
  const env = setupEngine(callbacks, document);
  const ctx: actions.StencilActionContext = {
    engine: env.engine,
    callbacks,
    editing: env.editing,
    stencilNodeId: 'inst' as NodeId,
  };
  return { ...env, callbacks, ctx };
}

function savedContent(callbacks: ReturnType<typeof setup>['callbacks']): TemplateDocument {
  const call = (callbacks.saveStencil as unknown as { mock: { calls: unknown[][] } }).mock.calls[0];
  return (call[1] as { content: TemplateDocument }).content;
}

describe('startEditing', () => {
  it('shows the latest working copy, keeps the template fill, and unlocks the content', async () => {
    const { ctx, callbacks, engine, registry } = setup();
    callbacks.copies.set(`${CATALOG}/letter`, letter('Newer header'));

    await actions.startEditing(ctx);

    expect(textOf(engine, 'inst:hdr')).toBe('Newer header');
    expect(childrenOf(engine, 'inst:ph', 'fill')).toEqual(['mine']);
    expect(ctx.editing.isEditing('inst')).toBe(true);
    expect(
      isSlotLocked(engine.doc, 'inst:s:box:children' as SlotId, engine.indexes, registry),
    ).toBe(false);
  });

  it('refuses a stencil of a read-only catalog', async () => {
    const { ctx } = setup([workingCopy('letter', [text('hdr')], { editable: false })]);

    await expect(actions.startEditing(ctx)).rejects.toThrow('read-only catalog');
    expect(ctx.editing.isEditing('inst')).toBe(false);
  });
});

describe('saveStencil', () => {
  it("saves the instance's content without the template's fills, in stored form", async () => {
    const { ctx, callbacks, engine, registry } = setup();
    await actions.startEditing(ctx);
    insertText(engine, registry, 'inst:s:box:children' as SlotId, 'Added while editing');

    await actions.saveStencil(ctx);

    const content = savedContent(callbacks);
    const texts = Object.keys(content.nodes)
      .map((id) => textOf(content, id))
      .filter(Boolean);
    expect(texts).toContain('Added while editing');
    expect(texts).toContain('Default body');
    // The template's fill is not part of the stencil.
    expect(texts).not.toContain('Mine');
    expect(content.slots['ph:fill' as SlotId].children).toEqual([]);
    // The stencil keeps its own IDs, not the instance-prefixed ones it was shown with.
    expect(Object.keys(content.nodes)).toEqual(expect.arrayContaining(['hdr', 'ph', 'd', 'box']));
    expect(
      (callbacks.saveStencil as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][1],
    ).toMatchObject({ parameterSchema: null });
  });

  it('saves a stencil nested in the content by reference, with the fill the stencil gives it', async () => {
    const page = workingCopy('page', [
      storedInstance('frame-inst', 'frame', { inside: [text('page-fill', 'Page fill')] }),
    ]);
    const frame = workingCopy('frame', [text('frame-text', 'Frame'), placeholder('fp', 'inside')]);
    const callbacks = createMockCallbacks([page, frame]);
    const fetched = new Map([page, frame].map((c) => [stencilIdentity(c.ref), c]));
    const { document } = expand(
      buildDocument([storedInstance('inst', 'page')]),
      CATALOG,
      resolverOver(fetched),
    );
    const env = setupEngine(callbacks, document);
    const ctx = {
      engine: env.engine,
      callbacks,
      editing: env.editing,
      stencilNodeId: 'inst' as NodeId,
    };
    await actions.startEditing(ctx);

    await actions.saveStencil(ctx);

    const content = savedContent(callbacks);
    // `frame`'s content is not copied into `page`; its fill (page's own content) is kept.
    expect(Object.values(content.nodes).some((n) => textOf(content, n.id) === 'Frame')).toBe(false);
    expect(content.nodes['frame-inst' as NodeId].props).toEqual({
      stencilKey: 'frame',
      catalogKey: CATALOG,
    });
    expect(content.slots['frame-inst:fill:inside:fill' as SlotId].children).toEqual(['page-fill']);
  });

  it('sends the parameters defined on the instance', async () => {
    const { ctx, callbacks, engine } = setup();
    await actions.startEditing(ctx);
    const schema = { type: 'object', properties: { who: { type: 'string' } } };
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: 'inst' as NodeId,
      props: { ...engine.doc.nodes['inst' as NodeId].props, parameterSchemaSnapshot: schema },
    });

    await actions.saveStencil(ctx);

    expect(
      (callbacks.saveStencil as unknown as { mock: { calls: unknown[][] } }).mock.calls[0][1],
    ).toMatchObject({ parameterSchema: schema });
  });

  it('refreshes every other instance of the stencil in the document, keeping their fills', async () => {
    const { ctx, callbacks, engine, registry } = setup([letter()], true);
    await actions.startEditing(ctx);
    insertText(engine, registry, 'inst:s:box:children' as SlotId, 'Shared change');
    callbacks.saveStencil = async (_ref, update) => {
      callbacks.copies.set(`${CATALOG}/letter`, { ...letter(), content: update.content });
    };

    await actions.saveStencil(ctx);

    const otherTexts = childrenOf(engine, 'other:box', 'children').map((id) => textOf(engine, id));
    expect(otherTexts).toEqual(['Shared change']);
    expect(childrenOf(engine, 'inst:ph', 'fill')).toEqual(['mine']);
  });
});

describe('saveAndMarkReady', () => {
  it('saves, marks the stencil ready and stops editing', async () => {
    const { ctx, callbacks } = setup();
    await actions.startEditing(ctx);

    await actions.saveAndMarkReady(ctx);

    expect(callbacks.saveStencil).toHaveBeenCalled();
    expect(callbacks.markStencilReady).toHaveBeenCalledWith({
      stencilKey: 'letter',
      catalogKey: CATALOG,
    });
    expect(ctx.editing.isEditing('inst')).toBe(false);
  });

  it('refuses while a required parameter is unbound, before saving', async () => {
    const { ctx, callbacks, engine } = setup();
    await actions.startEditing(ctx);
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: 'inst' as NodeId,
      props: {
        ...engine.doc.nodes['inst' as NodeId].props,
        parameterSchemaSnapshot: {
          type: 'object',
          properties: { who: { type: 'string' } },
          required: ['who'],
        },
      },
    });

    await expect(actions.saveAndMarkReady(ctx)).rejects.toThrow('who');
    expect(callbacks.saveStencil).not.toHaveBeenCalled();
  });

  it('keeps editing when the stencil cannot be marked ready', async () => {
    const { ctx, callbacks } = setup();
    callbacks.markStencilReady = async () => {
      throw new Error('Stencil uses a stencil that does not exist');
    };
    await actions.startEditing(ctx);

    await expect(actions.saveAndMarkReady(ctx)).rejects.toThrow('does not exist');
    expect(ctx.editing.isEditing('inst')).toBe(true);
  });
});

describe('stopEditing', () => {
  it('drops unsaved changes, keeps the template fill, and locks the content', async () => {
    const { ctx, engine, registry } = setup();
    await actions.startEditing(ctx);
    insertText(engine, registry, 'inst:s:box:children' as SlotId, 'Not saved');

    await actions.stopEditing(ctx);

    expect(childrenOf(engine, 'inst:box', 'children')).toEqual([]);
    expect(childrenOf(engine, 'inst:ph', 'fill')).toEqual(['mine']);
    expect(
      isSlotLocked(engine.doc, 'inst:s:box:children' as SlotId, engine.indexes, registry),
    ).toBe(true);
  });
});

describe('detach', () => {
  it('turns the instance into a container and stops editing it', async () => {
    const { ctx, engine } = setup();
    await actions.startEditing(ctx);

    actions.detach(ctx);

    expect(engine.doc.nodes['inst' as NodeId].type).toBe('container');
    expect(ctx.editing.isEditing('inst')).toBe(false);
  });
});
