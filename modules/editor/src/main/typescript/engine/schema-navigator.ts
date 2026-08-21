// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import {
  type JsonSchemaNode,
  isObject,
  schemaProperties,
  schemaTypes,
} from '../json-schema/schema-node.js';
import {
  MAX_RESOLVED_SCHEMA_VARIANTS,
  type ResolvedSchemaVariant,
  resolveSchemaVariants,
} from '../json-schema/schema-resolution.js';
import { scalarFromJsonSchema } from '../json-schema/scalar-type.js';

export type SchemaCursorSegment = { kind: 'property'; name: string } | { kind: 'items' };

interface SchemaSource {
  root: JsonSchemaNode;
  variantsByPath: Map<string, ResolvedSchemaVariant[]>;
}

/** Opaque logical location inside one canonical JSON Schema root. */
export interface SchemaCursor {
  readonly source: SchemaSource;
  readonly segments: readonly SchemaCursorSegment[];
}

export type SchemaBindings = Readonly<Record<string, SchemaCursor>>;

export interface SchemaCursorDescription {
  type: string;
  ref?: string;
}

const sources = new WeakMap<JsonSchemaNode, SchemaSource>();
const SIMPLE_PATH = /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/;

/** Return the shared root cursor for a schema object. */
export function schemaRootCursor(root: JsonSchemaNode): SchemaCursor {
  let source = sources.get(root);
  if (!source) {
    source = { root, variantsByPath: new Map() };
    sources.set(root, source);
  }
  return { source, segments: [] };
}

/** Resolve a simple dotted data expression from aliases first, then the data root. */
export function resolveSchemaExpression(
  expression: string,
  dataRoot: SchemaCursor | undefined,
  bindings: SchemaBindings,
): SchemaCursor | null {
  const trimmed = expression.trim();
  if (!SIMPLE_PATH.test(trimmed)) return null;

  const names = trimmed.split('.');
  const bound = bindings[names[0]];
  let cursor = bound ?? dataRoot;
  const offset = bound ? 1 : 0;
  if (!cursor) return null;

  for (const name of names.slice(offset)) {
    const next = propertyCursor(cursor, name);
    if (!next) return null;
    cursor = next;
  }
  return cursor;
}

/** Resolve a property relative to an object-like cursor. */
export function propertyCursor(cursor: SchemaCursor, name: string): SchemaCursor | null {
  const exists = schemaVariantsAt(cursor).some(
    (variant) => schemaProperties(variant.schema)[name] !== undefined,
  );
  return exists ? append(cursor, { kind: 'property', name }) : null;
}

/** Resolve the item schema relative to an array-like cursor. */
export function itemCursor(cursor: SchemaCursor): SchemaCursor | null {
  const exists = schemaVariantsAt(cursor).some((variant) => isObject(variant.schema.items));
  return exists ? append(cursor, { kind: 'items' }) : null;
}

/** All effective schema variants at a logical cursor. Results are cached per root/path. */
export function schemaVariantsAt(cursor: SchemaCursor): ResolvedSchemaVariant[] {
  const key = cursorKey(cursor.segments);
  const cached = cursor.source.variantsByPath.get(key);
  if (cached) return cached;

  let variants = resolveSchemaVariants(cursor.source.root, cursor.source.root);
  for (const segment of cursor.segments) {
    const next: ResolvedSchemaVariant[] = [];
    const seen = new Set<string>();
    for (const variant of variants) {
      const child =
        segment.kind === 'property'
          ? schemaProperties(variant.schema)[segment.name]
          : isObject(variant.schema.items)
            ? variant.schema.items
            : undefined;
      if (!child) continue;

      for (const resolved of resolveSchemaVariants(
        child,
        cursor.source.root,
        variant.resolvingRefs,
      )) {
        const variantKey = `${JSON.stringify(resolved.schema)}|${[
          ...resolved.resolvingRefs,
        ].toSorted()}`;
        if (seen.has(variantKey)) continue;
        seen.add(variantKey);
        next.push(resolved);
        if (next.length >= MAX_RESOLVED_SCHEMA_VARIANTS) break;
      }
      if (next.length >= MAX_RESOLVED_SCHEMA_VARIANTS) break;
    }
    variants = next;
    if (variants.length === 0) break;
  }

  cursor.source.variantsByPath.set(key, variants);
  return variants;
}

/** Stable display metadata merged across every effective variant at a cursor. */
export function describeSchemaCursor(cursor: SchemaCursor): SchemaCursorDescription {
  const variants = schemaVariantsAt(cursor).filter((variant) => !isNullOnly(variant.schema));
  if (variants.length === 0) return { type: 'unknown' };

  let type = displayType(variants[0].schema);
  for (const variant of variants.slice(1)) type = mergeTypes(type, displayType(variant.schema));

  const refs = [
    ...new Set(
      variants
        .map((variant) => variant.schema.$ref)
        .filter((ref): ref is string => typeof ref === 'string'),
    ),
  ];
  return { type, ...(refs.length === 1 ? { ref: refs[0] } : {}) };
}

function append(cursor: SchemaCursor, segment: SchemaCursorSegment): SchemaCursor {
  return { source: cursor.source, segments: [...cursor.segments, segment] };
}

function cursorKey(segments: readonly SchemaCursorSegment[]): string {
  return segments
    .map((segment) => (segment.kind === 'property' ? `p:${JSON.stringify(segment.name)}` : 'i'))
    .join('/');
}

function displayType(schema: JsonSchemaNode): string {
  const types = schemaTypes(schema).filter((type) => type !== 'null');
  let type = types[0] ?? inferType(schema);
  for (const candidate of types.slice(1)) type = mergeTypes(type, candidate);
  return scalarFromJsonSchema(type, schema.format) ?? type;
}

function inferType(schema: JsonSchemaNode): string {
  if (Object.keys(schemaProperties(schema)).length > 0) return 'object';
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
