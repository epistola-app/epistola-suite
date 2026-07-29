// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resetCounter } from '../../engine/test-helpers.js';
import { hydrateStencilDrafts } from './draft-hydration.js';
import {
  createMockCallbacks,
  createSampleContent,
  insertStencil,
  setupEngine,
} from './stencil-test-helpers.js';

beforeEach(() => {
  resetCounter();
});

describe('hydrateStencilDrafts', () => {
  it('loads an exact draft once for duplicate references', async () => {
    const getStencilVersion = vi.fn().mockResolvedValue({
      ref: { stencilId: 'header', catalogKey: 'cat' },
      stencilName: 'Header',
      version: 3,
      status: 'draft',
      content: createSampleContent(),
    });
    const callbacks = createMockCallbacks({ getStencilVersion });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const first = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      catalogKey: 'cat',
      version: 1,
      draftVersion: 3,
    });
    const second = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      catalogKey: 'cat',
      version: 1,
      draftVersion: 3,
    });

    const result = await hydrateStencilDrafts(engine.doc, registry, callbacks);

    expect(getStencilVersion).toHaveBeenCalledOnce();
    expect(getStencilVersion).toHaveBeenCalledWith({ stencilId: 'header', catalogKey: 'cat' }, 3);
    expect(result.document.nodes[first].props?.draftVersion).toBe(3);
    expect(result.document.nodes[second].props?.draftVersion).toBe(3);
    expect(result.recovery).toEqual({});
  });

  it('promotes a draft reference when the exact version is now published', async () => {
    const callbacks = createMockCallbacks({
      getStencilVersion: vi.fn().mockResolvedValue({
        ref: { stencilId: 'header', catalogKey: 'cat' },
        stencilName: 'Header',
        version: 3,
        status: 'published',
        content: createSampleContent(),
      }),
    });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencil = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      catalogKey: 'cat',
      version: 1,
      draftVersion: 3,
    });

    const result = await hydrateStencilDrafts(engine.doc, registry, callbacks);

    expect(result.document.nodes[stencil].props?.version).toBe(3);
    expect(result.document.nodes[stencil].props?.draftVersion).toBeUndefined();
  });

  it('preserves embedded content and exposes recovery when the draft is missing', async () => {
    const callbacks = createMockCallbacks({
      getStencilVersion: vi.fn().mockResolvedValue(null),
    });
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencil = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      catalogKey: 'cat',
      version: 1,
      draftVersion: 3,
    });
    const before = structuredClone(engine.doc);

    const result = await hydrateStencilDrafts(engine.doc, registry, callbacks);

    expect(result.document).toEqual(before);
    expect(result.recovery[stencil]).toEqual(expect.objectContaining({ reason: 'missing' }));
  });
});
