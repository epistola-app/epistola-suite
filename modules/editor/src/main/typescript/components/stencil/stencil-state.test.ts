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
  it('publish as stencil: sets stencilId, version, isDraft=false', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId);

    // Simulate publish action
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: 'new-stencil', version: 1, isDraft: false },
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBe('new-stencil');
    expect(node.props?.version).toBe(1);
    expect(node.props?.isDraft).toBe(false);
  });

  it('start editing: sets isDraft=true', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, isDraft: true },
    });

    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(true);
  });

  it('publish draft: sets version to new, isDraft=false', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: true,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, version: 2, isDraft: false },
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(2);
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });

  it('discard: sets isDraft=false, keeps original version', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: true,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, isDraft: false },
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(1);
    expect(engine.doc.nodes[stencilId].props?.isDraft).toBe(false);
  });

  it('upgrade: sets version to latest', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { ...engine.doc.nodes[stencilId].props, version: 3 },
    });

    expect(engine.doc.nodes[stencilId].props?.version).toBe(3);
  });

  it('detach: clears stencilId, version, isDraft', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: stencilId,
      props: { stencilId: null, version: null, isDraft: false },
    });

    const node = engine.doc.nodes[stencilId];
    expect(node.props?.stencilId).toBeNull();
    expect(node.props?.version).toBeNull();
    expect(node.props?.isDraft).toBe(false);
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
      isDraft: false,
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
      isDraft: true,
    });

    const def = registry.get('stencil');
    const node = engine.doc.nodes[stencilId];
    const label = def!.getLabel!(node, engine);
    expect(label).toBe('header');
  });

  it('shows label without upgrade suffix when newer version available', () => {
    const callbacks = createMockCallbacks();
    const { engine, registry, rootSlotId } = setupEngine(callbacks);
    const stencilId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
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
      isDraft: false,
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
      isDraft: false,
      catalogKey: 'my-catalog',
    });

    expect(engine.doc.nodes[nodeId].props?.catalogKey).toBe('my-catalog');
  });

  it('stencil node without catalogKey defaults to null', () => {
    const { engine, registry, rootSlotId } = setupEngine();
    const nodeId = insertStencil(engine, registry, rootSlotId, {
      stencilId: 'header',
      version: 1,
      isDraft: false,
    });

    expect(engine.doc.nodes[nodeId].props?.catalogKey).toBeNull();
  });
});
