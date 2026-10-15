// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Helpers for reading and writing nested property values.
 *
 * Used by the inspector to access props like "condition.raw" via dot-notation paths.
 */

export function getNestedValue(obj: Record<string, unknown>, path: string): unknown {
  const keys = path.split('.');
  let current: unknown = obj;
  for (const key of keys) {
    if (current == null || typeof current !== 'object') return undefined;
    current = (current as Record<string, unknown>)[key];
  }
  return current;
}

/**
 * Writes only through own properties: an inherited name such as `constructor` becomes a plain own
 * key, and a path naming `__proto__` is ignored, so no path can reach `Object.prototype`.
 */
export function setNestedValue(obj: Record<string, unknown>, path: string, value: unknown): void {
  const keys = path.split('.');
  if (keys.includes('__proto__')) return;
  let current: Record<string, unknown> = obj;
  for (let i = 0; i < keys.length - 1; i++) {
    const key = keys[i];
    if (!Object.hasOwn(current, key) || typeof current[key] !== 'object' || current[key] == null) {
      current[key] = {};
    }
    current = current[key] as Record<string, unknown>;
  }
  current[keys[keys.length - 1]] = value;
}
