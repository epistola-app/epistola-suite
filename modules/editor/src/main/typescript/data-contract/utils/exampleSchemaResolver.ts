// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { JsonObject, JsonSchema, JsonSchemaProperty, JsonValue } from '../types.js';

type SchemaRoot = JsonSchema | JsonObject;

/** Resolve the schema shape the example form can render at a specific value. */
export function resolveExampleSchema(
  schema: JsonSchemaProperty,
  rootSchema: SchemaRoot,
  value?: JsonValue,
  resolvingRefs: ReadonlySet<string> = new Set(),
): JsonSchemaProperty {
  let effective = resolveReference(schema, rootSchema, value, resolvingRefs);

  if (effective.allOf) {
    effective = effective.allOf.reduce(
      (combined, member) =>
        mergeSchemas(combined, resolveExampleSchema(member, rootSchema, value, resolvingRefs)),
      withoutCompositions(effective),
    );
  }

  const alternatives = effective.oneOf ?? effective.anyOf;
  if (alternatives && alternatives.length > 0) {
    const selected = selectAlternative(alternatives, rootSchema, value, resolvingRefs);
    effective = mergeSchemas(
      withoutCompositions(effective),
      resolveExampleSchema(selected, rootSchema, value, resolvingRefs),
    );
  }

  return effective;
}

function resolveReference(
  schema: JsonSchemaProperty,
  rootSchema: SchemaRoot,
  value: JsonValue | undefined,
  resolvingRefs: ReadonlySet<string>,
): JsonSchemaProperty {
  if (!schema.$ref?.startsWith('#/') || resolvingRefs.has(schema.$ref)) return schema;

  const target = resolveLocalReference(rootSchema, schema.$ref);
  if (!target) return schema;

  const nextRefs = new Set(resolvingRefs);
  nextRefs.add(schema.$ref);
  const resolvedTarget = resolveExampleSchema(target, rootSchema, value, nextRefs);
  const { $ref: _ref, ...siblings } = schema;
  return mergeSchemas(resolvedTarget, siblings);
}

function selectAlternative(
  alternatives: JsonSchemaProperty[],
  rootSchema: SchemaRoot,
  value: JsonValue | undefined,
  resolvingRefs: ReadonlySet<string>,
): JsonSchemaProperty {
  if (value === undefined) return alternatives[0];

  if (value === null) {
    const editableAlternative = alternatives.find((candidate) => {
      const resolved = resolveExampleSchema(candidate, rootSchema, value, resolvingRefs);
      const types: readonly string[] = Array.isArray(resolved.type)
        ? resolved.type
        : resolved.type
          ? [resolved.type]
          : [];
      return types.some((type) => type !== 'null');
    });
    if (editableAlternative) return editableAlternative;
  }

  return (
    alternatives.find((candidate) =>
      schemaMatchesValue(resolveExampleSchema(candidate, rootSchema, value, resolvingRefs), value),
    ) ?? alternatives[0]
  );
}

function schemaMatchesValue(schema: JsonSchemaProperty, value: JsonValue): boolean {
  if (schema.const !== undefined && !sameJson(schema.const, value)) return false;
  if (schema.enum && !schema.enum.some((candidate) => sameJson(candidate, value))) return false;

  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  if (types.length > 0 && !types.some((type) => valueMatchesType(value, type))) return false;

  if (isObject(value) && schema.required) {
    return schema.required.every((name) => Object.hasOwn(value, name));
  }
  return true;
}

function valueMatchesType(value: JsonValue, type: string): boolean {
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

function mergeSchemas(base: JsonSchemaProperty, extension: JsonSchemaProperty): JsonSchemaProperty {
  const properties =
    base.properties || extension.properties
      ? { ...base.properties, ...extension.properties }
      : undefined;
  const required =
    base.required || extension.required
      ? [...new Set([...(base.required ?? []), ...(extension.required ?? [])])]
      : undefined;

  return {
    ...base,
    ...extension,
    ...(properties ? { properties } : {}),
    ...(required ? { required } : {}),
  };
}

function withoutCompositions(schema: JsonSchemaProperty): JsonSchemaProperty {
  const { allOf: _allOf, anyOf: _anyOf, oneOf: _oneOf, ...rest } = schema;
  return rest;
}

function resolveLocalReference(
  rootSchema: SchemaRoot,
  reference: string,
): JsonSchemaProperty | null {
  let current: unknown = rootSchema;
  for (const encodedSegment of reference.slice(2).split('/')) {
    if (!isObject(current)) return null;
    const segment = encodedSegment.replace(/~1/g, '/').replace(/~0/g, '~');
    current = current[segment];
  }
  return isObject(current) ? current : null;
}

function sameJson(left: JsonValue, right: JsonValue): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
