// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Reusable expression dialog for editing JSONata expressions.
 *
 * Extracted from ExpressionNodeView to be shared between:
 * - Inline expression chips (ProseMirror nodes)
 * - Inspector expression fields (conditional, loop)
 *
 * Features:
 * - Field path autocomplete with filtering
 * - Instant JSONata validation (green/red border)
 * - Debounced live preview against example data
 * - JSONata quick reference panel
 */

import type { FieldPath } from '../engine/schema-paths.js';
import './EpExpressionDialog.js';

/** Common JSONata patterns for the quick reference panel. */
export const JSONATA_QUICK_REFERENCE: { code: string; desc: string }[] = [
  { code: 'customer.name', desc: 'Access a field' },
  { code: 'address.line1 & ", " & address.city', desc: 'Concatenate strings' },
  { code: 'age >= 18 ? "Adult" : "Minor"', desc: 'Conditional' },
  { code: '$sum(items.price)', desc: 'Sum numbers' },
  { code: '$count(items)', desc: 'Count array items' },
  { code: '$join(tags, ", ")', desc: 'Join array to string' },
  { code: '$uppercase(name)', desc: 'Uppercase text' },
  { code: '$lowercase(name)', desc: 'Lowercase text' },
  { code: '$now()', desc: 'Current timestamp' },
  { code: '$substring(name, 0, 10)', desc: 'Substring' },
  { code: 'items[price > 100]', desc: 'Filter array' },
  { code: '$number(value)', desc: 'Convert to number' },
  { code: "$formatDate(date, 'dd-MM-yyyy')", desc: 'Format a date' },
  { code: "$formatLocaleNumber(value, '#,##0.00')", desc: 'Format a number (locale-aware)' },
];

export interface ExpressionDialogOptions {
  initialValue: string;
  fieldPaths: FieldPath[];
  getExampleData?: () => Record<string, unknown> | undefined;
  label?: string;
  placeholder?: string;
  /**
   * BCP-47 locale (variant attribute → tenant default → app default) used to
   * format the live preview and number-format example labels so they match the
   * generated PDF. Defaults to `"en-US"` when omitted.
   */
  locale?: string;
  /** Enable builder/code toggle (default: false — code only). */
  enableBuilderMode?: boolean;
  /** Optional filter to highlight certain field paths (e.g., array fields for loops). */
  fieldPathFilter?: (fp: FieldPath) => boolean;
  /**
   * Optional validator for the evaluated result.
   * Return an error message string if the result is invalid, or null if valid.
   * Called after successful evaluation — not called on parse errors or missing data.
   */
  resultValidator?: (value: unknown) => string | null;
  /**
   * Optional disable-predicate for field paths in the autocomplete list.
   * Return a tooltip message string to render the path as a non-clickable
   * disabled item (with the message as its `title`); return `null` to allow
   * the path. Used by callers to express domain rules like "an inline chip
   * cannot bind to a `richTextBlock` field" without leaking that knowledge
   * into the dialog itself.
   */
  pathDisabled?: (fp: FieldPath) => string | null;
}

export interface ExpressionDialogResult {
  /** The expression value, or null if the user cancelled. */
  value: string | null;
}

/**
 * Open a modal dialog for editing a JSONata expression.
 * Returns a promise that resolves when the dialog closes.
 */
export async function openExpressionDialog(
  options: ExpressionDialogOptions,
): Promise<ExpressionDialogResult> {
  const dialog = document.createElement('ep-expression-dialog');
  dialog.initialValue = options.initialValue;
  dialog.fieldPaths = options.fieldPaths;
  dialog.getExampleData = options.getExampleData;
  dialog.label = options.label ?? 'Expression';
  dialog.placeholder = options.placeholder ?? 'e.g. customer.name';
  if (options.locale !== undefined) dialog.locale = options.locale;
  dialog.enableBuilderMode = options.enableBuilderMode ?? false;
  dialog.fieldPathFilter = options.fieldPathFilter;
  dialog.resultValidator = options.resultValidator;
  dialog.pathDisabled = options.pathDisabled;

  document.body.appendChild(dialog);
  // Wait for the first render so the <dialog> ref is populated before show()
  await dialog.updateComplete;
  return dialog.show();
}
