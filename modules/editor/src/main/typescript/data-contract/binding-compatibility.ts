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
  return findRefType(fp.ref)?.label ?? fp.type;
}

/**
 * Display-side helper for the expression dialog's resolved-value preview.
 * For values that match a registered ref type (e.g. a rich-text doc) the raw
 * JSON is unhelpful — render a stable placeholder instead so authors know
 * the binding resolved without seeing a wall of ProseMirror JSON. Returns
 * `null` when the value isn't a ref-shaped value; the caller then falls back
 * to its generic formatter (`formatForPreview`).
 */
export function formatBindingPreviewPlaceholder(value: unknown): string | null {
  return classifyValue(value) !== null ? '[rich text value]' : null;
}
