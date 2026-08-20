// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { findRefType } from '../ref-types.js';
import type { JsonArray, JsonObject, JsonSchema, JsonSchemaProperty, JsonValue } from '../types.js';

const GENERATED_DATE = '2024-01-01';
const GENERATED_DATE_TIME = '2024-01-01T12:00:00Z';

type SchemaNode = JsonSchemaProperty & {
  type?: string | string[];
};

/**
 * Complete example data from a JSON Schema without replacing authored values.
 * Missing nested properties and array items are filled recursively.
 */
export function completeExampleFromSchema(
  schema: JsonSchema | JsonObject,
  existing: JsonObject,
): JsonObject {
  return completeObject(schema as SchemaNode, schema as JsonObject, existing, 0);
}

function completeObject(
  schema: SchemaNode,
  rootSchema: JsonObject,
  existing: JsonObject,
  depth: number,
): JsonObject {
  if (depth > 20) return structuredClone(existing);

  const result = structuredClone(existing);
  for (const [name, propertySchema] of Object.entries(schema.properties ?? {})) {
    const present = Object.prototype.hasOwnProperty.call(existing, name);
    result[name] = completeValue(
      propertySchema as SchemaNode,
      rootSchema,
      existing[name],
      present,
      depth + 1,
    );
  }
  return result;
}

function completeValue(
  originalSchema: SchemaNode,
  rootSchema: JsonObject,
  existing: JsonValue | undefined,
  present: boolean,
  depth: number,
): JsonValue {
  if (depth > 20) return present ? structuredClone(existing as JsonValue) : null;

  const refType = findRefType(originalSchema.$ref);
  if (refType) {
    return present ? structuredClone(existing as JsonValue) : refType.defaultValue();
  }

  const schema = resolveLocalRef(originalSchema, rootSchema) ?? originalSchema;
  const type = resolveType(schema);

  if (present) {
    if (type === 'object' && isJsonObject(existing)) {
      return completeObject(schema, rootSchema, existing, depth);
    }
    if (type === 'array' && Array.isArray(existing)) {
      return completeArray(schema, rootSchema, existing, depth);
    }
    return structuredClone(existing as JsonValue);
  }

  if (Object.prototype.hasOwnProperty.call(schema, 'const')) {
    return structuredClone(schema.const as JsonValue);
  }
  if (Object.prototype.hasOwnProperty.call(schema, 'default')) {
    return completeGeneratedContainer(schema, rootSchema, schema.default as JsonValue, depth);
  }
  if (schema.enum && schema.enum.length > 0) {
    return structuredClone(schema.enum[0]);
  }

  switch (type) {
    case 'object':
      return completeObject(schema, rootSchema, {}, depth);
    case 'array':
      return completeArray(schema, rootSchema, [], depth);
    case 'boolean':
      return false;
    case 'integer':
      return generateNumber(schema, true);
    case 'number':
      return generateNumber(schema, false);
    case 'null':
      return null;
    case 'string':
    default:
      return generateString(schema);
  }
}

function completeGeneratedContainer(
  schema: SchemaNode,
  rootSchema: JsonObject,
  generated: JsonValue,
  depth: number,
): JsonValue {
  const type = resolveType(schema);
  if (type === 'object' && isJsonObject(generated)) {
    return completeObject(schema, rootSchema, generated, depth);
  }
  if (type === 'array' && Array.isArray(generated)) {
    return completeArray(schema, rootSchema, generated, depth);
  }
  return structuredClone(generated);
}

function completeArray(
  schema: SchemaNode,
  rootSchema: JsonObject,
  existing: JsonArray,
  depth: number,
): JsonArray {
  const result = structuredClone(existing);
  if (!schema.items) return result;

  for (let index = 0; index < result.length; index += 1) {
    result[index] = completeValue(
      schema.items as SchemaNode,
      rootSchema,
      result[index],
      true,
      depth + 1,
    );
  }

  const minimum = Math.max(0, schema.minItems ?? (result.length === 0 ? 1 : 0));
  const target = schema.maxItems === undefined ? minimum : Math.min(minimum, schema.maxItems);
  while (result.length < target) {
    result.push(completeValue(schema.items as SchemaNode, rootSchema, undefined, false, depth + 1));
  }
  return result;
}

function generateString(schema: SchemaNode): string {
  let value = 'Example value';
  switch (schema.format) {
    case 'date':
      value = GENERATED_DATE;
      break;
    case 'date-time':
      value = GENERATED_DATE_TIME;
      break;
    case 'email':
      value = 'example@example.com';
      break;
    case 'uri':
      value = 'https://example.com';
      break;
  }

  const minimum = Math.max(0, schema.minLength ?? 0);
  if (value.length < minimum) value = value.padEnd(minimum, 'x');
  if (schema.maxLength !== undefined) value = value.slice(0, schema.maxLength);
  return value;
}

function generateNumber(schema: SchemaNode, integer: boolean): number {
  let value = 0;
  if (schema.minimum !== undefined) value = schema.minimum;
  if (schema.exclusiveMinimum !== undefined) {
    value = Math.max(value, schema.exclusiveMinimum + (integer ? 1 : 0.01));
  }
  if (schema.maximum !== undefined) value = Math.min(value, schema.maximum);
  if (schema.exclusiveMaximum !== undefined) {
    value = Math.min(value, schema.exclusiveMaximum - (integer ? 1 : 0.01));
  }
  if (schema.multipleOf && schema.multipleOf > 0) {
    value = Math.ceil(value / schema.multipleOf) * schema.multipleOf;
  }
  return integer ? Math.ceil(value) : value;
}

function resolveType(schema: SchemaNode): string {
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  const nonNullType = types.find((type) => type !== 'null');
  if (nonNullType) return nonNullType;
  if (schema.properties) return 'object';
  if (schema.items) return 'array';
  return types[0] ?? 'string';
}

function resolveLocalRef(schema: SchemaNode, rootSchema: JsonObject): SchemaNode | null {
  if (!schema.$ref?.startsWith('#/')) return null;
  let current: unknown = rootSchema;
  for (const encodedSegment of schema.$ref.slice(2).split('/')) {
    if (!isJsonObject(current)) return null;
    const segment = encodedSegment.replace(/~1/g, '/').replace(/~0/g, '~');
    current = current[segment];
  }
  return isJsonObject(current) ? (current as SchemaNode) : null;
}

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
