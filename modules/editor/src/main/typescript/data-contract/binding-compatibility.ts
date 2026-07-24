// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Predicates that decide whether a contract field path may be bound at a
 * specific consumption surface, plus the display-time helper that maps a
 * field path's raw `$ref` URL to a friendly type label.
 *
 * Lives in the data-contract layer because it knows about the ref-type
 * registry. The engine layer (`engine/`) stays domain-agnostic and just
 * transports `FieldPath.ref` through to the consumer; consumers call into
 * here when they need to interpret the URL.
 *
 * Each predicate has shape `(fp: FieldPath) => string | null`:
 *   - returns `null` when the path is allowed
 *   - returns a short reason (used as a tooltip on a disabled list item)
 *     when the path is incompatible
 */

import type { FieldPath } from '../engine/schema-paths.js';
import { classifyValue, findRefType, isAnyRefType, isRefType } from './ref-types.js';
import { fieldTypeLabel } from './field-types.js';
import type { SchemaFieldType } from './types.js';

/** Predicate signature consumed by the expression dialog. */
export type PathDisabledPredicate = (fp: FieldPath) => string | null;

/**
 * Inline expression chip: accepts scalars + `richTextInline`. Rejects
 * `richTextBlock` (use a Rich Text Variable component instead) and
 * containers (`array`, `object`).
 */
export const inlineExpressionPathDisabled: PathDisabledPredicate = (fp) => {
  if (isRefType(fp.ref, 'richTextBlock')) {
    return 'This is a Rich text (block) field — drop a Rich Text Variable component to bind it instead of an inline expression.';
  }
  if (fp.type === 'array' || fp.type === 'object') {
    return `Inline expressions cannot bind to ${fp.type} fields directly.`;
  }
  return null;
};

/**
 * Block-level Rich Text Variable: accepts only rich-text fields
 * (`richTextInline` or `richTextBlock`). Everything else is rejected.
 */
export const richTextVariablePathDisabled: PathDisabledPredicate = (fp) => {
  if (isAnyRefType(fp.ref, ['richTextInline', 'richTextBlock'])) {
    return null;
  }
  return 'A Rich Text Variable can only be bound to a Rich text field. Use a Text component with an inline expression for plain values.';
};

/**
 * Display-side helper: friendly label for the type column of the expression
 * dialog (autocomplete list and builder-mode `<option>` data-type). Maps
 * known `$ref` URLs to their registered `label`; falls back to the raw
 * `FieldPath.type` for everything else.
 */
export function formatFieldPathTypeLabel(fp: FieldPath): string {
  return findRefType(fp.ref)?.label ?? fieldTypeLabel(fp.type as SchemaFieldType);
}

/**
 * Extract a readable plain-text preview from a resolved expression value.
 * For rich-text ProseMirror docs, walks paragraphs → text nodes and joins
 * their text content. Returns `null` when the value isn't a registered ref
 * type, so the caller can fall back to its generic formatter
 * (`formatForPreview`).
 */
export function formatBindingPreview(value: unknown): string | null {
  const refType = classifyValue(value);
  if (refType === null) return null;

  const doc = value as { content?: unknown };
  if (!Array.isArray(doc.content)) return '(empty)';
  const paragraphs: string[] = [];
  for (const block of doc.content) {
    if (!block || typeof block !== 'object') continue;
    const b = block as { type?: string; content?: unknown[] };
    if (b.type !== 'paragraph' || !Array.isArray(b.content)) continue;
    const texts: string[] = [];
    for (const node of b.content) {
      if (!node || typeof node !== 'object') continue;
      const n = node as { type?: string; text?: string };
      if (n.type === 'text' && n.text) texts.push(n.text);
      else if (n.type === 'hard_break') texts.push(' ');
    }
    if (texts.length > 0) paragraphs.push(texts.join(''));
  }
  return paragraphs.length > 0 ? paragraphs.join(' ') : '(empty)';
}
