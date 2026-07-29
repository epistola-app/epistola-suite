// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Stencil props state-transition tests + getLabel + catalogKey propagation.
 *
 * These exercise the dispatch-level prop changes the inspector triggers,
 * not the inspector itself. Useful as regression sentinels when the
 * stencil's prop shape evolves.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { resetCounter } from '../../engine/test-helpers.js';
import { setupEngine, insertStencil, createMockCallbacks } from './stencil-test-helpers.js';

beforeEach(() => {
  resetCounter();
});

describe('Stencil props state transitions', () => {
  it('publish as stencil: sets stencilId and version without draft provenance', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    // Simulate publish action
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: 'new-stencil', version: 1 },
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBe('new-stencil');
    expect(node.props?.version).toBe(1);
    expect(node.props?.draftVersion).toBeUndefined();
  });

  it('start editing: records the exact draft version', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, draftVersion: 2 },
    });

    expect(engine.doc.nodes[stencilId].props?.draftVersion).toBe(2);
  });

  it('publish draft: sets the published version and clears draft provenance', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      draftVersion: 2,
    });

    const { draftVersion: _, ...publishedProps } = engine.doc.nodes[stencilId].props ?? {};
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...publishedProps, version: 2 },
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(2);
    expect(engine.doc.nodes[stencilId].props?.draftVersion).toBeUndefined();
  });

  it('discard: clears draft provenance and keeps the original version', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      draftVersion: 2,
    });

    const { draftVersion: _, ...publishedProps } = engine.doc.nodes[stencilId].props ?? {};
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: publishedProps,
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(1);
    expect(engine.doc.nodes[stencilId].props?.draftVersion).toBeUndefined();
  });

  it('upgrade: sets version to latest', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, version: 3 },
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(3);
  });

  it('detach: clears stencil provenance', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: null, version: null },
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBeNull();
    expect(node.props?.version).toBeNull();
    expect(node.props?.draftVersion).toBeUndefined();
  });
});

describe('getLabel', () => {
  it('returns "Stencil" for unlinked node', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId);

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('Stencil');
  });

  it('returns name + version for locked stencil', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 2,
    });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('header v2');
  });

  it('returns name for draft stencil', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      draftVersion: 2,
    });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('header draft v2');
  });

  it('shows label without upgrade suffix when newer version available', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
    });

    // Set upgrade state
    engine.setComponentState('stencil:upgrades', { header: 3 });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('header v1');
  });

  it('shows label without upgrade indicator when on latest version', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 3,
    });

    engine.setComponentState('stencil:upgrades', { header: 3 });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('header v3');
  });
});

describe('catalogKey propagation', () => {
  it('stencil node preserves catalogKey in props', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,

      catalogKey: 'my-catalog',
    });

    expect(engine.doc.nodes[nodeId].props?.catalogKey).toBe('my-catalog');
  });

  it('stencil node without catalogKey defaults to null', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
    });

    expect(engine.doc.nodes[nodeId].props?.catalogKey).toBeNull();
  });
});
