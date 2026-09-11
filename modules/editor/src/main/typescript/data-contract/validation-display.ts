// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Shared helpers for turning a flat `ValidationError[]` into the structures
 * the various data-contract forms render inline errors from. Generic over
 * any path+message source — the Examples form and the Schema Definition form
 * both use these, so neither owns them.
 */

import type { ValidationError } from './types.js';

/**
 * Convert a JSON Schema validation path (e.g., "$.users[0].email")
 * to the dot-separated form path used by the form renderer (e.g., "users.0.email").
 */
export function validationPathToFormPath(path: string): string {
  return path
    .replace(/^\$\./, '') // strip leading "$."
    .replace(/\[(\d+)\]/g, '.$1'); // convert [0] to .0
}

/**
 * Build a Map<formPath, errorMessage> from validation errors.
 * Used to drive inline error indicators on form fields.
 */
export function buildFieldErrorMap(errors: ValidationError[]): Map<string, string> {
  const map = new Map<string, string>();
  for (const err of errors) {
    const formPath = validationPathToFormPath(err.path);
    // Keep the first error per path (most relevant)
    if (!map.has(formPath)) {
      map.set(formPath, err.message);
    }
  }
  return map;
}

/**
 * Count distinct field paths in a validation error list — matches the number
 * of inline indicators `buildFieldErrorMap` produces, so badges/summaries
 * built from this agree with what's actually highlighted when multiple
 * errors land on the same path (e.g. a contradictory minItems > maxItems
 * schema can report both bounds for one array field).
 */
export function countDistinctErrorPaths(errors: ValidationError[]): number {
  return buildFieldErrorMap(errors).size;
}

/**
 * Check if any error path starts with the given prefix.
 * Used to show red dots on collapsed groups containing errors.
 */
export function hasChildErrors(parentPath: string, errors: Map<string, string>): boolean {
  const prefix = parentPath + '.';
  for (const key of errors.keys()) {
    if (key === parentPath || key.startsWith(prefix)) return true;
  }
  return false;
}

export function pathToErrorId(path: string): string {
  return `error-${path.replace(/\./g, '-')}`;
}

export function fieldIdFromPath(path: string): string {
  return `dc-field-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
}
