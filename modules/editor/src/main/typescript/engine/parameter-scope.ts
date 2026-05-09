/**
 * Generic parameter-scope provider.
 *
 * For any node whose `props.parameterSchemaSnapshot` declares parameters,
 * exposes the declared parameters as scoped FieldPath entries under the
 * node's `paramsAlias` (default `params`) and best-effort-evaluates each
 * binding against the outer evaluation context so the canvas preview shows
 * resolved values for `params.foo` / `letter.title`.
 *
 * Today the only parametrised component is the stencil, which carries its
 * schema as a snapshot prop. A future static-parametrised component would
 * need a small enrichment so the scope provider can read its schema from
 * the [ComponentDefinition.parameters] field instead — for now we keep the
 * lookup simple and read directly from props.
 *
 * Evaluation is intentionally synchronous: JSONata's `evaluate` is async and
 * the scope-provider walk is sync, so we use the same dot-path resolver the
 * iteration scope uses (`resolveSimplePath`). Simple bindings (`customer.name`,
 * `params.title`, `sys.pages.current`) resolve in the canvas preview; complex
 * JSONata (e.g. `$sum(...)`, string concatenation) doesn't get a preview value
 * here, but the PDF renderer is the source of truth so it still resolves at
 * render time. Resolution order per parameter:
 *   1. Bound expression resolved as simple path against the outer context.
 *   2. Schema default.
 *   3. Synthetic placeholder (`<paramName>`) — so authors editing a stencil
 *      draft (where no bindings exist yet) see something useful in the canvas
 *      instead of a literal `{{params.foo}}`.
 *
 * The params namespace is *always* populated when the schema declares
 * properties, so `params.foo` never falls through to `undefined` and never
 * renders as raw text in the editor.
 */
import type { JsonSchema, JsonSchemaProperty } from '../data-contract/types.js';
import type { ScopeDeclaration, ScopeProviderContext } from './registry.js';
import type { FieldPath } from './schema-paths.js';
import { resolveSimplePath } from './scoped-fields.js';
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
    description: propSchema?.description,
  }));

  const params: Record<string, unknown> = {};
  const outer = ctx.evaluationContext;
  for (const [name, propSchema] of Object.entries(schema.properties)) {
    params[name] = resolveValue(name, bindings[name], propSchema, outer);
  }

  return {
    variables,
    evaluationData: { [alias]: params },
  };
}

function resolveValue(
  name: string,
  expr: string | undefined,
  propSchema: JsonSchemaProperty | undefined,
  outer: Record<string, unknown> | undefined,
): unknown {
  if (typeof expr === 'string' && expr.trim() && outer) {
    const resolved = resolveSimplePath(outer, expr.trim());
    if (resolved !== undefined) return resolved;
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
  if (t === 'string' && prop.format === 'date') return 'date';
  if (t === 'string' && prop.format === 'date-time') return 'datetime';
  return t ?? 'string';
}
