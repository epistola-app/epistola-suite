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
 * but the PDF renderer is the source of truth, so it still resolves at render
 * time. Schema defaults fill in unbound params so the canvas isn't blank.
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

  // Evaluate bindings against the outer evaluation context. For simple paths
  // this gives accurate canvas previews; complex JSONata falls through to
  // undefined here and resolves correctly at render time.
  const params: Record<string, unknown> = {};
  const outer = ctx.evaluationContext;
  for (const [name, propSchema] of Object.entries(schema.properties)) {
    const expr = bindings[name];
    let value: unknown = undefined;
    if (typeof expr === 'string' && expr.trim() && outer) {
      value = resolveSimplePath(outer, expr.trim());
    }
    if (value === undefined) {
      const def = (propSchema as JsonSchemaProperty & { default?: unknown }).default;
      if (def !== undefined) value = def;
    }
    if (value !== undefined) params[name] = value;
  }

  const evaluationData =
    Object.keys(params).length > 0 ? ({ [alias]: params } as Record<string, unknown>) : undefined;

  return { variables, evaluationData };
}

function typeFromSchema(prop: JsonSchemaProperty | undefined): string {
  if (!prop) return 'string';
  const t = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  if (t === 'string' && prop.format === 'date') return 'date';
  if (t === 'string' && prop.format === 'date-time') return 'datetime';
  return t ?? 'string';
}
