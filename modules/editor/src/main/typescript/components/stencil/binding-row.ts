/**
 * Shared rendering + wiring for parameter binding rows.
 *
 * Both the picker dialog's step 4 (when inserting a stencil) and the
 * inspector's "Configure parameters…" dialog (when re-editing bindings on
 * an existing stencil) render the same per-parameter row: name + type
 * badge + optional description + a JSONata input + a "…" button that
 * opens the full expression dialog.
 *
 * This module owns that rendering and the type-aware filter applied to
 * field-path autocomplete inside the expression dialog. Callers wire up
 * the input/advanced events to their own state.
 */
import { openExpressionDialog } from '../../ui/expression-dialog.js';
import { isValidExpression } from '../../engine/resolve-expression.js';
import type { FieldPath } from '../../engine/schema-paths.js';
import type { JsonSchemaProperty } from '../../data-contract/types.js';

export interface BindingRowOptions {
  /** Parameter name. */
  name: string;
  /** Parameter schema (used for type label, description, advanced-dialog filter). */
  prop: JsonSchemaProperty | undefined;
  /** Whether this parameter is required (renders the badge). */
  required: boolean;
  /** Initial JSONata expression to display. */
  initialValue: string;
  /** Fields available at the insertion point — passed to openExpressionDialog. */
  fieldPaths: FieldPath[];
  /** Live preview source for the advanced dialog. */
  getExampleData?: () => Record<string, unknown> | undefined;
  /** Notified when the binding's expression changes (input or advanced dialog). */
  onChange: (newValue: string) => void;
  /**
   * Optional dataset key to attach to the input/button; useful when the
   * caller needs to find rows by parameter name later.
   */
  paramDatasetKey?: string;
  /** Optional backend validation error to display inline on this row. */
  error?: string;
}

export interface RenderedBindingRow {
  /** Wrap in a parent container; this is the `<div>` for the whole row. */
  element: HTMLElement;
  /** The expression input — caller can set focus / read value if needed. */
  input: HTMLInputElement;
  /** The "…" advanced button — caller can disable / show alternate UI. */
  advancedBtn: HTMLButtonElement;
}

const VALIDATION_DEBOUNCE_MS = 250;

export function renderBindingRow(options: BindingRowOptions): RenderedBindingRow {
  const {
    name,
    prop,
    required,
    initialValue,
    fieldPaths,
    getExampleData,
    onChange,
    paramDatasetKey,
    error,
  } = options;
  const typeLabel = typeOf(prop);
  const dataAttr = paramDatasetKey ? ` data-param="${escapeAttr(paramDatasetKey)}"` : '';

  const row = document.createElement('div');
  row.style.marginBottom = 'var(--ep-space-3)';
  row.innerHTML = `
    <div style="display:flex; align-items:center; gap:var(--ep-space-2); margin-bottom:2px;">
      <label style="font-size: var(--ep-text-xs); font-weight:500;">${escapeHtml(name)}</label>
      <span style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground);">${typeLabel}</span>
      ${required ? '<span style="font-size: var(--ep-text-xs); color: var(--ep-destructive, #dc2626);">required</span>' : ''}
    </div>
    ${prop?.description ? `<div style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); margin-bottom:2px;">${escapeHtml(prop.description)}</div>` : ''}
    <div style="display:flex; gap: 4px; align-items: center;">
      <input type="text" class="ep-input binding-row-input"${dataAttr} style="flex: 1;" placeholder="JSONata expression — e.g. recipient.name" />
      <span class="binding-row-validity" style="font-size: 14px; width: 16px; text-align: center; flex-shrink: 0;"></span>
      <button type="button" class="stencil-picker-btn binding-row-advanced"${dataAttr} style="padding: 4px 10px;" title="Open expression editor">…</button>
    </div>
    <div class="binding-row-error" style="font-size: var(--ep-text-xs); color: var(--ep-destructive, #dc2626); margin-top: 2px; display: none;"></div>
  `;

  const input = row.querySelector<HTMLInputElement>('.binding-row-input')!;
  const validityEl = row.querySelector<HTMLElement>('.binding-row-validity')!;
  const errorEl = row.querySelector<HTMLElement>('.binding-row-error')!;
  const advancedBtn = row.querySelector<HTMLButtonElement>('.binding-row-advanced')!;
  input.value = initialValue;

  let validationDebounce: ReturnType<typeof setTimeout> | null = null;
  let hasBackendError = !!error;

  function validate() {
    const val = input.value.trim();
    const valid = val.length === 0 || isValidExpression(val);
    input.classList.toggle('binding-row-valid', val.length > 0 && valid);
    input.classList.toggle('binding-row-invalid', val.length > 0 && !valid);
    validityEl.textContent = val.length === 0 ? '' : valid ? '\u2713' : '\u2717';
    validityEl.style.color =
      val.length === 0
        ? 'transparent'
        : valid
          ? 'var(--ep-green-600, #16a34a)'
          : 'var(--ep-destructive, #dc2626)';
    if (val.length > 0 && !valid && !hasBackendError) {
      errorEl.textContent = 'Invalid JSONata expression';
      errorEl.style.display = '';
    } else if (val.length > 0 && !valid && hasBackendError) {
      // Keep backend error text, just make sure it's visible
      errorEl.style.display = '';
    } else {
      errorEl.style.display = 'none';
    }
  }

  // Show backend error if present (takes priority over client validation)
  if (error) {
    errorEl.textContent = error;
    errorEl.style.display = '';
    input.classList.add('binding-row-invalid');
    validityEl.textContent = '\u2717';
    validityEl.style.color = 'var(--ep-destructive, #dc2626)';
  }

  input.addEventListener('input', () => {
    onChange(input.value.trim());
    hasBackendError = false;
    if (validationDebounce) clearTimeout(validationDebounce);
    validationDebounce = setTimeout(validate, VALIDATION_DEBOUNCE_MS);
  });
  input.addEventListener('blur', validate);
  setTimeout(validate, 0);

  advancedBtn.addEventListener('click', async () => {
    const compatible = filterFieldsByType(fieldPaths, propTypeKey(prop));
    const result = await openExpressionDialog({
      initialValue: input.value,
      fieldPaths,
      getExampleData,
      label: `Expression for ${name}`,
      placeholder: 'e.g. recipient.name',
      enableBuilderMode: true,
      fieldPathFilter: (fp) => compatible.some((f) => f.path === fp.path),
    });
    if (result.value === null) return;
    input.value = result.value;
    onChange(result.value.trim());
  });

  return { element: row, input, advancedBtn };
}

/**
 * Coarse type matcher — accepts a field path if its declared type plausibly
 * fits the parameter slot. Permissive: string accepts anything (JSONata
 * coerces freely); array fields are passed through unfiltered for any
 * paramType so consumers can wrap them in JSONata array operations.
 */
function filterFieldsByType(fieldPaths: FieldPath[], paramTypeKey: string): FieldPath[] {
  switch (paramTypeKey) {
    case 'string':
    case 'date':
    case 'datetime':
      return fieldPaths;
    case 'number':
    case 'integer':
      return fieldPaths.filter((fp) => fp.type === 'number' || fp.type === 'integer');
    case 'boolean':
      return fieldPaths.filter((fp) => fp.type === 'boolean');
    case 'array':
      return fieldPaths.filter((fp) => fp.type === 'array');
    default:
      return fieldPaths;
  }
}

function propTypeKey(prop: JsonSchemaProperty | undefined): string {
  if (!prop) return 'string';
  const t = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  if (t === 'array') return 'array';
  if (t === 'string' && prop.format === 'date') return 'date';
  if (t === 'string' && prop.format === 'date-time') return 'datetime';
  return t ?? 'string';
}

function typeOf(prop: JsonSchemaProperty | undefined): string {
  if (!prop) return 'string';
  const t = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  if (t === 'array') {
    const inner = prop.items;
    const innerType = inner ? (Array.isArray(inner.type) ? inner.type[0] : inner.type) : 'string';
    return `list of ${innerType}`;
  }
  if (t === 'string' && prop.format === 'date') return 'date';
  if (t === 'string' && prop.format === 'date-time') return 'date-time';
  return t ?? 'string';
}

/**
 * Parse a save error message for known binding-validation error codes.
 * Returns structured info when the message matches, null otherwise.
 *
 * Handles:
 *  - NODE_PARAMETER_BINDING_SYNTAX_INVALID: parameter binding 'X' expression is invalid — {msg}
 */
export function parseBindingSaveError(
  message: string,
): { paramName: string; message: string } | null {
  const syntaxMatch = message.match(
    /^NODE_PARAMETER_BINDING_SYNTAX_INVALID:\s*parameter binding '([^']+)' expression is invalid/u,
  );
  if (syntaxMatch) {
    const paramName = syntaxMatch[1];
    // Extract the parser message after the " — " separator
    const parserMsg = message.includes(' — ')
      ? message.slice(message.indexOf(' — ') + 3)
      : 'Invalid JSONata expression';
    return { paramName, message: parserMsg };
  }
  return null;
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!;
  });
}

function escapeAttr(s: string): string {
  return escapeHtml(s);
}
