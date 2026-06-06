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
  /** BCP-47 locale for the advanced dialog's preview + number-format examples. */
  locale?: string;
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
    locale,
    onChange,
    paramDatasetKey,
    error,
  } = options;
  const typeLabel = typeOf(prop);
  const dataAttr = paramDatasetKey ? ` data-param="${escapeAttr(paramDatasetKey)}"` : '';

  const row = document.createElement('div');
  row.className = 'stencil-picker-group-mb3';
  row.innerHTML = `
    <div class="stencil-picker-label-row">
      <label class="stencil-picker-label-inline">${escapeHtml(name)}</label>
      <span class="stencil-picker-muted">${typeLabel}</span>
      ${required ? '<span class="stencil-picker-error">required</span>' : ''}
    </div>
    ${prop?.description ? `<div class="stencil-picker-desc">${escapeHtml(prop.description)}</div>` : ''}
    <div class="stencil-picker-flex-tight">
      <input type="text" class="ep-input binding-row-input stencil-picker-flex-fill"${dataAttr} placeholder="JSONata expression — e.g. recipient.name" />
      <span class="stencil-picker-validity"></span>
      <button type="button" class="stencil-picker-btn binding-row-advanced stencil-picker-btn-compact"${dataAttr} title="Open expression editor">…</button>
    </div>
    <div class="binding-row-error stencil-picker-error" style="margin-top: 2px; display: none;"></div>
  `;

  const input = row.querySelector<HTMLInputElement>('.binding-row-input')!;
  const validityEl = row.querySelector<HTMLElement>('.binding-row-validity')!;
  const errorEl = row.querySelector<HTMLElement>('.binding-row-error')!;
  const advancedBtn = row.querySelector<HTMLButtonElement>('.binding-row-advanced')!;
  input.value = initialValue;

  let validationDebounce: ReturnType<typeof setTimeout> | null = null;
  let hasBackendError = !!error;

  function validate() {
    // A deferred (debounced / setTimeout(0)) call can fire after the dialog
    // DOM is torn down — touching detached nodes then is pointless.
    if (!row.isConnected) return;
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
      locale,
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

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!;
  });
}

function escapeAttr(s: string): string {
  return escapeHtml(s);
}
