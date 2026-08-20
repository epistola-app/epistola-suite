// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Shared JSON Schema resolution for editor features.
 *
 * This module deliberately has no dependency on either editor domain. It provides
 * value-aware resolution for forms and all-branch resolution for schema
 * introspection. Only local JSON Pointer references are expanded; external and
 * unresolved references remain intact so callers can handle them as safe leaves.
 */

export type JsonSchemaValue =
  | string
  | number
  | boolean
  | null
  | JsonSchemaValue[]
  | { [key: string]: JsonSchemaValue };

export interface JsonSchemaNode {
  $ref?: string;
  type?: string | string[];
  format?: string;
  title?: string;
  description?: string;
  properties?: Record<string, JsonSchemaNode>;
  items?: JsonSchemaNode;
  required?: string[];
  allOf?: JsonSchemaNode[];
  oneOf?: JsonSchemaNode[];
  anyOf?: JsonSchemaNode[];
  enum?: JsonSchemaValue[];
  const?: JsonSchemaValue;
  default?: JsonSchemaValue;
  minimum?: number;
  maximum?: number;
  minItems?: number;
  maxItems?: number;
  minLength?: number;
  maxLength?: number;
  multipleOf?: number;
  exclusiveMinimum?: number;
  exclusiveMaximum?: number;
  additionalProperties?: boolean | JsonSchemaNode;
  $defs?: Record<string, JsonSchemaNode>;
  definitions?: Record<string, JsonSchemaNode>;
}

/** Prevent hostile or accidental composition graphs from growing exponentially. */
export const MAX_RESOLVED_SCHEMA_VARIANTS = 256;

const INCOMPATIBLE_SCORE = Number.NEGATIVE_INFINITY;
const MAX_MATCH_DEPTH = 20;

export interface ResolvedSchemaVariant {
  schema: JsonSchemaNode;
  /** References active on the route to this variant, used to stop recursive schemas. */
  resolvingRefs: ReadonlySet<string>;
}

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

/**
 * Resolve every effective variant at one schema location.
 *
 * `allOf` members are merged and `oneOf`/`anyOf` branches are returned
 * separately. The reference ancestry travels with each result so consumers that
 * recurse into child properties can stop cycles without losing useful fields.
 */
export function resolveSchemaVariants(
  schema: JsonSchemaNode,
  rootSchema: JsonSchemaNode,
  resolvingRefs: ReadonlySet<string> = new Set(),
): ResolvedSchemaVariant[] {
  return resolveVariants(schema, rootSchema, resolvingRefs);
}

function resolveVariants(
  schema: JsonSchemaNode,
  rootSchema: JsonSchemaNode,
  resolvingRefs: ReadonlySet<string>,
): ResolvedSchemaVariant[] {
  const reference = typeof schema.$ref === 'string' ? schema.$ref : undefined;
  if (reference?.startsWith('#/') && !resolvingRefs.has(reference)) {
    const target = resolveLocalReference(rootSchema, reference);
    if (target) {
      const nextRefs = new Set(resolvingRefs);
      nextRefs.add(reference);
      const { $ref: _ref, ...siblings } = schema;
      return resolveVariants(target, rootSchema, nextRefs).flatMap((variant) =>
        expandCompositions(
          mergeSchemas(variant.schema, siblings),
          rootSchema,
          variant.resolvingRefs,
        ),
      );
    }
  }

  return expandCompositions(schema, rootSchema, resolvingRefs);
}

function expandCompositions(
  schema: JsonSchemaNode,
  rootSchema: JsonSchemaNode,
  resolvingRefs: ReadonlySet<string>,
): ResolvedSchemaVariant[] {
  let variants: ResolvedSchemaVariant[] = [{ schema: withoutCompositions(schema), resolvingRefs }];

  for (const member of schemaArray(schema.allOf)) {
    const memberVariants = resolveVariants(member, rootSchema, resolvingRefs);
    variants = combineVariants(variants, memberVariants);
  }

  for (const alternatives of [schemaArray(schema.oneOf), schemaArray(schema.anyOf)]) {
    if (alternatives.length > 0) {
      const alternativeVariants = collectAlternativeVariants(
        alternatives,
        rootSchema,
        resolvingRefs,
      );
      variants = combineVariants(variants, alternativeVariants);
    }
  }

  return variants;
}

function combineVariants(
  bases: ResolvedSchemaVariant[],
  extensions: ResolvedSchemaVariant[],
): ResolvedSchemaVariant[] {
  const combined: ResolvedSchemaVariant[] = [];
  const seen = new Set<string>();
  for (const base of bases) {
    for (const extension of extensions) {
      const variant = {
        schema: mergeSchemas(base.schema, extension.schema),
        resolvingRefs: new Set([...base.resolvingRefs, ...extension.resolvingRefs]),
      };
      const key = variantKey(variant);
      if (seen.has(key)) continue;
      seen.add(key);
      combined.push(variant);
      if (combined.length >= MAX_RESOLVED_SCHEMA_VARIANTS) return combined;
    }
  }
  return combined;
}

function collectAlternativeVariants(
  alternatives: JsonSchemaNode[],
  rootSchema: JsonSchemaNode,
  resolvingRefs: ReadonlySet<string>,
): ResolvedSchemaVariant[] {
  const variants: ResolvedSchemaVariant[] = [];
  const seen = new Set<string>();
  for (const alternative of alternatives) {
    for (const variant of resolveVariants(alternative, rootSchema, resolvingRefs)) {
      const key = variantKey(variant);
      if (seen.has(key)) continue;
      seen.add(key);
      variants.push(variant);
      if (variants.length >= MAX_RESOLVED_SCHEMA_VARIANTS) return variants;
    }
  }
  return variants;
}

function variantKey(variant: ResolvedSchemaVariant): string {
  return `${JSON.stringify(variant.schema)}|${[...variant.resolvingRefs].toSorted().join(',')}`;
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

function mergeSchemas(base: JsonSchemaNode, extension: JsonSchemaNode): JsonSchemaNode {
  const baseProperties = schemaProperties(base);
  const extensionProperties = schemaProperties(extension);
  const hasProperties =
    Object.keys(baseProperties).length > 0 || Object.keys(extensionProperties).length > 0;
  const required = [...stringArray(base.required), ...stringArray(extension.required)];

  return {
    ...base,
    ...extension,
    ...(hasProperties ? { properties: { ...baseProperties, ...extensionProperties } } : {}),
    ...(required.length > 0 ? { required: [...new Set(required)] } : {}),
  };
}

function withoutCompositions(schema: JsonSchemaNode): JsonSchemaNode {
  const { allOf: _allOf, anyOf: _anyOf, oneOf: _oneOf, ...rest } = schema;
  return rest;
}

function resolveLocalReference(
  rootSchema: JsonSchemaNode,
  reference: string,
): JsonSchemaNode | null {
  let current: unknown = rootSchema;
  for (const encodedSegment of reference.slice(2).split('/')) {
    if (!isObject(current)) return null;
    const segment = encodedSegment.replace(/~1/g, '/').replace(/~0/g, '~');
    current = current[segment];
  }
  return isObject(current) ? current : null;
}

function schemaTypes(schema: JsonSchemaNode): string[] {
  if (typeof schema.type === 'string') return [schema.type];
  return stringArray(schema.type);
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

function schemaProperties(schema: JsonSchemaNode): Record<string, JsonSchemaNode> {
  if (!isObject(schema.properties)) return {};
  return Object.fromEntries(
    Object.entries(schema.properties).filter((entry): entry is [string, JsonSchemaNode] =>
      isSchemaNode(entry[1]),
    ),
  );
}

function schemaArray(value: unknown): JsonSchemaNode[] {
  return Array.isArray(value) ? value.filter(isSchemaNode) : [];
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

function sameJson(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isSchemaNode(value: unknown): value is JsonSchemaNode {
  return isObject(value);
}
