// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * JSON Schema → field path extractor.
 *
 * Resolves a JSON Schema and walks its properties recursively, returning
 * dot-notation paths suitable for expression autocomplete. Local references,
 * compositions, unions, nullable types, and nested arrays are supported.
 *
 * Stays domain-agnostic: every path carries the raw JSON Schema `type`
 * (with `string + format: date` collapsed to `'date'` and `string + format:
 * date-time` to `'datetime'`, because that's how the editor surfaces the
 * field) and, when present, the raw `$ref` URL. Callers
 * that care about a specific `$ref` (e.g. rich-text schemas) classify it at
 * their layer rather than baking that knowledge in here.
 */

import { scalarFromJsonSchema } from '../json-schema/scalar-type.js';
import {
  type JsonSchemaNode,
  type ResolvedSchemaVariant,
  resolveSchemaVariants,
} from '../json-schema/schema-resolution.js';

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
 * Walks all effective `properties` recursively (up to MAX_DEPTH levels).
 * For arrays with `items`, appends `[]` at each array level and continues into
 * object items. Union branches are deduplicated in declaration order.
 */
export function extractFieldPaths(schema: JsonSchemaNode): FieldPath[] {
  const fields = new Map<string, FieldPath>();
  for (const variant of resolveSchemaVariants(schema, schema)) {
    walkProperties(variant, schema, '', 0, fields);
  }
  return [...fields.values()];
}

function walkProperties(
  variant: ResolvedSchemaVariant,
  rootSchema: JsonSchemaNode,
  prefix: string,
  depth: number,
  fields: Map<string, FieldPath>,
): void {
  if (depth > MAX_DEPTH) return;

  const properties = objectRecord(variant.schema.properties);

  for (const [key, propSchema] of Object.entries(properties)) {
    const path = prefix ? `${prefix}.${key}` : key;
    const resolvedPropertyVariants = resolveSchemaVariants(
      propSchema,
      rootSchema,
      variant.resolvingRefs,
    );
    const nonNullVariants = resolvedPropertyVariants.filter(
      (propertyVariant) => !isNullOnly(propertyVariant.schema),
    );
    const propertyVariants =
      nonNullVariants.length > 0 ? nonNullVariants : resolvedPropertyVariants;

    for (const propertyVariant of propertyVariants) {
      addField(fields, path, propertyVariant.schema);
      walkContainer(propertyVariant, rootSchema, path, depth + 1, fields);
    }
  }
}

function walkContainer(
  variant: ResolvedSchemaVariant,
  rootSchema: JsonSchemaNode,
  path: string,
  depth: number,
  fields: Map<string, FieldPath>,
): void {
  if (depth > MAX_DEPTH) return;

  const types = schemaTypes(variant.schema);
  const objectLike =
    types.includes('object') || Object.keys(objectRecord(variant.schema.properties)).length > 0;
  if (objectLike) {
    walkProperties(variant, rootSchema, path, depth, fields);
  }

  const arrayLike = types.includes('array') || isObject(variant.schema.items);
  if (!arrayLike || !isObject(variant.schema.items)) return;

  for (const itemVariant of resolveSchemaVariants(
    variant.schema.items,
    rootSchema,
    variant.resolvingRefs,
  )) {
    walkContainer(itemVariant, rootSchema, `${path}[]`, depth, fields);
  }
}

function addField(fields: Map<string, FieldPath>, path: string, schema: JsonSchemaNode): void {
  const type = displayType(schema);
  const ref = typeof schema.$ref === 'string' ? schema.$ref : undefined;
  const existing = fields.get(path);

  if (!existing) {
    fields.set(path, ref ? { path, type, ref } : { path, type });
    return;
  }

  existing.type = mergeTypes(existing.type, type);
  if (!existing.ref && ref) existing.ref = ref;
}

function displayType(schema: JsonSchemaNode): string {
  const types = schemaTypes(schema).filter((type) => type !== 'null');
  let type = types[0] ?? inferType(schema);
  for (const candidate of types.slice(1)) type = mergeTypes(type, candidate);

  const format = typeof schema.format === 'string' ? schema.format : undefined;
  return scalarFromJsonSchema(type, format) ?? type;
}

function inferType(schema: JsonSchemaNode): string {
  if (Object.keys(objectRecord(schema.properties)).length > 0) return 'object';
  if (isObject(schema.items)) return 'array';
  return 'unknown';
}

function mergeTypes(left: string, right: string): string {
  if (left === right) return left;
  if ((left === 'integer' && right === 'number') || (left === 'number' && right === 'integer')) {
    return 'number';
  }
  return 'unknown';
}

function isNullOnly(schema: JsonSchemaNode): boolean {
  const types = schemaTypes(schema);
  return types.length === 1 && types[0] === 'null';
}

function schemaTypes(schema: JsonSchemaNode): string[] {
  if (typeof schema.type === 'string') return [schema.type];
  return Array.isArray(schema.type)
    ? schema.type.filter((type): type is string => typeof type === 'string')
    : [];
}

function objectRecord(value: unknown): Record<string, JsonSchemaNode> {
  if (!isObject(value)) return {};
  return Object.fromEntries(
    Object.entries(value).filter((entry): entry is [string, JsonSchemaNode] => isObject(entry[1])),
  );
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
