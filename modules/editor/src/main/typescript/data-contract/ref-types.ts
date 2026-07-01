/**
 * Registry of "reference types" — JSON Schema `$ref` URLs the editor
 * recognises as logical field types. Today: rich-text inline + block. Adding a
 * future type (signature, address, phone, etc.) is a single `REF_TYPES` entry,
 * not a code-change tour.
 *
 * Each entry carries:
 *   - the canonical URL (also the JSON Schema $id);
 *   - a stable logical id used as a `SchemaFieldType` discriminant;
 *   - a human-readable label for the field-type badge / dialog type column;
 *   - a default-value factory used when a field of this type is freshly created;
 *   - a frontend-only shallow shape recogniser used by the simplified
 *     example-data validator and by `inferType`. The backend validator does the
 *     rigorous deep check via the IRI.
 *
 * The engine layer (`engine/`) stays domain-agnostic: it just transports a
 * raw `$ref` string on `FieldPath`. Consumers that care about the URL look up
 * its meaning here.
 */

import { EMPTY_RICH_TEXT_BLOCK_DOC } from '../prosemirror/richTextBlockSchema.js';
import { EMPTY_RICH_TEXT_INLINE_DOC } from '../prosemirror/richTextInlineSchema.js';
import {
  RICH_TEXT_BLOCK_SCHEMA_REF,
  RICH_TEXT_INLINE_SCHEMA_REF,
  type JsonObject,
  type JsonValue,
} from './types.js';

/** Logical id of a registered reference type (kept narrow on purpose). */
export type RefTypeId = 'richTextInline' | 'richTextBlock';

export interface RefType {
  id: RefTypeId;
  url: string;
  label: string;
  defaultValue: () => JsonValue;
  shallowShapeCheck: (value: unknown) => string | null;
}

const inlineDocCheck = (value: unknown): string | null => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return 'must be an inline rich-text document';
  }
  if ((value as JsonObject).type !== 'doc') {
    return 'must be an inline rich-text document';
  }
  const content = (value as JsonObject).content;
  if (!Array.isArray(content) || content.length !== 1) {
    return 'inline rich text must contain exactly one paragraph';
  }
  const only = content[0];
  if (
    only === null ||
    typeof only !== 'object' ||
    Array.isArray(only) ||
    only.type !== 'paragraph'
  ) {
    return 'inline rich text must contain a single paragraph node';
  }
  return null;
};

const blockDocCheck = (value: unknown): string | null => {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return 'must be a rich-text document';
  }
  if ((value as JsonObject).type !== 'doc') {
    return 'must be a rich-text document';
  }
  return null;
};

export const REF_TYPES: ReadonlyArray<RefType> = [
  {
    id: 'richTextInline',
    url: RICH_TEXT_INLINE_SCHEMA_REF,
    label: 'Rich text (inline)',
    defaultValue: () => ({ ...(EMPTY_RICH_TEXT_INLINE_DOC as unknown as JsonObject) }),
    shallowShapeCheck: inlineDocCheck,
  },
  {
    id: 'richTextBlock',
    url: RICH_TEXT_BLOCK_SCHEMA_REF,
    label: 'Rich text (block)',
    defaultValue: () => ({ ...(EMPTY_RICH_TEXT_BLOCK_DOC as unknown as JsonObject) }),
    shallowShapeCheck: blockDocCheck,
  },
];

/** Lookup by canonical URL. Returns null for unknown / undefined urls. */
export function findRefType(url: string | undefined): RefType | null {
  if (!url) return null;
  return REF_TYPES.find((t) => t.url === url) ?? null;
}

/** Lookup by logical id; throws when the id isn't registered (programmer error). */
export function getRefTypeById(id: RefTypeId): RefType {
  const t = REF_TYPES.find((t) => t.id === id);
  if (!t) throw new Error(`Unknown ref type id: ${id}`);
  return t;
}

/** True when `url` resolves to the registered type with the given id. */
export function isRefType(url: string | undefined, id: RefTypeId): boolean {
  return findRefType(url)?.id === id;
}

/** True when `url` resolves to *any* of the listed registered types. */
export function isAnyRefType(url: string | undefined, ids: ReadonlyArray<RefTypeId>): boolean {
  const t = findRefType(url);
  return t !== null && ids.includes(t.id);
}

/**
 * Find the first registered type whose `shallowShapeCheck` accepts `value`.
 * Used by `inferType` to classify raw values into rich-text subtypes when
 * generating a schema from data.
 */
export function classifyValue(value: unknown): RefType | null {
  for (const t of REF_TYPES) {
    if (t.shallowShapeCheck(value) === null) return t;
  }
  return null;
}
