import type { JsonObject, JsonValue } from '../types.js';

/* oxlint-disable eslint/no-use-before-define */

/**
 * Normalize a path to dot-separated format.
 * Converts `/customer/city` to `customer.city`.
 * Handles mixed formats like `/customer.address.0` or `customer/address/city`.
 */
export function normalizePath(path: string): string {
  return path.replace(/^\//, '').replace(/\/$/, '').replace(/\//g, '.');
}

/**
 * Read a nested value from an object using a dot-separated path.
 * Supports array indices as numeric segments (e.g., "items.0.name").
 */
export function getNestedValue(obj: JsonObject, path: string): JsonValue | undefined {
  if (!path) return;

  const segments = path.split('.');
  let current: unknown = obj;

  for (const segment of segments) {
    if (isNil(current)) return;

    if (Array.isArray(current)) {
      const index = toIndex(segment);
      if (index === null) return;
      current = current[index];
    } else if (isRecord(current)) {
      current = current[segment];
    } else {
      return;
    }
  }

  if (!isJsonValue(current)) {
    return;
  }

  return current;
}

/**
 * Immutably set a nested value in an object using a dot-separated path.
 * Creates intermediate objects/arrays as needed.
 */
export function setNestedValue(obj: JsonObject, path: string, value: JsonValue): JsonObject {
  if (!path) return obj;

  const segments = path.split('.');
  const updated = setAtPath(obj, segments, 0, value);
  return isJsonObject(updated) ? updated : obj;
}

/**
 * Immutably remove a nested value from an object using a dot-separated path.
 * Missing paths are ignored and return the original clone unchanged.
 */
export function deleteNestedValue(obj: JsonObject, path: string): JsonObject {
  if (!path) return structuredClone(obj);

  const segments = path.split('.');
  const updated = deleteAtPath(obj, segments, 0);
  return isJsonObject(updated) ? updated : obj;
}

/**
 * Convert a JSON Schema validation path (e.g., "$.users[0].email")
 * to the dot-separated form path used by the form renderer (e.g., "users.0.email").
 */
export function validationPathToFormPath(path: string): string {
  return path.replace(/^\$\./, '').replace(/\[(\d+)\]/g, '.$1');
}

function isNil(value: unknown): value is null | undefined {
  return value === null || typeof value === 'undefined';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isJsonObject(value: JsonValue): value is JsonObject {
  return isRecord(value) && !Array.isArray(value);
}

function isJsonValue(value: unknown): value is JsonValue {
  if (isNil(value)) {
    return true;
  }

  const valueType = typeof value;
  if (valueType === 'string' || valueType === 'number' || valueType === 'boolean') {
    return true;
  }

  if (Array.isArray(value)) {
    return value.every((item) => isJsonValue(item));
  }

  if (isRecord(value)) {
    return Object.values(value).every((item) => isJsonValue(item));
  }

  return false;
}

function isArrayIndexSegment(segment: string): boolean {
  return /^\d+$/.test(segment);
}

function toIndex(segment: string): number | null {
  const index = Number.parseInt(segment, 10);
  return Number.isNaN(index) ? null : index;
}

function createContainerFor(nextSegment: string): JsonValue {
  return isArrayIndexSegment(nextSegment) ? [] : {};
}

function cloneContainer(value: JsonValue): JsonValue {
  if (Array.isArray(value)) {
    return value.slice();
  }

  if (isJsonObject(value)) {
    return Object.assign({}, value);
  }

  return value;
}

function ensureContainer(value: JsonValue, nextSegment: string): JsonValue {
  if (Array.isArray(value) || isJsonObject(value)) {
    return cloneContainer(value);
  }

  return createContainerFor(nextSegment);
}

function setAtPath(
  current: JsonValue,
  segments: readonly string[],
  index: number,
  value: JsonValue,
): JsonValue {
  if (index >= segments.length) {
    return value;
  }

  const segment = segments[index];
  const nextSegment = index + 1 < segments.length ? segments[index + 1] : '';

  if (Array.isArray(current)) {
    const arrayIndex = toIndex(segment);
    if (arrayIndex === null || arrayIndex < 0) {
      return current;
    }

    const cloned = current.slice();
    const existing = arrayIndex < cloned.length ? cloned[arrayIndex] : null;
    const nextCurrent = ensureContainer(existing, nextSegment);
    cloned[arrayIndex] = setAtPath(nextCurrent, segments, index + 1, value);
    return cloned;
  }

  if (isJsonObject(current)) {
    const cloned = Object.assign({}, current);
    const hasSegment = Object.prototype.hasOwnProperty.call(cloned, segment);
    const existing = hasSegment ? cloned[segment] : null;
    const nextCurrent = ensureContainer(existing, nextSegment);
    cloned[segment] = setAtPath(nextCurrent, segments, index + 1, value);
    return cloned;
  }

  const fallback = createContainerFor(segment);
  return setAtPath(fallback, segments, index, value);
}

function deleteAtPath(current: JsonValue, segments: readonly string[], index: number): JsonValue {
  if (index >= segments.length) {
    return current;
  }

  const segment = segments[index];
  const isLeaf = index === segments.length - 1;

  if (Array.isArray(current)) {
    const arrayIndex = toIndex(segment);
    if (arrayIndex === null || arrayIndex < 0 || arrayIndex >= current.length) {
      return current;
    }

    const cloned = current.slice();
    if (isLeaf) {
      cloned.splice(arrayIndex, 1);
      return cloned;
    }

    const child = cloned[arrayIndex];
    if (!Array.isArray(child) && !isJsonObject(child)) {
      return current;
    }

    cloned[arrayIndex] = deleteAtPath(child, segments, index + 1);
    return cloned;
  }

  if (!isJsonObject(current)) {
    return current;
  }

  if (!Object.prototype.hasOwnProperty.call(current, segment)) {
    return current;
  }

  if (isLeaf) {
    return omitObjectKey(current, segment);
  }

  const child = current[segment];
  if (!Array.isArray(child) && !isJsonObject(child)) {
    return current;
  }

  const cloned = Object.assign({}, current);
  cloned[segment] = deleteAtPath(child, segments, index + 1);
  return cloned;
}

function omitObjectKey(obj: JsonObject, keyToRemove: string): JsonObject {
  const result: JsonObject = {};
  const entries = Object.entries(obj);

  for (const [key, value] of entries) {
    if (key !== keyToRemove) {
      result[key] = value;
    }
  }

  return result;
}
