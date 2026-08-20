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

type SchemaNode = Record<string, unknown>;

export interface ResolvedSchemaVariant {
  schema: SchemaNode;
  /** References active on the route to this variant, used to stop recursive schemas. */
  resolvingRefs: ReadonlySet<string>;
}

/** Resolve the single effective schema that best matches an existing value. */
export function resolveSchemaForValue<TSchema extends object>(
  schema: TSchema,
  rootSchema: object,
  value?: unknown,
): TSchema;
export function resolveSchemaForValue(schema: object, rootSchema: object, value?: unknown): object {
  const variants = resolveSchemaVariants(schema, rootSchema);
  const selected = selectVariant(variants, value);
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
  schema: object,
  rootSchema: object,
  resolvingRefs: ReadonlySet<string> = new Set(),
): ResolvedSchemaVariant[] {
  if (!isObject(schema) || !isObject(rootSchema)) return [];
  return resolveVariants(schema, rootSchema, resolvingRefs);
}

function resolveVariants(
  schema: SchemaNode,
  rootSchema: SchemaNode,
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
  schema: SchemaNode,
  rootSchema: SchemaNode,
  resolvingRefs: ReadonlySet<string>,
): ResolvedSchemaVariant[] {
  let variants: ResolvedSchemaVariant[] = [{ schema: withoutCompositions(schema), resolvingRefs }];

  for (const member of schemaArray(schema.allOf)) {
    const memberVariants = resolveVariants(member, rootSchema, resolvingRefs);
    variants = combineVariants(variants, memberVariants);
  }

  const alternatives = schemaArray(schema.oneOf ?? schema.anyOf);
  if (alternatives.length > 0) {
    const alternativeVariants = alternatives.flatMap((alternative) =>
      resolveVariants(alternative, rootSchema, resolvingRefs),
    );
    variants = combineVariants(variants, alternativeVariants);
  }

  return variants;
}

function combineVariants(
  bases: ResolvedSchemaVariant[],
  extensions: ResolvedSchemaVariant[],
): ResolvedSchemaVariant[] {
  return bases.flatMap((base) =>
    extensions.map((extension) => ({
      schema: mergeSchemas(base.schema, extension.schema),
      resolvingRefs: new Set([...base.resolvingRefs, ...extension.resolvingRefs]),
    })),
  );
}

function selectVariant(
  variants: ResolvedSchemaVariant[],
  value: unknown,
): ResolvedSchemaVariant | undefined {
  if (variants.length <= 1 || value === undefined) return variants[0];

  if (value === null) {
    const editable = variants.find(({ schema }) =>
      schemaTypes(schema).some((type) => type !== 'null'),
    );
    if (editable) return editable;
  }

  const exact = variants.find(({ schema }) => schemaMatchesValue(schema, value));
  if (exact) return exact;

  // Required fields make partially authored objects fail every exact match.
  // Prefer the branch with the largest overlap with keys already present.
  if (isObject(value)) {
    const scored = variants
      .map((variant, index) => ({
        variant,
        index,
        score: Object.keys(value).filter((key) => key in schemaProperties(variant.schema)).length,
      }))
      .toSorted((left, right) => right.score - left.score || left.index - right.index);
    if (scored[0]?.score > 0) return scored[0].variant;
  }

  return variants[0];
}

function schemaMatchesValue(schema: SchemaNode, value: unknown): boolean {
  if (schema.const !== undefined && !sameJson(schema.const, value)) return false;
  if (Array.isArray(schema.enum) && !schema.enum.some((candidate) => sameJson(candidate, value))) {
    return false;
  }

  const types = schemaTypes(schema);
  if (types.length > 0 && !types.some((type) => valueMatchesType(value, type))) return false;

  if (isObject(value) && Array.isArray(schema.required)) {
    return schema.required.every((name) => typeof name === 'string' && Object.hasOwn(value, name));
  }
  return true;
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

function mergeSchemas(base: SchemaNode, extension: SchemaNode): SchemaNode {
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

function withoutCompositions(schema: SchemaNode): SchemaNode {
  const { allOf: _allOf, anyOf: _anyOf, oneOf: _oneOf, ...rest } = schema;
  return rest;
}

function resolveLocalReference(rootSchema: SchemaNode, reference: string): SchemaNode | null {
  let current: unknown = rootSchema;
  for (const encodedSegment of reference.slice(2).split('/')) {
    if (!isObject(current)) return null;
    const segment = encodedSegment.replace(/~1/g, '/').replace(/~0/g, '~');
    current = current[segment];
  }
  return isObject(current) ? current : null;
}

function schemaTypes(schema: SchemaNode): string[] {
  if (typeof schema.type === 'string') return [schema.type];
  return stringArray(schema.type);
}

function schemaProperties(schema: SchemaNode): Record<string, SchemaNode> {
  if (!isObject(schema.properties)) return {};
  return Object.fromEntries(
    Object.entries(schema.properties).filter((entry): entry is [string, SchemaNode] =>
      isObject(entry[1]),
    ),
  );
}

function schemaArray(value: unknown): SchemaNode[] {
  return Array.isArray(value) ? value.filter(isObject) : [];
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

function sameJson(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function isObject(value: unknown): value is SchemaNode {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
