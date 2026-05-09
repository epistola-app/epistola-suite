/**
 * Async cache for stencil parameter binding evaluations.
 *
 * The parameter scope provider runs synchronously, but JSONata is async.
 * To get accurate canvas previews for non-trivial bindings (literals,
 * concatenation, $functions, conditionals), we kick off real async
 * evaluation, cache the result, and refresh expression chips when the
 * value arrives. This mirrors the pattern ExpressionNodeView already uses
 * for inline `{{...}}` chips.
 *
 * Cache key: (stencilNodeId, alias, paramName, expression). The same
 * binding on the same node is evaluated at most once. Changing the
 * expression naturally produces a new key — old entries remain in the cache
 * but are never read again until the cache is cleared.
 *
 * Invalidation: the cache is wiped on example:change (different data) and
 * on structural doc:change events (the scope graph may have shifted).
 * See [wireParameterCache].
 */

import { evaluateExpression } from './resolve-expression.js';
import { ExpressionNodeView } from '../prosemirror/ExpressionNodeView.js';
import type { EditorEngine } from './EditorEngine.js';

const cache = new Map<string, unknown>();
const pending = new Set<string>();

const makeKey = (nodeId: string, alias: string, name: string, expr: string): string =>
  `${nodeId}|${alias}|${name}|${expr}`;

export type CachedParamValue = { found: true; value: unknown } | { found: false };

/** Synchronous cache read. Used by the scope provider. */
export function getCachedParamValue(
  nodeId: string,
  alias: string,
  name: string,
  expr: string,
): CachedParamValue {
  const key = makeKey(nodeId, alias, name, expr);
  if (cache.has(key)) return { found: true, value: cache.get(key) };
  return { found: false };
}

/**
 * Kick off async evaluation if the binding isn't already cached or in-flight.
 * On resolution: store the value, drop the pending marker, refresh every
 * live expression chip so canvas previews of `params.foo` re-evaluate and
 * pick up the new value via getEvaluationContextAt → scope providers.
 *
 * Errors are logged once and stored as `undefined` so we don't keep retrying
 * a broken expression on every scope walk.
 */
export function evaluateParamAsync(
  nodeId: string,
  alias: string,
  name: string,
  expr: string,
  data: Record<string, unknown>,
): void {
  const key = makeKey(nodeId, alias, name, expr);
  if (cache.has(key) || pending.has(key)) return;
  pending.add(key);
  void evaluateExpression(expr, data)
    .then((value) => {
      cache.set(key, value);
    })
    .catch(() => {
      cache.set(key, undefined);
    })
    .finally(() => {
      pending.delete(key);
      ExpressionNodeView.refreshAll();
    });
}

/** Wipe both maps. Called on example/doc changes that may invalidate values. */
export function clearParameterCache(): void {
  cache.clear();
  pending.clear();
}

/**
 * Subscribe an engine instance so its example:change and structural doc:change
 * events clear the cache. Returns an unsubscribe function the caller is
 * expected to run on teardown.
 *
 * Non-structural doc changes (typing in a text node, prop edits on an
 * unrelated node) don't shift the scope graph, so we leave the cache
 * intact — the only cost of being too eager would be re-evaluating
 * bindings that didn't actually change.
 */
export function wireParameterCache(engine: EditorEngine): () => void {
  const unsubExample = engine.events.on('example:change', () => clearParameterCache());
  const unsubDoc = engine.events.on('doc:change', ({ structureChanged }) => {
    if (structureChanged) clearParameterCache();
  });
  return () => {
    unsubExample();
    unsubDoc();
  };
}
