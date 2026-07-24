// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * JSON Schema → field path extractor.
 *
 * Walks a JSON Schema's `properties` recursively and returns
 * dot-notation paths suitable for expression autocomplete.
 *
 * Stays domain-agnostic: every path carries the raw JSON Schema `type`
 * (with `string + format: date` collapsed to `'date'` and `string + format:
 * date-time` to `'datetime'`, because that's how the editor surfaces the
 * field) and, when present, the raw `$ref` URL. Callers
 * that care about a specific `$ref` (e.g. rich-text schemas) classify it at
 * their layer rather than baking that knowledge in here.
 */

import { scalarFromJsonSchema } from '../data-contract/field-types.js';

export interface FieldPath {
  /** Dot-notation path, e.g. "customer.address.city" */
  path: string;
  /** JSON Schema `type` at this path (`'date'`/`'datetime'` for `string + format: date`/`date-time`, `'unknown'` if absent). */
  type: string;
  /**
   * Raw `$ref` URL when the field is declared by reference (e.g. a rich-text
   * field). Domain-specific consumers map known URLs to logical types.
   */
  ref?: string;
  /** Whether this is a system parameter (injected by the rendering engine). */
  system?: boolean;
  /** Human-readable description (used for system parameter tooltips). */
  description?: string;
  /** Scope alias (e.g., "item" or "params") — marks this as a scoped variable. */
  scope?: string;
  /** Classifies scoped variables for UI grouping. Missing means legacy iteration scope. */
  scopeKind?: 'iteration' | 'stencil-parameter';
  /** When true, this parameter is only available inside page headers/footers. */
  pageOnly?: boolean;
}

const MAX_DEPTH = 5;

/**
 * Extract field paths from a JSON Schema object.
 *
 * Walks `properties` recursively (up to MAX_DEPTH levels).
 * For arrays with `items`, appends `[]` and continues into
 * the items schema if it has properties.
 */
export function extractFieldPaths(schema: object): FieldPath[] {
  const result: FieldPath[] = [];
  walk(schema as Record<string, unknown>, '', 0, result);
  return result;
}

function walk(
  schema: Record<string, unknown>,
  prefix: string,
  depth: number,
  result: FieldPath[],
): void {
  if (depth > MAX_DEPTH) return;

  const properties = schema.properties as Record<string, Record<string, unknown>> | undefined;
  if (!properties || typeof properties !== 'object') return;

  for (const [key, propSchema] of Object.entries(properties)) {
    if (!propSchema || typeof propSchema !== 'object') continue;

    const path = prefix ? `${prefix}.${key}` : key;
    const ref = typeof propSchema.$ref === 'string' ? propSchema.$ref : undefined;
    const rawType = String(propSchema.type ?? (ref ? 'unknown' : 'unknown'));
    const format = typeof propSchema.format === 'string' ? propSchema.format : undefined;
    // Collapse `string + format: date`/`date-time` to `'date'`/`'datetime'` via
    // the shared registry; everything else keeps its raw JSON Schema type.
    const type = scalarFromJsonSchema(rawType, format) ?? rawType;

    result.push(ref ? { path, type, ref } : { path, type });

    if (type === 'object') {
      walk(propSchema, path, depth + 1, result);
    } else if (type === 'array') {
      const items = propSchema.items as Record<string, unknown> | undefined;
      if (items && typeof items === 'object') {
        const itemType = String(items.type ?? 'unknown');
        const arrayPath = `${path}[]`;
        if (itemType === 'object') {
          walk(items, arrayPath, depth + 1, result);
        }
      }
    }
  }
}
