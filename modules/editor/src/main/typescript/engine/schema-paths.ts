// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * JSON Schema field projection for expression autocomplete.
 *
 * Schema traversal is owned by schema-navigator. FieldPath is deliberately a
 * presentation type: iteration scopes retain logical schema cursors instead
 * of trying to navigate through flattened picker rows.
 */

import type { JsonSchemaNode } from '../json-schema/schema-resolution.js';
import { isObject, schemaProperties, schemaTypes } from '../json-schema/schema-node.js';
import {
  type SchemaCursor,
  describeSchemaCursor,
  itemCursor,
  propertyCursor,
  schemaRootCursor,
  schemaVariantsAt,
} from './schema-navigator.js';

export interface FieldPath {
  /** Dot-notation path, e.g. "customer.address.city" */
  path: string;
  /** JSON Schema type, with date formats collapsed for editor presentation. */
  type: string;
  /** Raw external `$ref` URL when retained by schema resolution. */
  ref?: string;
  /** Whether this is a system parameter (injected by the rendering engine). */
  system?: boolean;
  /** Human-readable description (used for system parameter tooltips). */
  description?: string;
  /** Scope alias (e.g. "item" or "params") — marks this as a scoped variable. */
  scope?: string;
  /** Classifies scoped variables for UI grouping. Missing means template data. */
  scopeKind?: 'iteration' | 'stencil-parameter';
  /** When true, this parameter is only available inside page headers/footers. */
  pageOnly?: boolean;
}

export interface ScopedFieldProjection {
  alias: string;
  scopeKind: NonNullable<FieldPath['scopeKind']>;
  description?: string;
  includeAlias: boolean;
}

const MAX_DEPTH = 5;

/** Extract root data fields from a canonical JSON Schema object. */
export function extractFieldPaths(schema: JsonSchemaNode): FieldPath[] {
  const fields = new Map<string, FieldPath>();
  walkProperties(schemaRootCursor(schema), '', 0, fields);
  return [...fields.values()];
}

/** Project one schema cursor under a scoped alias for expression autocomplete. */
export function projectScopedFieldPaths(
  cursor: SchemaCursor,
  projection: ScopedFieldProjection,
): FieldPath[] {
  const fields = new Map<string, FieldPath>();
  if (projection.includeAlias) {
    const descriptor = describeSchemaCursor(cursor);
    fields.set(projection.alias, scopedField(projection.alias, descriptor, projection));
  }
  walkContainer(cursor, projection.alias, 0, fields, projection);
  return [...fields.values()];
}

function walkProperties(
  cursor: SchemaCursor,
  prefix: string,
  depth: number,
  fields: Map<string, FieldPath>,
  projection?: ScopedFieldProjection,
): void {
  if (depth > MAX_DEPTH) return;

  const names = new Set<string>();
  for (const variant of schemaVariantsAt(cursor)) {
    for (const name of Object.keys(schemaProperties(variant.schema))) names.add(name);
  }

  for (const name of names) {
    const child = propertyCursor(cursor, name);
    if (!child) continue;
    const path = prefix ? `${prefix}.${name}` : name;
    addField(fields, path, describeSchemaCursor(child), projection);
    walkContainer(child, path, depth + 1, fields, projection);
  }
}

function walkContainer(
  cursor: SchemaCursor,
  path: string,
  depth: number,
  fields: Map<string, FieldPath>,
  projection?: ScopedFieldProjection,
): void {
  if (depth > MAX_DEPTH) return;

  const variants = schemaVariantsAt(cursor);
  const objectLike = variants.some(
    (variant) =>
      schemaTypes(variant.schema).includes('object') ||
      Object.keys(schemaProperties(variant.schema)).length > 0,
  );
  if (objectLike) walkProperties(cursor, path, depth, fields, projection);

  const arrayLike = variants.some(
    (variant) => schemaTypes(variant.schema).includes('array') || isObject(variant.schema.items),
  );
  if (!arrayLike) return;
  const items = itemCursor(cursor);
  if (items) walkContainer(items, `${path}[]`, depth, fields, projection);
}

function addField(
  fields: Map<string, FieldPath>,
  path: string,
  descriptor: { type: string; ref?: string },
  projection?: ScopedFieldProjection,
): void {
  const field = projection ? scopedField(path, descriptor, projection) : { path, ...descriptor };
  const existing = fields.get(path);
  if (!existing) {
    fields.set(path, field);
    return;
  }
  existing.type = mergeTypes(existing.type, field.type);
  if (!existing.ref && field.ref) existing.ref = field.ref;
}

function scopedField(
  path: string,
  descriptor: { type: string; ref?: string },
  projection: ScopedFieldProjection,
): FieldPath {
  return {
    path,
    ...descriptor,
    scope: projection.alias,
    scopeKind: projection.scopeKind,
    ...(path === projection.alias && projection.description
      ? { description: projection.description }
      : {}),
  };
}

function mergeTypes(left: string, right: string): string {
  if (left === right) return left;
  if ((left === 'integer' && right === 'number') || (left === 'number' && right === 'integer')) {
    return 'number';
  }
  return 'unknown';
}
