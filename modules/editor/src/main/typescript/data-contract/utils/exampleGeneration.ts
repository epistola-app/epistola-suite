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

export interface ExampleField {
  name: string;
  title?: string;
  description?: string;
}

export interface ExampleValueProvider {
  string(field: ExampleField): string | undefined;
  number(field: ExampleField): number | undefined;
  boolean(field: ExampleField): boolean | undefined;
}

const NO_SEMANTIC_VALUES: ExampleValueProvider = {
  string: () => undefined,
  number: () => undefined,
  boolean: () => undefined,
};

/**
 * Complete example data from a JSON Schema without replacing authored values.
 * Missing nested properties and array items are filled recursively.
 */
export function completeExampleFromSchema(
  schema: JsonSchema | JsonObject,
  existing: JsonObject,
  semanticValues: ExampleValueProvider = NO_SEMANTIC_VALUES,
): JsonObject {
  return completeObject(schema as SchemaNode, schema as JsonObject, existing, semanticValues, 0);
}

function completeObject(
  schema: SchemaNode,
  rootSchema: JsonObject,
  existing: JsonObject,
  semanticValues: ExampleValueProvider,
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
      { name, title: propertySchema.title, description: propertySchema.description },
      semanticValues,
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
  field: ExampleField,
  semanticValues: ExampleValueProvider,
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
      return completeObject(schema, rootSchema, existing, semanticValues, depth);
    }
    if (type === 'array' && Array.isArray(existing)) {
      return completeArray(schema, rootSchema, existing, field, semanticValues, depth);
    }
    return structuredClone(existing as JsonValue);
  }

  if (Object.prototype.hasOwnProperty.call(schema, 'const')) {
    return structuredClone(schema.const as JsonValue);
  }
  if (Object.prototype.hasOwnProperty.call(schema, 'default')) {
    return completeGeneratedContainer(
      schema,
      rootSchema,
      schema.default as JsonValue,
      field,
      semanticValues,
      depth,
    );
  }
  if (schema.enum && schema.enum.length > 0) {
    return structuredClone(schema.enum[0]);
  }

  switch (type) {
    case 'object':
      return completeObject(schema, rootSchema, {}, semanticValues, depth);
    case 'array':
      return completeArray(schema, rootSchema, [], field, semanticValues, depth);
    case 'boolean':
      return semanticValues.boolean(field) ?? false;
    case 'integer':
      return generateNumber(schema, true, semanticValues.number(field));
    case 'number':
      return generateNumber(schema, false, semanticValues.number(field));
    case 'null':
      return null;
    case 'string':
    default:
      return generateString(schema, semanticValues.string(field));
  }
}

function completeGeneratedContainer(
  schema: SchemaNode,
  rootSchema: JsonObject,
  generated: JsonValue,
  field: ExampleField,
  semanticValues: ExampleValueProvider,
  depth: number,
): JsonValue {
  const type = resolveType(schema);
  if (type === 'object' && isJsonObject(generated)) {
    return completeObject(schema, rootSchema, generated, semanticValues, depth);
  }
  if (type === 'array' && Array.isArray(generated)) {
    return completeArray(schema, rootSchema, generated, field, semanticValues, depth);
  }
  return structuredClone(generated);
}

function completeArray(
  schema: SchemaNode,
  rootSchema: JsonObject,
  existing: JsonArray,
  field: ExampleField,
  semanticValues: ExampleValueProvider,
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
      field,
      semanticValues,
      depth + 1,
    );
  }

  const minimum = Math.max(0, schema.minItems ?? (result.length === 0 ? 1 : 0));
  const target = schema.maxItems === undefined ? minimum : Math.min(minimum, schema.maxItems);
  while (result.length < target) {
    result.push(
      completeValue(
        schema.items as SchemaNode,
        rootSchema,
        undefined,
        false,
        field,
        semanticValues,
        depth + 1,
      ),
    );
  }
  return result;
}

function generateString(schema: SchemaNode, semanticValue?: string): string {
  let value: string;
  switch (schema.format) {
    case 'date':
      value = semanticValue?.match(/^\d{4}-\d{2}-\d{2}$/) ? semanticValue : GENERATED_DATE;
      break;
    case 'date-time':
      value = GENERATED_DATE_TIME;
      break;
    case 'email':
      value = semanticValue?.includes('@') ? semanticValue : 'example@example.com';
      break;
    case 'uri':
      value = semanticValue?.startsWith('http') ? semanticValue : 'https://example.com';
      break;
    default:
      value = semanticValue ?? 'Example value';
  }

  const minimum = Math.max(0, schema.minLength ?? 0);
  if (value.length < minimum) value = value.padEnd(minimum, 'x');
  if (schema.maxLength !== undefined) value = value.slice(0, schema.maxLength);
  return value;
}

function generateNumber(schema: SchemaNode, integer: boolean, semanticValue?: number): number {
  let value = semanticValue ?? 0;
  if (schema.minimum !== undefined) value = Math.max(value, schema.minimum);
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
