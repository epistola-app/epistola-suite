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
import {
  formatBindingPreviewPlaceholder,
  formatFieldPathTypeLabel,
} from '../data-contract/binding-compatibility.js';
import { tryEvaluateExpression, formatForPreview } from '../engine/resolve-expression.js';
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
];

/** Regex to parse `$formatDate(fieldPath, 'pattern')` or `$formatDate(fieldPath, "pattern")` expressions. */
const FORMAT_DATE_REGEX = /^\$formatDate\(\s*([^,]+?)\s*,\s*["']([^"']+)["']\s*\)$/;

/** Extract field path and format pattern from a `$formatDate(...)` expression. */
export function parseFormatDateExpression(
  expr: string,
): { fieldPath: string; pattern: string } | null {
  const match = expr.match(FORMAT_DATE_REGEX);
  if (!match) return null;
  return { fieldPath: match[1], pattern: match[2] };
}

/** Wrap a field path with `$formatDate(...)`. */
export function wrapFormatDate(fieldPath: string, pattern: string): string {
  return `$formatDate(${fieldPath}, '${pattern}')`;
}

// ---------------------------------------------------------------------------
// Builder mode support
// ---------------------------------------------------------------------------

/** State representing a builder-representable expression. */
export interface BuilderState {
  fieldPath: string;
  fieldType: string;
  formatType: 'none' | 'date';
  formatPattern: string;
}

/**
 * Try to parse an expression into a BuilderState.
 * Returns null if the expression cannot be represented in builder mode.
 */
export function tryParseAsBuilderExpression(
  expr: string,
  fieldPaths: FieldPath[],
): BuilderState | null {
  const trimmed = expr.trim();
  if (!trimmed) return null;

  // Try $formatDate(field, 'pattern')
  const parsed = parseFormatDateExpression(trimmed);
  if (parsed) {
    const fp = fieldPaths.find((f) => f.path === parsed.fieldPath);
    if (fp) {
      return {
        fieldPath: parsed.fieldPath,
        fieldType: fp.type,
        formatType: 'date',
        formatPattern: parsed.pattern,
      };
    }
  }

  // Try bare field path
  const fp = fieldPaths.find((f) => f.path === trimmed);
  if (fp) {
    return { fieldPath: trimmed, fieldType: fp.type, formatType: 'none', formatPattern: '' };
  }

  return null;
}

/**
 * Check if an expression looks like a simple builder pattern (bare path or $formatDate)
 * but the field isn't in the available paths. This distinguishes "stale field reference"
 * from "complex JSONata expression".
 */
export function isStaleFieldReference(expr: string, fieldPaths: FieldPath[]): boolean {
  const trimmed = expr.trim();
  if (!trimmed) return false;

  // Check if it's a $formatDate with a field path that's not found
  const parsed = parseFormatDateExpression(trimmed);
  if (parsed) {
    return !fieldPaths.some((f) => f.path === parsed.fieldPath);
  }

  // Check if it looks like a simple dot-path (no operators, no function calls except $formatDate)
  const looksLikeSimplePath = /^[a-zA-Z_][a-zA-Z0-9_.]*$/.test(trimmed);
  if (looksLikeSimplePath) {
    return !fieldPaths.some((f) => f.path === trimmed);
  }

  return false;
}

/** Construct an expression string from builder state. */
export function buildExpression(state: BuilderState): string {
  if (state.formatType === 'date' && state.formatPattern) {
    return wrapFormatDate(state.fieldPath, state.formatPattern);
  }
  return state.fieldPath;
}

// ---------------------------------------------------------------------------
// Dialog options and result
// ---------------------------------------------------------------------------

export interface ExpressionDialogOptions {
  initialValue: string;
  fieldPaths: FieldPath[];
  getExampleData?: () => Record<string, unknown> | undefined;
  label?: string;
  placeholder?: string;
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
  dialog.enableBuilderMode = options.enableBuilderMode ?? false;
  dialog.fieldPathFilter = options.fieldPathFilter;
  dialog.resultValidator = options.resultValidator;
  dialog.pathDisabled = options.pathDisabled;

  document.body.appendChild(dialog);
  // Wait for the first render so the <dialog> ref is populated before show()
  await dialog.updateComplete;
  return dialog.show();
}

// ---------------------------------------------------------------------------
// Internal helpers (kept for backward compatibility and future phases)
// ---------------------------------------------------------------------------

export function renderFieldPaths(
  container: HTMLElement,
  input: HTMLInputElement,
  fieldPaths: FieldPath[],
  fieldPathFilter?: (fp: FieldPath) => boolean,
  pathDisabled?: (fp: FieldPath) => string | null,
): void {
  if (fieldPaths.length === 0) return;

  const dataFields = fieldPaths.filter((fp) => !fp.system && !fp.scope);
  const scopedFields = fieldPaths.filter((fp) => fp.scope);
  const systemFields = fieldPaths.filter((fp) => fp.system);

  const header = document.createElement('div');
  header.className = 'expression-dialog-paths-header';

  const headerLabel = document.createElement('span');
  headerLabel.textContent = 'Available fields';

  const filterInput = document.createElement('input');
  filterInput.type = 'text';
  filterInput.className = 'expression-dialog-filter';
  filterInput.placeholder = 'Filter...';

  header.appendChild(headerLabel);
  header.appendChild(filterInput);
  container.appendChild(header);

  const list = document.createElement('ul');
  list.className = 'expression-dialog-paths-list';

  const items: { li: HTMLLIElement; path: string }[] = [];

  for (const fp of dataFields) {
    const li = createFieldPathItem(fp, input, fieldPathFilter, pathDisabled);
    list.appendChild(li);
    items.push({ li, path: fp.path });
  }

  if (scopedFields.length > 0) {
    const scopeHeader = document.createElement('li');
    scopeHeader.className = 'expression-dialog-section-header scoped-header';
    scopeHeader.textContent = 'Iteration variables';
    list.appendChild(scopeHeader);

    for (const fp of scopedFields) {
      const li = createFieldPathItem(fp, input, fieldPathFilter, pathDisabled);
      li.classList.add('scoped');
      if (fp.description) {
        li.title = fp.description;
      }
      list.appendChild(li);
      items.push({ li, path: fp.path });
    }
  }

  if (systemFields.length > 0) {
    const sysHeader = document.createElement('li');
    sysHeader.className = 'expression-dialog-section-header system-header';
    sysHeader.textContent = 'System parameters';
    list.appendChild(sysHeader);

    for (const fp of systemFields) {
      const li = createFieldPathItem(fp, input, fieldPathFilter, pathDisabled);
      li.classList.add('system');
      if (fp.description) {
        li.title = fp.description;
      }
      list.appendChild(li);
      items.push({ li, path: fp.path });
    }
  }

  filterInput.addEventListener('input', () => {
    const query = filterInput.value.toLowerCase();
    for (const item of items) {
      item.li.style.display = item.path.toLowerCase().includes(query) ? '' : 'none';
    }
    for (const [cssClass, headerClass] of [
      ['scoped', 'scoped-header'],
      ['system', 'system-header'],
    ] as const) {
      const headerEl = list.querySelector<HTMLElement>(`.${headerClass}`);
      if (headerEl) {
        const hasVisible = items.some(
          (item) => item.li.classList.contains(cssClass) && item.li.style.display !== 'none',
        );
        headerEl.style.display = hasVisible || !query ? '' : 'none';
      }
    }
  });

  container.appendChild(list);
}

export function createFieldPathItem(
  fp: FieldPath,
  input: HTMLInputElement,
  fieldPathFilter?: (fp: FieldPath) => boolean,
  pathDisabled?: (fp: FieldPath) => string | null,
): HTMLLIElement {
  const li = document.createElement('li');
  li.className = 'expression-dialog-path-item';

  if (fieldPathFilter && fieldPathFilter(fp)) {
    li.classList.add('highlighted');
  }

  const disabledReason = pathDisabled ? pathDisabled(fp) : null;
  if (disabledReason !== null) {
    li.classList.add('disabled');
    li.title = disabledReason;
    li.setAttribute('aria-disabled', 'true');
  }

  const pathSpan = document.createElement('span');
  pathSpan.className = 'expression-dialog-path-name';
  pathSpan.textContent = fp.path;

  const typeSpan = document.createElement('span');
  typeSpan.className = 'expression-dialog-path-type';
  typeSpan.textContent = formatFieldPathTypeLabel(fp);

  li.appendChild(pathSpan);
  li.appendChild(typeSpan);

  li.addEventListener('click', () => {
    if (disabledReason !== null) return;
    input.value = fp.path;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.focus();
  });

  return li;
}

export function renderQuickReference(container: HTMLElement, input: HTMLInputElement): void {
  for (const entry of JSONATA_QUICK_REFERENCE) {
    const row = document.createElement('div');
    row.className = 'expression-dialog-ref-row';

    const code = document.createElement('code');
    code.className = 'expression-dialog-ref-code';
    code.textContent = entry.code;

    const desc = document.createElement('span');
    desc.className = 'expression-dialog-ref-desc';
    desc.textContent = entry.desc;

    row.appendChild(code);
    row.appendChild(desc);

    row.addEventListener('click', () => {
      input.value = entry.code;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.focus();
    });

    container.appendChild(row);
  }
}

export function updatePreview(
  expression: string,
  previewEl: HTMLElement,
  getExampleData: (() => Record<string, unknown> | undefined) | undefined,
  incrementGeneration: () => number,
  getGeneration: () => number,
  resultValidator?: (value: unknown) => string | null,
): void {
  let data: Record<string, unknown> | undefined;
  if (getExampleData) {
    data = getExampleData();
  }
  if (!data) {
    previewEl.style.display = '';
    previewEl.className = 'expression-dialog-preview no-data';
    previewEl.textContent = 'No data example selected';
    return;
  }

  const generation = incrementGeneration();
  tryEvaluateExpression(expression, data)
    .then((result) => {
      if (generation !== getGeneration()) return; // stale

      previewEl.style.display = '';
      if (result.ok) {
        const validationError = resultValidator ? resultValidator(result.value) : null;
        if (validationError) {
          previewEl.className = 'expression-dialog-preview error';
          previewEl.textContent = validationError;
        } else {
          previewEl.className = 'expression-dialog-preview success';
          const placeholder = formatBindingPreviewPlaceholder(result.value);
          previewEl.textContent = `Preview: ${placeholder ?? formatForPreview(result.value)}`;
        }
      } else {
        previewEl.className = 'expression-dialog-preview error';
        previewEl.textContent = result.error;
      }
    })
    .catch(() => {
      // Errors are surfaced via result.error; rejections are silently ignored
    });
}
