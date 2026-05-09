/**
 * Generic parameter-scope provider.
 *
 * For any node whose `props.parameterSchemaSnapshot` declares parameters,
 * exposes the declared parameters as scoped FieldPath entries under the
 * node's `paramsAlias` (default `params`). Inside the node's content,
 * expressions like `params.foo` / `letter.title` pick up autocomplete and
 * type-aware filtering — the same way iteration variables work.
 *
 * Today the only parametrised component is the stencil, which carries its
 * schema as a snapshot prop. A future static-parametrised component would
 * need a small enrichment so the scope provider can read its schema from
 * the [ComponentDefinition.parameters] field instead — for now we keep the
 * lookup simple and read directly from props.
 *
 * Eager evaluation of bindings for canvas preview is intentionally deferred:
 * JSONata's `evaluate` is async, and the scope-provider walk is sync. The PDF
 * renderer is the source of truth for resolved values; the editor canvas only
 * needs the variable names + types for autocomplete to work correctly.
 */
import type { JsonSchema, JsonSchemaProperty } from '../data-contract/types.js';
import type { ScopeDeclaration, ScopeProviderContext } from './registry.js';
import type { FieldPath } from './schema-paths.js';
import type { Node } from '../types/index.js';

export function buildParameterScope(
  node: Node,
  _ctx: ScopeProviderContext,
): ScopeDeclaration | null {
  const props = node.props ?? {};
  const schema = props.parameterSchemaSnapshot as JsonSchema | undefined;
  if (!schema?.properties) return null;
  const alias = (typeof props.paramsAlias === 'string' && props.paramsAlias.trim()) || 'params';

  const variables: FieldPath[] = Object.entries(schema.properties).map(([name, propSchema]) => ({
    path: `${alias}.${name}`,
    type: typeFromSchema(propSchema),
    scope: alias,
    description: propSchema?.description,
  }));

  return { variables };
}

function typeFromSchema(prop: JsonSchemaProperty | undefined): string {
  if (!prop) return 'string';
  const t = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  if (t === 'string' && prop.format === 'date') return 'date';
  if (t === 'string' && prop.format === 'date-time') return 'datetime';
  return t ?? 'string';
}
