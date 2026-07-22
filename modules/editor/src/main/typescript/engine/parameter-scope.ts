/**
 * Generic parameter-scope provider.
 *
 * For any node whose `props.parameterSchemaSnapshot` declares parameters,
 * exposes the declared parameters as scoped FieldPath entries under the
 * node's `paramsAlias` (default `params`) and evaluates each binding
 * against the outer evaluation context so the canvas preview shows
 * resolved values for `params.foo` / `letter.title`.
 *
 * Today the only parametrised component is the stencil, which carries its
 * schema as a snapshot prop. A future static-parametrised component would
 * need a small enrichment so the scope provider can read its schema from
 * the [ComponentDefinition.parameters] field instead — for now we keep the
 * lookup simple and read directly from props.
 *
 * Resolution order per parameter (per call):
 *   1. Cached value from the async-eval cache (real JSONata, same library
 *      the PDF renderer uses). Cache hit → return it.
 *   2. Cache miss → schedule async evaluation (cache populates + canvas
 *      refreshes when ready). Meanwhile fall through to:
 *   3. Schema default.
 *   4. Synthetic placeholder (`<paramName>`) — so authors editing a stencil
 *      draft (where no bindings exist yet) see something useful in the canvas
 *      instead of a literal `{{params.foo}}`.
 *
 * The params namespace is *always* populated when the schema declares
 * properties, so `params.foo` never falls through to `undefined` and never
 * renders as raw text in the editor.
 */
import type { JsonSchema, JsonSchemaProperty } from '../data-contract/types.js';
import { scalarFromJsonSchema } from '../data-contract/field-types.js';
import type { ScopeDeclaration, ScopeProviderContext } from './registry.js';
import type { FieldPath } from './schema-paths.js';
import { evaluateParamAsync, getCachedParamValue } from './parameter-evaluation-cache.js';
import type { Node } from '../types/index.js';

export function buildParameterScope(
  node: Node,
  ctx: ScopeProviderContext,
): ScopeDeclaration | null {
  const props = node.props ?? {};
  const schema = props.parameterSchemaSnapshot as JsonSchema | undefined;
  if (!schema?.properties) return null;
  const alias = (typeof props.paramsAlias === 'string' && props.paramsAlias.trim()) || 'params';
  const bindings = (props.parameterBindings ?? {}) as Record<string, string>;

  const variables: FieldPath[] = Object.entries(schema.properties).map(([name, propSchema]) => ({
    path: `${alias}.${name}`,
    type: typeFromSchema(propSchema),
    scope: alias,
    scopeKind: 'stencil-parameter',
    description: propSchema?.description,
  }));

  const params: Record<string, unknown> = {};
  const outer = ctx.evaluationContext;
  for (const [name, propSchema] of Object.entries(schema.properties)) {
    params[name] = resolveValue(node.id, alias, name, bindings[name], propSchema, outer);
  }

  return {
    variables,
    evaluationData: { [alias]: params },
  };
}

function resolveValue(
  nodeId: string,
  alias: string,
  name: string,
  expr: string | undefined,
  propSchema: JsonSchemaProperty | undefined,
  outer: Record<string, unknown> | undefined,
): unknown {
  const trimmed = typeof expr === 'string' ? expr.trim() : '';
  if (trimmed && outer) {
    const cached = getCachedParamValue(nodeId, alias, name, trimmed);
    if (cached.found) return cached.value;
    // Kick off the async eval; the result populates the cache and triggers
    // ExpressionNodeView.refreshAll, which re-renders chips against the
    // updated scope. While pending we fall through to defaults below.
    evaluateParamAsync(nodeId, alias, name, trimmed, outer);
  }
  const def = (propSchema as (JsonSchemaProperty & { default?: unknown }) | undefined)?.default;
  if (def !== undefined) return def;
  // Synthetic placeholder so authors see something while editing a draft
  // (no bindings yet) and consumers see an obvious flag for unbound params.
  return `<${name}>`;
}

function typeFromSchema(prop: JsonSchemaProperty | undefined): string {
  if (!prop) return 'string';
  const t = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  // Collapse date / date-time via the shared registry; non-scalars keep raw type.
  return scalarFromJsonSchema(t, prop.format) ?? t ?? 'string';
}
