/**
 * Building blocks for interactive tour steps (D1). A step's `advance` hook wires a
 * listener when the step is shown and calls `advance()` when the user completes the
 * action, moving the tour on — while Next/Skip stay enabled (guide, never gate).
 *
 * Two common cases are covered: an editor-engine event (validate the *outcome*, so
 * any path — click or drag — that reaches it advances) and a DOM click inside the
 * host. Kept driver-free (type-only engine import) so it adds nothing to the
 * always-loaded bundle beyond this already-lazy chunk.
 */
import type { EngineEvents, EventEmitter } from '../../engine/events.js';
import type { DocumentIndexes } from '../../engine/indexes.js';
import type { TourStep } from './registry.js';

type Advance = NonNullable<TourStep['advance']>;

interface EngineLike {
  events: EventEmitter<EngineEvents>;
  indexes: DocumentIndexes;
}

/** Total node count — every node (root included) appears once in `depthByNodeId`. */
function nodeCount(indexes: DocumentIndexes): number {
  return indexes.depthByNodeId.size;
}

/** The editor engine for a host `<epistola-editor>`, if initialised. */
export function getEngine(host: HTMLElement): EngineLike | undefined {
  return (host as HTMLElement & { engine?: EngineLike }).engine;
}

/** Whether the document has at least one block on the canvas. */
export function hasAnyBlock(host: HTMLElement): boolean {
  return host.querySelector('.canvas-block[data-node-id]') !== null;
}

/**
 * Advance when an engine event fires, optionally only when `when` holds. Prefer
 * this over a raw click — it validates the real result ("a block was added",
 * "something is selected"), so the user reaching it any way advances.
 */
export function advanceOnEvent<K extends keyof EngineEvents>(
  event: K,
  when?: (host: HTMLElement, data: EngineEvents[K]) => boolean,
): Advance {
  return (host, advance) => {
    const engine = getEngine(host);
    if (!engine) return () => {};
    return engine.events.on(event, (data) => {
      if (!when || when(host, data)) advance();
    });
  };
}

/**
 * Advance once a block is added anywhere in the document. Captures the node count
 * when the step is shown and advances when it grows — so it fires for the user's
 * insertion (click or drag), not any earlier one, and reads the engine's own count
 * rather than racing the canvas re-render.
 */
export function advanceOnBlockAdded(): Advance {
  return (host, advance) => {
    const engine = getEngine(host);
    if (!engine) return () => {};
    const baseline = nodeCount(engine.indexes);
    return engine.events.on('doc:change', (data) => {
      if (nodeCount(data.indexes) > baseline) advance();
    });
  };
}

/** Advance when the user clicks an element matching `selector` inside the host. */
export function advanceOnClick(selector: string): Advance {
  return (host, advance) => {
    const handler = (e: Event): void => {
      const target = e.target;
      if (target instanceof Element && target.closest(selector)) advance();
    };
    host.addEventListener('click', handler, true);
    return () => host.removeEventListener('click', handler, true);
  };
}
