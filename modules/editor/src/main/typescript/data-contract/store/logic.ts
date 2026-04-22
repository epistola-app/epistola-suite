import type { JsonValue, JsonObject, JsonSchema, JsonSchemaProperty } from '../types.js';

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

export function isJsonObject(value: JsonValue): value is JsonObject {
  return isRecord(value) && !Array.isArray(value);
}

export function isDeepEqual(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) return true;

  if (
    left === null ||
    right === null ||
    typeof left === 'undefined' ||
    typeof right === 'undefined'
  ) {
    return left === right;
  }

  if (typeof left !== 'object' || typeof right !== 'object') {
    return false;
  }

  if (Array.isArray(left) || Array.isArray(right)) {
    return isDeepEqualArray(left, right);
  }

  if (!isRecord(left) || !isRecord(right)) {
    return false;
  }

  return isDeepEqualRecord(left, right);
}

function isDeepEqualArray(left: unknown, right: unknown): boolean {
  if (!Array.isArray(left) || !Array.isArray(right)) {
    return false;
  }

  if (left.length !== right.length) {
    return false;
  }

  for (let i = 0; i < left.length; i += 1) {
    if (!isDeepEqual(left[i], right[i])) {
      return false;
    }
  }

  return true;
}

function isDeepEqualRecord(left: Record<string, unknown>, right: Record<string, unknown>): boolean {
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);

  if (leftKeys.length !== rightKeys.length) return false;

  for (const key of leftKeys) {
    if (!(key in right)) return false;
    if (!isDeepEqual(left[key], right[key])) return false;
  }

  return true;
}

export function pruneValueToSchema(
  value: JsonValue,
  schema: JsonSchema | JsonSchemaProperty,
): JsonValue {
  if (value === null || typeof value === 'undefined') return value;

  const schemaType = Array.isArray(schema.type) ? schema.type[0] : schema.type;

  if (schemaType === 'object' && typeof value === 'object' && !Array.isArray(value)) {
    const obj = value;
    const properties = schema.properties ?? {};
    const result: JsonObject = {};

    for (const [key, propSchema] of Object.entries(properties)) {
      if (key in obj) {
        result[key] = pruneValueToSchema(obj[key], propSchema);
      }
    }

    return result;
  }

  if (schemaType === 'array' && Array.isArray(value)) {
    const itemSchema = 'items' in schema ? schema.items : null;
    if (!itemSchema) return value;
    return value.map((item) => pruneValueToSchema(item, itemSchema));
  }

  return value;
}

export function coerceValue(
  value: JsonValue | null,
  expectedType: string | null,
): JsonValue | null {
  if (value === null || typeof value === 'undefined' || !expectedType) return value;

  switch (expectedType) {
    case 'number':
    case 'integer': {
      const num = Number(value);
      return Number.isNaN(num) ? value : expectedType === 'integer' ? Math.trunc(num) : num;
    }
    case 'boolean': {
      if (typeof value === 'boolean') return value;
      if (value === 'true') return true;
      if (value === 'false') return false;
      return value;
    }
    default:
      return value;
  }
}

export function getExpectedTypeForPath(
  migrations: Array<{ exampleId: string; path: string; expectedType?: string }>,
  exampleId: string,
  path: string,
): string | null {
  const migration = migrations.find((m) => m.exampleId === exampleId && m.path === path);
  if (!migration) {
    return null;
  }

  return migration.expectedType ?? null;
}
