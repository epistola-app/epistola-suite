// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EventEmitter, type EngineEvents } from '../../engine/events.js';
import { advanceOnClick, advanceOnEvent, getEngine, hasAnyBlock } from './signals.js';

/** A host `<div>` with a fake engine exposing a real EventEmitter. */
function makeHost(): { host: HTMLElement; events: EventEmitter<EngineEvents> } {
  const events = new EventEmitter<EngineEvents>();
  const host = document.createElement('div');
  Object.assign(host, { engine: { events } });
  document.body.appendChild(host);
  return { host, events };
}

afterEach(() => {
  document.body.innerHTML = '';
});

describe('getEngine', () => {
  it('returns the engine when the host exposes one, undefined otherwise', () => {
    const { host } = makeHost();
    expect(getEngine(host)).toBeDefined();
    expect(getEngine(document.createElement('div'))).toBeUndefined();
  });
});

describe('hasAnyBlock', () => {
  it('is true only when a canvas block is present', () => {
    const host = document.createElement('div');
    expect(hasAnyBlock(host)).toBe(false);
    const block = document.createElement('div');
    block.className = 'canvas-block';
    block.setAttribute('data-node-id', 'n1');
    host.appendChild(block);
    expect(hasAnyBlock(host)).toBe(true);
  });
});

describe('advanceOnEvent', () => {
  let ctx: ReturnType<typeof makeHost>;
  beforeEach(() => (ctx = makeHost()));

  it('advances on the event, and stops after cleanup', () => {
    const advance = vi.fn();
    const cleanup = advanceOnEvent('example:change')(ctx.host, advance);

    ctx.events.emit('example:change', { index: 0, example: undefined });
    expect(advance).toHaveBeenCalledTimes(1);

    cleanup();
    ctx.events.emit('example:change', { index: 1, example: undefined });
    expect(advance).toHaveBeenCalledTimes(1);
  });

  it('honours the `when` predicate — only the matching event advances', () => {
    const advance = vi.fn();
    advanceOnEvent('example:change', (_host, data) => data.index === 2)(ctx.host, advance);

    ctx.events.emit('example:change', { index: 1, example: undefined });
    expect(advance).not.toHaveBeenCalled();
    ctx.events.emit('example:change', { index: 2, example: undefined });
    expect(advance).toHaveBeenCalledTimes(1);
  });

  it('is a no-op (never throws) when the host has no engine', () => {
    const advance = vi.fn();
    const cleanup = advanceOnEvent('doc:change')(document.createElement('div'), advance);
    expect(() => cleanup()).not.toThrow();
    expect(advance).not.toHaveBeenCalled();
  });
});

describe('advanceOnClick', () => {
  it('advances on a click matching the selector, and stops after cleanup', () => {
    const host = document.createElement('div');
    const btn = document.createElement('button');
    btn.setAttribute('data-testid', 'palette-item-text');
    host.appendChild(btn);
    document.body.appendChild(host);

    const advance = vi.fn();
    const cleanup = advanceOnClick('[data-testid="palette-item-text"]')(host, advance);

    btn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(advance).toHaveBeenCalledTimes(1);

    cleanup();
    btn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(advance).toHaveBeenCalledTimes(1);
  });

  it('ignores clicks that do not match the selector', () => {
    const host = document.createElement('div');
    const other = document.createElement('div');
    host.appendChild(other);
    document.body.appendChild(host);

    const advance = vi.fn();
    advanceOnClick('[data-testid="palette-item-text"]')(host, advance);
    other.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(advance).not.toHaveBeenCalled();
  });
});
