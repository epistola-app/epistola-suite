import type { JsonObject, JsonValue } from '../types.js';

/**
 * Normalize a path to dot-separated format.
 * Converts `/customer/city` to `customer.city`.
 * Handles mixed formats like `/customer.address.0` or `customer/address/city`.
 */
export function normalizePath(path: string): string {
  return path
    .replace(/^\//, '')
    .replace(/\/$/, '')
    .replace(/\//g, '.');
}

/**
 * Read a nested value from an object using a dot-separated path.
 * Supports array indices as numeric segments (e.g., "items.0.name").
 */
export function getNestedValue(obj: JsonObject, path: string): JsonValue | undefined {
  if (!path) return undefined;

  const segments = path.split('.');
  let current: unknown = obj;

  for (const segment of segments) {
    if (current === null || current === undefined) return undefined;

    if (Array.isArray(current)) {
      const index = Number.parseInt(segment, 10);
      if (Number.isNaN(index)) return undefined;
      current = current[index];
    } else if (typeof current === 'object') {
      current = (current as Record<string, unknown>)[segment];
    } else {
      return undefined;
    }
  }

  return current as JsonValue | undefined;
}

/**
 * Immutably set a nested value in an object using a dot-separated path.
 * Creates intermediate objects/arrays as needed.
 */
export function setNestedValue(obj: JsonObject, path: string, value: JsonValue): JsonObject {
  if (!path) return obj;

  const segments = path.split('.');
  const result = structuredClone(obj);
  let current: Record<string, unknown> = result;

  for (let i = 0; i < segments.length - 1; i++) {
    const segment = segments[i];
    const nextSegment = segments[i + 1];
    const isNextIndex = /^\d+$/.test(nextSegment);

    if (!(segment in current) || current[segment] === null || current[segment] === undefined) {
      current[segment] = isNextIndex ? [] : {};
    }

    if (Array.isArray(current[segment])) {
      current[segment] = [...(current[segment] as unknown[])];
    } else if (typeof current[segment] === 'object') {
      current[segment] = { ...(current[segment] as Record<string, unknown>) };
    }

    current = current[segment] as Record<string, unknown>;
  }

  const lastSegment = segments[segments.length - 1];
  current[lastSegment] = value;

  return result;
}

/**
 * Immutably remove a nested value from an object using a dot-separated path.
 * Missing paths are ignored and return the original clone unchanged.
 */
export function deleteNestedValue(obj: JsonObject, path: string): JsonObject {
  if (!path) return structuredClone(obj);

  const segments = path.split('.');
  const result = structuredClone(obj) as Record<string, unknown>;
  let current: unknown = result;

  for (let i = 0; i < segments.length - 1; i++) {
    const segment = segments[i];

    if (Array.isArray(current)) {
      const index = Number.parseInt(segment, 10);
      if (Number.isNaN(index) || index < 0 || index >= current.length) {
        return result as JsonObject;
      }

      const next = current[index];
      if (next === null || next === undefined || typeof next !== 'object') {
        return result as JsonObject;
      }

      current[index] = Array.isArray(next) ? [...next] : { ...(next as Record<string, unknown>) };
      current = current[index];
      continue;
    }

    if (current === null || current === undefined || typeof current !== 'object') {
      return result as JsonObject;
    }

    const record = current as Record<string, unknown>;
    if (!(segment in record)) {
      return result as JsonObject;
    }

    const next = record[segment];
    if (next === null || next === undefined || typeof next !== 'object') {
      return result as JsonObject;
    }

    record[segment] = Array.isArray(next) ? [...next] : { ...next };
    current = record[segment];
  }

  const lastSegment = segments[segments.length - 1];

  if (Array.isArray(current)) {
    const index = Number.parseInt(lastSegment, 10);
    if (Number.isNaN(index) || index < 0 || index >= current.length) {
      return result as JsonObject;
    }
    current.splice(index, 1);
    return result as JsonObject;
  }

  if (current !== null && current !== undefined && typeof current === 'object') {
    const record = current as Record<string, unknown>;
    delete record[lastSegment];
  }

  return result as JsonObject;
}

/**
 * Convert a JSON Schema validation path (e.g., "$.users[0].email")
 * to the dot-separated form path used by the form renderer (e.g., "users.0.email").
 */
export function validationPathToFormPath(path: string): string {
  return path.replace(/^\$\./, '').replace(/\[(\d+)\]/g, '.$1');
}
