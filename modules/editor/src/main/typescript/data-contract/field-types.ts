/**
 * Single source of truth for the editor's field/data types — their UI names,
 * their JSON Schema mapping, and which surfaces may use them.
 *
 * The data-contract editor offers the **full** set; the stencil-parameter
 * editor offers the **subset** flagged `stencilParam` (the scalar primitives).
 * Adding a future data type is a single entry here (plus widening the
 * `SchemaFieldType` union in `types.ts`) — not a tour through every dropdown,
 * badge and serializer. UI naming lives here too, so the two editors can never
 * drift apart again.
 *
 * Three `kind`s, by how they serialize to JSON Schema:
 *   - `scalar`    — `{ type, format? }` (string/number/integer/boolean/date/date-time).
 *   - `ref`       — a `$ref` to a canonical schema URL; the specifics live in
 *                   `ref-types.ts` (rich text today). Entries here are derived
 *                   from that registry so labels stay in one place.
 *   - `container` — `array` / `object`, which carry nested `items`/`properties`.
 */

import { REF_TYPES } from './ref-types.js';
import type { SchemaFieldType } from './types.js';

/** The scalar (non-container, non-ref) subset of `SchemaFieldType`. */
export type ScalarFieldType = Extract<
  SchemaFieldType,
  'string' | 'number' | 'integer' | 'boolean' | 'date' | 'datetime'
>;

/** JSON Schema primitive `type` keyword a scalar maps to. */
export type JsonScalarType = 'string' | 'number' | 'integer' | 'boolean';

/** JSON Schema `format` for the date-ish scalars. */
export type JsonDateFormat = 'date' | 'date-time';

export interface FieldTypeDef {
  /** Canonical id — the value stored in `SchemaField.type` / a stencil param row. */
  id: SchemaFieldType;
  /** Human-readable UI name shown in every dropdown and type badge. */
  label: string;
  /** How this type serializes to JSON Schema. */
  kind: 'scalar' | 'ref' | 'container';
  /** JSON Schema mapping — present only for `kind === 'scalar'`. */
  json?: { type: JsonScalarType; format?: JsonDateFormat };
  /** Selectable as a top-level data-contract field type. */
  contractField: boolean;
  /** Selectable as a data-contract array item type. */
  arrayItem: boolean;
  /** Selectable as a stencil parameter type (the shared scalar subset). */
  stencilParam: boolean;
}

/** The scalar primitives, in display order. Shared by both editors. */
const SCALAR_DEFS: readonly FieldTypeDef[] = [
  {
    id: 'string',
    label: 'Text',
    kind: 'scalar',
    json: { type: 'string' },
    contractField: true,
    arrayItem: true,
    stencilParam: true,
  },
  {
    id: 'number',
    label: 'Number',
    kind: 'scalar',
    json: { type: 'number' },
    contractField: true,
    arrayItem: true,
    stencilParam: true,
  },
  {
    id: 'integer',
    label: 'Integer',
    kind: 'scalar',
    json: { type: 'integer' },
    contractField: true,
    arrayItem: true,
    stencilParam: true,
  },
  {
    id: 'boolean',
    label: 'Yes/No',
    kind: 'scalar',
    json: { type: 'boolean' },
    contractField: true,
    arrayItem: true,
    stencilParam: true,
  },
  {
    id: 'date',
    label: 'Date',
    kind: 'scalar',
    json: { type: 'string', format: 'date' },
    contractField: true,
    arrayItem: true,
    stencilParam: true,
  },
  {
    id: 'datetime',
    label: 'Date-time',
    kind: 'scalar',
    json: { type: 'string', format: 'date-time' },
    contractField: true,
    arrayItem: true,
    stencilParam: true,
  },
];

/**
 * The full registry: scalars, then the ref types (rich text), then containers.
 * Order is the dropdown order in the data-contract editor.
 */
export const FIELD_TYPE_DEFS: readonly FieldTypeDef[] = [
  ...SCALAR_DEFS,
  ...REF_TYPES.map(
    (r): FieldTypeDef => ({
      id: r.id,
      label: r.label,
      kind: 'ref',
      contractField: true,
      arrayItem: true,
      stencilParam: false,
    }),
  ),
  {
    id: 'array',
    label: 'List',
    kind: 'container',
    contractField: true,
    arrayItem: false,
    stencilParam: false,
  },
  {
    id: 'object',
    label: 'Object',
    kind: 'container',
    contractField: true,
    arrayItem: true,
    stencilParam: false,
  },
];

const DEF_BY_ID = new Map<SchemaFieldType, FieldTypeDef>(FIELD_TYPE_DEFS.map((d) => [d.id, d]));

/** UI label for any field type. */
export function fieldTypeLabel(id: SchemaFieldType): string {
  return DEF_BY_ID.get(id)?.label ?? id;
}

/** Map of every field type to its UI label. */
export const FIELD_TYPE_LABELS: Record<SchemaFieldType, string> = Object.fromEntries(
  FIELD_TYPE_DEFS.map((d) => [d.id, d.label]),
) as Record<SchemaFieldType, string>;

/** True when `id` is one of the scalar primitives. */
export function isScalarFieldType(id: string): id is ScalarFieldType {
  return DEF_BY_ID.get(id as SchemaFieldType)?.kind === 'scalar';
}

/** Serialize a scalar field type to its JSON Schema `{ type, format? }`. */
export function scalarToJsonSchema(id: ScalarFieldType): {
  type: JsonScalarType;
  format?: JsonDateFormat;
} {
  const json = DEF_BY_ID.get(id)?.json;
  if (!json) throw new Error(`Not a scalar field type: ${id}`);
  return json.format ? { type: json.type, format: json.format } : { type: json.type };
}

/**
 * Resolve a JSON Schema `{ type, format? }` back to a scalar field type id, or
 * `null` when it isn't one of the registered scalars (e.g. a `string` with an
 * `email`/`uri` format, or a container type — handled by the caller).
 */
export function scalarFromJsonSchema(
  jsonType: string | undefined,
  format: string | undefined,
): ScalarFieldType | null {
  for (const d of FIELD_TYPE_DEFS) {
    if (d.kind !== 'scalar' || !d.json) continue;
    if (d.json.type === jsonType && (d.json.format ?? undefined) === (format ?? undefined)) {
      return d.id as ScalarFieldType;
    }
  }
  return null;
}

/** Field types selectable as top-level data-contract fields, in display order. */
export const CONTRACT_FIELD_TYPES: SchemaFieldType[] = FIELD_TYPE_DEFS.filter(
  (d) => d.contractField,
).map((d) => d.id);

/** Field types selectable as data-contract array item types, in display order. */
export const ARRAY_ITEM_FIELD_TYPES: SchemaFieldType[] = FIELD_TYPE_DEFS.filter(
  (d) => d.arrayItem,
).map((d) => d.id);

/** Scalar types a stencil parameter may use (the subset), in display order. */
export const STENCIL_PARAM_TYPES: readonly FieldTypeDef[] = FIELD_TYPE_DEFS.filter(
  (d) => d.stencilParam,
);
