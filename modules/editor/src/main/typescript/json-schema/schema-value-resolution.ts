// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import {
  isObject,
  sameJson,
  schemaProperties,
  schemaTypes,
  type JsonSchemaNode,
} from './schema-node.js';
import { resolveSchemaVariants, type ResolvedSchemaVariant } from './schema-variants.js';

const INCOMPATIBLE_SCORE = Number.NEGATIVE_INFINITY;
const MAX_MATCH_DEPTH = 20;

/** Resolve the single effective schema that best matches an existing value. */
export function resolveSchemaForValue(
  schema: JsonSchemaNode,
  rootSchema: JsonSchemaNode,
  value?: unknown,
): JsonSchemaNode {
  const variants = resolveSchemaVariants(schema, rootSchema);
  const selected = selectVariant(variants, rootSchema, value);
  return selected?.schema ?? schema;
}

function selectVariant(
  variants: ResolvedSchemaVariant[],
  rootSchema: JsonSchemaNode,
  value: unknown,
): ResolvedSchemaVariant | undefined {
  if (variants.length <= 1) return variants[0];

  if (value === null || value === undefined) {
    const editable = variants.find(({ schema }) => !isNullOnly(schema));
    if (editable) return editable;
  }

  return variants
    .map((variant, index) => ({
      variant,
      index,
      score: scoreSchemaMatch(variant.schema, rootSchema, value, variant.resolvingRefs, 0),
    }))
    .toSorted((left, right) => right.score - left.score || left.index - right.index)[0]?.variant;
}

function scoreSchemaMatch(
  schema: JsonSchemaNode,
  rootSchema: JsonSchemaNode,
  value: unknown,
  resolvingRefs: ReadonlySet<string>,
  depth: number,
): number {
  if (depth > MAX_MATCH_DEPTH) return 0;
  if (schema.const !== undefined && !sameJson(schema.const, value)) return INCOMPATIBLE_SCORE;
  if (Array.isArray(schema.enum) && !schema.enum.some((candidate) => sameJson(candidate, value))) {
    return INCOMPATIBLE_SCORE;
  }

  const types = inferredSchemaTypes(schema);
  if (types.length > 0 && !types.some((type) => valueMatchesType(value, type))) {
    return INCOMPATIBLE_SCORE;
  }

  let score = 1;
  if (isObject(value)) {
    const properties = schemaProperties(schema);
    for (const [name, propertyValue] of Object.entries(value)) {
      const propertySchema = properties[name];
      if (!propertySchema) continue;
      score += 4;
      const childVariants = resolveSchemaVariants(propertySchema, rootSchema, resolvingRefs);
      const childScore = Math.max(
        ...childVariants.map((variant) =>
          scoreSchemaMatch(
            variant.schema,
            rootSchema,
            propertyValue,
            variant.resolvingRefs,
            depth + 1,
          ),
        ),
      );
      score += childScore === INCOMPATIBLE_SCORE ? -8 : Math.min(childScore, 8);
    }
    for (const name of schema.required ?? []) {
      score += Object.hasOwn(value, name) ? 2 : -1;
    }
  }

  if (Array.isArray(value) && schema.items) {
    for (const item of value.slice(0, 5)) {
      const itemVariants = resolveSchemaVariants(schema.items, rootSchema, resolvingRefs);
      score += Math.max(
        ...itemVariants.map((variant) =>
          scoreSchemaMatch(variant.schema, rootSchema, item, variant.resolvingRefs, depth + 1),
        ),
      );
    }
  }
  return score;
}

function inferredSchemaTypes(schema: JsonSchemaNode): string[] {
  const types = schemaTypes(schema);
  if (types.length > 0) return types;
  if (schema.properties) return ['object'];
  if (schema.items) return ['array'];
  return [];
}

function isNullOnly(schema: JsonSchemaNode): boolean {
  const types = schemaTypes(schema);
  return types.length === 1 && types[0] === 'null';
}

function valueMatchesType(value: unknown, type: string): boolean {
  switch (type) {
    case 'null':
      return value === null;
    case 'array':
      return Array.isArray(value);
    case 'object':
      return isObject(value);
    case 'integer':
      return typeof value === 'number' && Number.isInteger(value);
    case 'number':
      return typeof value === 'number';
    default:
      return typeof value === type;
  }
}
