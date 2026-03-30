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
  tryEvaluateExpression,
  formatForPreview,
  isValidExpression,
} from '../engine/resolve-expression.js';

/** Common JSONata patterns for the quick reference panel. */
const JSONATA_QUICK_REFERENCE: { code: string; desc: string }[] = [
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

/** Date format presets for the format dropdown. */
const DATE_FORMAT_PRESETS: { value: string; label: string }[] = [
  { value: '', label: 'No formatting' },
  { value: 'dd-MM-yyyy', label: 'dd-MM-yyyy (15-01-2024)' },
  { value: 'yyyy-MM-dd', label: 'yyyy-MM-dd (2024-01-15)' },
  { value: 'dd/MM/yyyy', label: 'dd/MM/yyyy (15/01/2024)' },
  { value: 'MM/dd/yyyy', label: 'MM/dd/yyyy (01/15/2024)' },
  { value: 'd MMMM yyyy', label: 'd MMMM yyyy (15 January 2024)' },
  { value: 'dd-MM-yyyy HH:mm', label: 'dd-MM-yyyy HH:mm (15-01-2024 14:30)' },
  { value: 'yyyy-MM-dd HH:mm', label: 'yyyy-MM-dd HH:mm (2024-01-15 14:30)' },
];

/** Regex to parse `$formatDate(fieldPath, 'pattern')` expressions. */
const FORMAT_DATE_REGEX = /^\$formatDate\(\s*([^,]+?)\s*,\s*'([^']+)'\s*\)$/;

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
}

export interface ExpressionDialogResult {
  /** The expression value, or null if the user cancelled. */
  value: string | null;
}

/**
 * Open a modal dialog for editing a JSONata expression.
 * Returns a promise that resolves when the dialog closes.
 *
 * When `enableBuilderMode` is true, shows a Builder/Code toggle with a visual
 * builder for simple field references and date formatting. When false (default),
 * renders the code-only dialog (unchanged behavior for inspector consumers).
 */
export function openExpressionDialog(
  options: ExpressionDialogOptions,
): Promise<ExpressionDialogResult> {
  return new Promise((resolve) => {
    const {
      initialValue,
      fieldPaths,
      getExampleData,
      label = 'Expression',
      placeholder = 'e.g. customer.name',
      enableBuilderMode = false,
      fieldPathFilter,
      resultValidator,
    } = options;

    let previewTimer: ReturnType<typeof setTimeout> | null = null;
    let previewGeneration = 0;

    // Determine initial mode
    const initialBuilderState = enableBuilderMode
      ? initialValue
        ? tryParseAsBuilderExpression(initialValue, fieldPaths)
        : null
      : null;
    // Default to builder for new/empty expressions or parseable ones; code for complex
    let currentMode: 'builder' | 'code' = enableBuilderMode
      ? initialValue && !initialBuilderState
        ? 'code'
        : 'builder'
      : 'code';

    const dataFields = fieldPaths.filter((fp) => !fp.system && !fp.scope);
    const scopedFields = fieldPaths.filter((fp) => fp.scope);
    const systemFields = fieldPaths.filter((fp) => fp.system);
    // Builder excludes arrays, objects, and any nested array properties (paths containing [])
    const builderFields = dataFields.filter(
      (fp) => fp.type !== 'array' && fp.type !== 'object' && !fp.path.includes('[]'),
    );
    const builderScopedFields = scopedFields.filter(
      (fp) => fp.type !== 'array' && fp.type !== 'object',
    );
    const dateFieldPaths = new Set(
      fieldPaths.filter((fp) => fp.type === 'date').map((fp) => fp.path),
    );

    // --- Build field options HTML ---
    const fieldOptionHtml = (fp: FieldPath) =>
      `<option value="${escapeAttr(fp.path)}" data-type="${escapeAttr(fp.type)}">${escapeHtml(fp.path)}</option>`;

    const fieldOptionsHtml = [
      '<option value="">Select a field...</option>',
      ...(builderFields.length > 0
        ? [
            '<optgroup label="Template variables">',
            ...builderFields.map(fieldOptionHtml),
            '</optgroup>',
          ]
        : []),
      ...(builderScopedFields.length > 0
        ? [
            '<optgroup label="Iteration variables">',
            ...builderScopedFields.map(fieldOptionHtml),
            '</optgroup>',
          ]
        : []),
      ...(systemFields.length > 0
        ? [
            '<optgroup label="System parameters">',
            ...systemFields.map(fieldOptionHtml),
            '</optgroup>',
          ]
        : []),
    ].join('');

    const formatOptionsHtml = DATE_FORMAT_PRESETS.map(
      (p) => `<option value="${escapeAttr(p.value)}">${escapeHtml(p.label)}</option>`,
    ).join('');

    // --- Create dialog ---
    const dialog = document.createElement('dialog');
    dialog.className = 'expression-dialog';

    dialog.innerHTML = `
      <form method="dialog" class="expression-dialog-form">
        ${
          enableBuilderMode
            ? `
        <div class="expression-dialog-header">
          <label class="expression-dialog-label" for="expression-dialog-input">${escapeHtml(label)}</label>
          <div class="expression-dialog-mode-toggle">
            <button type="button" class="mode-btn${currentMode === 'builder' ? ' active' : ''}" data-mode="builder">Builder</button>
            <button type="button" class="mode-btn${currentMode === 'code' ? ' active' : ''}" data-mode="code">Code</button>
          </div>
        </div>
        <div class="expression-dialog-builder" data-mode-panel="builder"${currentMode !== 'builder' ? ' style="display:none"' : ''}>
          <div class="expression-dialog-builder-row">
            <div class="expression-dialog-builder-field">
              <label for="expression-dialog-field">Field</label>
              <select class="expression-dialog-field-select" id="expression-dialog-field">${fieldOptionsHtml}</select>
            </div>
            <div class="expression-dialog-builder-format" style="display:none">
              <label for="expression-dialog-builder-format">Format</label>
              <select class="expression-dialog-builder-format-select" id="expression-dialog-builder-format">${formatOptionsHtml}</select>
            </div>
          </div>
          <div class="expression-dialog-preview builder-preview" style="display:none"></div>
        </div>
        <div class="expression-dialog-mode-warning" style="display:none">
          This expression is too complex for Builder mode.
        </div>
        `
            : `<label class="expression-dialog-label" for="expression-dialog-input">${escapeHtml(label)}</label>`
        }
        <div class="expression-dialog-code" data-mode-panel="code"${enableBuilderMode && currentMode !== 'code' ? ' style="display:none"' : ''}>
          <input
            type="text"
            class="expression-dialog-input"
            id="expression-dialog-input"
            value="${escapeAttr(initialValue)}"
            placeholder="${escapeAttr(placeholder)}"
            autocomplete="off"
          />
          ${
            !enableBuilderMode
              ? `<div class="expression-dialog-format-row" style="display:none">
            <label class="expression-dialog-format-label" for="expression-dialog-date-format">Date format</label>
            <select class="expression-dialog-format-select" id="expression-dialog-date-format">${formatOptionsHtml}</select>
          </div>`
              : ''
          }
          <div class="expression-dialog-preview code-preview" style="display:none"></div>
          <div class="expression-dialog-paths"></div>
          <details class="expression-dialog-reference">
            <summary class="expression-dialog-ref-summary">JSONata Quick Reference</summary>
            <div class="expression-dialog-ref-list"></div>
          </details>
        </div>
        <div class="expression-dialog-actions">
          <button type="button" class="expression-dialog-btn cancel">Cancel</button>
          <button type="submit" class="expression-dialog-btn save">Save</button>
        </div>
      </form>
    `;

    // --- Query elements ---
    const input = dialog.querySelector<HTMLInputElement>('.expression-dialog-input')!;
    const cancelBtn = dialog.querySelector('.cancel')!;
    const pathsContainer = dialog.querySelector<HTMLElement>('.expression-dialog-paths')!;
    const codePreviewEl = dialog.querySelector<HTMLElement>('.code-preview')!;
    const refList = dialog.querySelector<HTMLElement>('.expression-dialog-ref-list')!;
    const formatRow = dialog.querySelector<HTMLElement>('.expression-dialog-format-row');
    const formatSelect = dialog.querySelector<HTMLSelectElement>(
      '.expression-dialog-format-select',
    );

    // Builder-mode elements (may be null if enableBuilderMode is false)
    const builderPanel = dialog.querySelector<HTMLElement>('[data-mode-panel="builder"]');
    const codePanel = dialog.querySelector<HTMLElement>('[data-mode-panel="code"]')!;
    const fieldSelect = dialog.querySelector<HTMLSelectElement>('.expression-dialog-field-select');
    const builderFormatContainer = dialog.querySelector<HTMLElement>(
      '.expression-dialog-builder-format',
    );
    const builderFormatSelect = dialog.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    );
    const builderPreviewEl = dialog.querySelector<HTMLElement>('.builder-preview');
    const modeWarning = dialog.querySelector<HTMLElement>('.expression-dialog-mode-warning');
    const builderBtn = dialog.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    const codeBtn = dialog.querySelector<HTMLButtonElement>('.mode-btn[data-mode="code"]');

    // --- Shared helpers ---
    const cancelPreviewTimer = () => {
      if (previewTimer !== null) {
        clearTimeout(previewTimer);
        previewTimer = null;
      }
    };

    const getActivePreviewEl = () =>
      currentMode === 'builder' && builderPreviewEl ? builderPreviewEl : codePreviewEl;

    const schedulePreview = (expr: string) => {
      cancelPreviewTimer();
      const previewEl = getActivePreviewEl();
      if (!expr) {
        previewEl.style.display = 'none';
        return;
      }
      previewTimer = setTimeout(() => {
        updatePreview(
          expr,
          previewEl,
          getExampleData,
          () => ++previewGeneration,
          () => previewGeneration,
          resultValidator,
        );
      }, 250);
    };

    // --- Code mode: date format dropdown (only when builder mode is off) ---
    const getBarePath = (val: string): string => {
      const parsed = parseFormatDateExpression(val);
      return parsed ? parsed.fieldPath : val;
    };

    const updateCodeFormatVisibility = () => {
      if (!formatRow || !formatSelect) return;
      const barePath = getBarePath(input.value.trim());
      const isDateField = dateFieldPaths.has(barePath);
      formatRow.style.display = isDateField ? '' : 'none';
      if (!isDateField) formatSelect.value = '';
    };

    if (formatSelect) {
      const initialParsed = parseFormatDateExpression(initialValue);
      if (initialParsed && dateFieldPaths.has(initialParsed.fieldPath)) {
        formatSelect.value = initialParsed.pattern;
      }

      formatSelect.addEventListener('change', () => {
        const barePath = getBarePath(input.value.trim());
        const pattern = formatSelect.value;
        input.value = pattern ? wrapFormatDate(barePath, pattern) : barePath;
        input.dispatchEvent(new Event('input', { bubbles: true }));
      });
    }

    // --- Code mode: validation + preview ---
    const applyValidation = () => {
      const val = input.value.trim();
      input.classList.remove('valid', 'invalid');
      if (val) {
        input.classList.add(isValidExpression(val) ? 'valid' : 'invalid');
      }
    };

    input.addEventListener('input', () => {
      applyValidation();
      updateCodeFormatVisibility();
      schedulePreview(input.value.trim());
      // Update builder toggle availability
      if (enableBuilderMode && builderBtn) {
        const canSwitch =
          !input.value.trim() ||
          tryParseAsBuilderExpression(input.value.trim(), fieldPaths) !== null;
        builderBtn.classList.toggle('disabled', !canSwitch);
      }
    });

    // --- Code mode: field paths + quick reference ---
    renderFieldPaths(pathsContainer, input, fieldPaths, fieldPathFilter);
    renderQuickReference(refList, input);

    // --- Builder mode logic ---
    if (
      enableBuilderMode &&
      fieldSelect &&
      builderFormatContainer &&
      builderFormatSelect &&
      builderPanel
    ) {
      // Populate builder from initial state
      if (initialBuilderState) {
        fieldSelect.value = initialBuilderState.fieldPath;
        if (initialBuilderState.formatType === 'date' && initialBuilderState.formatPattern) {
          builderFormatContainer.style.display = '';
          builderFormatSelect.value = initialBuilderState.formatPattern;
        }
      }

      const updateBuilderFormatVisibility = () => {
        const selectedOption = fieldSelect.selectedOptions[0];
        const fieldType = selectedOption?.dataset.type ?? '';
        const isDate = fieldType === 'date';
        builderFormatContainer.style.display = isDate ? '' : 'none';
        if (!isDate) builderFormatSelect.value = '';
      };

      const getBuilderExpression = (): string => {
        const fieldPath = fieldSelect.value;
        if (!fieldPath) return '';
        const selectedOption = fieldSelect.selectedOptions[0];
        const fieldType = selectedOption?.dataset.type ?? 'string';
        const formatPattern = builderFormatSelect.value;
        return buildExpression({
          fieldPath,
          fieldType,
          formatType: fieldType === 'date' && formatPattern ? 'date' : 'none',
          formatPattern,
        });
      };

      const onBuilderChange = () => {
        updateBuilderFormatVisibility();
        const expr = getBuilderExpression();
        // Sync to code input
        input.value = expr;
        schedulePreview(expr);
      };

      fieldSelect.addEventListener('change', onBuilderChange);
      builderFormatSelect.addEventListener('change', onBuilderChange);

      // --- Mode switching ---
      const switchMode = (mode: 'builder' | 'code') => {
        if (mode === currentMode) return;

        if (mode === 'builder') {
          // Try to parse current code input into builder
          const parsed = input.value.trim()
            ? tryParseAsBuilderExpression(input.value.trim(), fieldPaths)
            : null; // empty is OK for builder

          if (input.value.trim() && !parsed) {
            // Show context-aware warning
            if (modeWarning) {
              const stale = isStaleFieldReference(input.value.trim(), fieldPaths);
              modeWarning.textContent = stale
                ? `Field '${input.value.trim()}' not found. The loop alias may have changed.`
                : 'This expression is too complex for Builder mode.';
              modeWarning.style.display = '';
              setTimeout(() => {
                modeWarning.style.display = 'none';
              }, 5000);
            }
            return;
          }

          // Populate builder
          if (parsed) {
            fieldSelect.value = parsed.fieldPath;
            builderFormatSelect.value = parsed.formatPattern;
          } else {
            fieldSelect.value = '';
            builderFormatSelect.value = '';
          }
          updateBuilderFormatVisibility();
        } else {
          // Builder → Code: sync expression to code input
          input.value = getBuilderExpression();
          applyValidation();
          updateCodeFormatVisibility();
        }

        currentMode = mode;
        builderPanel.style.display = mode === 'builder' ? '' : 'none';
        codePanel.style.display = mode === 'code' ? '' : 'none';
        builderBtn?.classList.toggle('active', mode === 'builder');
        codeBtn?.classList.toggle('active', mode === 'code');

        // Refresh preview in the new panel
        const expr = mode === 'builder' ? getBuilderExpression() : input.value.trim();
        schedulePreview(expr);
      };

      builderBtn?.addEventListener('click', () => {
        if (!builderBtn.classList.contains('disabled')) switchMode('builder');
      });
      codeBtn?.addEventListener('click', () => switchMode('code'));

      // Initial builder preview
      if (currentMode === 'builder') {
        const expr = getBuilderExpression();
        if (expr) schedulePreview(expr);
      }
    }

    // --- Close helpers ---
    const close = (value: string | null) => {
      cancelPreviewTimer();
      previewGeneration++; // discard in-flight previews
      dialog.close();
      dialog.remove();
      resolve({ value });
    };

    cancelBtn.addEventListener('click', () => close(null));

    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        close(null);
      }
    });

    dialog.querySelector('form')!.addEventListener('submit', (e) => {
      e.preventDefault();
      let value: string;
      if (currentMode === 'builder' && fieldSelect) {
        // Build expression from builder state
        value = input.value.trim(); // already synced by onBuilderChange
      } else {
        value = input.value.trim();
      }
      close(value || null);
    });

    dialog.addEventListener('click', (e) => {
      if (e.target === dialog) close(null);
    });

    // --- Show ---
    document.body.appendChild(dialog);
    dialog.showModal();

    if (currentMode === 'code') {
      input.focus();
      input.select();
    }

    // Initial code mode state
    if (initialValue && currentMode === 'code') {
      applyValidation();
      updateCodeFormatVisibility();
      updatePreview(
        initialValue,
        codePreviewEl,
        getExampleData,
        () => ++previewGeneration,
        () => previewGeneration,
        resultValidator,
      );
    }
  });
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function renderFieldPaths(
  container: HTMLElement,
  input: HTMLInputElement,
  fieldPaths: FieldPath[],
  fieldPathFilter?: (fp: FieldPath) => boolean,
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

  // Render data fields
  for (const fp of dataFields) {
    const li = createFieldPathItem(fp, input, fieldPathFilter);
    list.appendChild(li);
    items.push({ li, path: fp.path });
  }

  // Render iteration variables in a separate section
  if (scopedFields.length > 0) {
    const scopeHeader = document.createElement('li');
    scopeHeader.className = 'expression-dialog-section-header scoped-header';
    scopeHeader.textContent = 'Iteration variables';
    list.appendChild(scopeHeader);

    for (const fp of scopedFields) {
      const li = createFieldPathItem(fp, input, fieldPathFilter);
      li.classList.add('scoped');
      if (fp.description) {
        li.title = fp.description;
      }
      list.appendChild(li);
      items.push({ li, path: fp.path });
    }
  }

  // Render system parameters in a separate section
  if (systemFields.length > 0) {
    const sysHeader = document.createElement('li');
    sysHeader.className = 'expression-dialog-section-header system-header';
    sysHeader.textContent = 'System parameters';
    list.appendChild(sysHeader);

    for (const fp of systemFields) {
      const li = createFieldPathItem(fp, input, fieldPathFilter);
      li.classList.add('system');
      if (fp.description) {
        li.title = fp.description;
      }
      list.appendChild(li);
      items.push({ li, path: fp.path });
    }
  }

  // Filter field paths on typing
  filterInput.addEventListener('input', () => {
    const query = filterInput.value.toLowerCase();
    for (const item of items) {
      item.li.style.display = item.path.toLowerCase().includes(query) ? '' : 'none';
    }
    // Show/hide section headers based on visible items
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

function createFieldPathItem(
  fp: FieldPath,
  input: HTMLInputElement,
  fieldPathFilter?: (fp: FieldPath) => boolean,
): HTMLLIElement {
  const li = document.createElement('li');
  li.className = 'expression-dialog-path-item';

  if (fieldPathFilter?.(fp)) {
    li.classList.add('highlighted');
  }

  const pathSpan = document.createElement('span');
  pathSpan.className = 'expression-dialog-path-name';
  pathSpan.textContent = fp.path;

  const typeSpan = document.createElement('span');
  typeSpan.className = 'expression-dialog-path-type';
  typeSpan.textContent = fp.type;

  li.appendChild(pathSpan);
  li.appendChild(typeSpan);

  li.addEventListener('click', () => {
    input.value = fp.path;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.focus();
  });

  return li;
}

function renderQuickReference(container: HTMLElement, input: HTMLInputElement): void {
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

function updatePreview(
  expression: string,
  previewEl: HTMLElement,
  getExampleData: (() => Record<string, unknown> | undefined) | undefined,
  incrementGeneration: () => number,
  getGeneration: () => number,
  resultValidator?: (value: unknown) => string | null,
): void {
  const data = getExampleData?.();
  if (!data) {
    previewEl.style.display = '';
    previewEl.className = 'expression-dialog-preview no-data';
    previewEl.textContent = 'No data example selected';
    return;
  }

  const generation = incrementGeneration();
  tryEvaluateExpression(expression, data).then((result) => {
    if (generation !== getGeneration()) return; // stale

    previewEl.style.display = '';
    if (result.ok) {
      // Run result validator if provided (e.g., loop expressions must be arrays)
      const validationError = resultValidator?.(result.value);
      if (validationError) {
        previewEl.className = 'expression-dialog-preview error';
        previewEl.textContent = validationError;
      } else {
        previewEl.className = 'expression-dialog-preview success';
        previewEl.textContent = `Preview: ${formatForPreview(result.value)}`;
      }
    } else {
      previewEl.className = 'expression-dialog-preview error';
      previewEl.textContent = result.error;
    }
  });
}

function escapeAttr(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
