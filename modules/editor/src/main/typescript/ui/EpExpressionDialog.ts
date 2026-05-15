import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { createRef, ref, type Ref } from 'lit/directives/ref.js';
import type { FieldPath } from '../engine/schema-paths.js';
import {
  formatFieldPathTypeLabel,
  formatBindingPreviewPlaceholder,
} from '../data-contract/binding-compatibility.js';
import {
  isValidExpression,
  tryEvaluateExpression,
  formatForPreview,
} from '../engine/resolve-expression.js';
import {
  parseFormatDateExpression,
  wrapFormatDate,
  tryParseAsBuilderExpression,
  buildExpression,
  isStaleFieldReference,
} from './expression-builder.js';
import { JSONATA_QUICK_REFERENCE } from './expression-dialog.js';
import type { ExpressionDialogResult } from './expression-dialog.js';

/*
 * Lit binds `this` automatically when using `@event=${this.handler}` syntax,
 * so `unbound-method` warnings are false positives in this file.
 * See: https://lit.dev/docs/components/events/#understanding-this-in-event-listeners
 */
/* eslint-disable unbound-method */

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

type PreviewState = 'empty' | 'loading' | 'no-data' | 'success' | 'error';

/**
 * Lit component for the expression dialog.
 *
 * Uses light DOM so that existing CSS selectors in prosemirror.css
 * and inspector.css continue to match.
 */
@customElement('ep-expression-dialog')
export class EpExpressionDialog extends LitElement {
  public override createRenderRoot(): HTMLElement {
    return this;
  }

  // -----------------------------------------------------------------------
  // Public properties (mirrors ExpressionDialogOptions)
  // -----------------------------------------------------------------------

  @property({ type: String }) public initialValue = '';
  @property({ attribute: false }) public fieldPaths: FieldPath[] = [];
  @property({ attribute: false }) public getExampleData?: () => Record<string, unknown> | undefined;
  @property({ type: String }) public label = 'Expression';
  @property({ type: String }) public placeholder = 'e.g. customer.name';
  @property({ type: Boolean }) public enableBuilderMode = false;
  @property({ attribute: false }) public fieldPathFilter?: (fp: FieldPath) => boolean;
  @property({ attribute: false }) public resultValidator?: (value: unknown) => string | null;
  @property({ attribute: false }) public pathDisabled?: (fp: FieldPath) => string | null;

  // -----------------------------------------------------------------------
  // Internal state
  // -----------------------------------------------------------------------

  @state() private _expression = '';
  @state() private _mode: 'builder' | 'code' = 'code';
  @state() private _builderFieldPath = '';
  @state() private _builderFormatPattern = '';
  @state() private _modeWarning = '';
  @state() private _previewState: PreviewState = 'empty';
  @state() private _previewText = '';
  @state() private _fieldFilter = '';
  @state() private _refExpanded = true;

  private _dialogRef: Ref<HTMLDialogElement> = createRef();
  private _inputRef: Ref<HTMLTextAreaElement> = createRef();
  private _resolve: ((value: ExpressionDialogResult) => void) | null = null;
  private _previewTimer: ReturnType<typeof setTimeout> | null = null;
  private _previewGeneration = 0;

  // -----------------------------------------------------------------------
  // Computed data
  // -----------------------------------------------------------------------

  private get _dataFields(): FieldPath[] {
    return this.fieldPaths.filter((fp) => !fp.system && !fp.scope);
  }

  private get _scopedFields(): FieldPath[] {
    return this.fieldPaths.filter((fp) => fp.scope);
  }

  private get _systemFields(): FieldPath[] {
    return this.fieldPaths.filter((fp) => fp.system);
  }

  private get _dateFieldPaths(): Set<string> {
    return new Set(
      this.fieldPaths
        .filter((fp) => fp.type === 'date' || fp.type === 'datetime')
        .map((fp) => fp.path),
    );
  }

  private get _isPickableForBuilder(): (fp: FieldPath) => boolean {
    return (fp: FieldPath) => {
      if (fp.type === 'array' || fp.type === 'object') return false;
      const disabled = this.pathDisabled;
      return !(disabled ? disabled(fp) : null);
    };
  }

  private get _builderFields(): FieldPath[] {
    return this._dataFields.filter(
      (fp) => this._isPickableForBuilder(fp) && !fp.path.includes('[]'),
    );
  }

  private get _builderScopedFields(): FieldPath[] {
    return this._scopedFields.filter(this._isPickableForBuilder);
  }

  private get _builderSystemFields(): FieldPath[] {
    return this._systemFields.filter(this._isPickableForBuilder);
  }

  private get _selectedFieldType(): string {
    if (!this._builderFieldPath) return '';
    const fp = this.fieldPaths.find((f) => f.path === this._builderFieldPath);
    return fp ? fp.type : '';
  }

  private get _isDateFieldSelected(): boolean {
    const type = this._selectedFieldType;
    return type === 'date' || type === 'datetime';
  }

  private get _builderExpression(): string {
    if (!this._builderFieldPath) return '';
    return buildExpression({
      fieldPath: this._builderFieldPath,
      fieldType: this._selectedFieldType,
      formatType: this._isDateFieldSelected && this._builderFormatPattern ? 'date' : 'none',
      formatPattern: this._builderFormatPattern,
    });
  }

  /** The expression currently being previewed (depends on active mode). */
  private get _currentExpression(): string {
    if (this._mode === 'builder' && this.enableBuilderMode) {
      return this._builderExpression;
    }
    return this._expression;
  }

  // -----------------------------------------------------------------------
  // Lifecycle
  // -----------------------------------------------------------------------

  /**
   * Initialize internal state from current property values.
   * Called right before the dialog is shown so that all properties
   * (set by the caller after construction) are already in place.
   */
  private _initializeFromProps(): void {
    this._expression = this.initialValue;
    this._modeWarning = '';
    this._previewState = 'empty';
    this._previewText = '';

    if (!this.enableBuilderMode) {
      this._mode = 'code';
      this._builderFieldPath = '';
      this._builderFormatPattern = '';
      return;
    }

    const initialBuilderState = this.initialValue
      ? tryParseAsBuilderExpression(this.initialValue, this.fieldPaths)
      : null;

    // Check if the initial field is now disabled by pathDisabled
    const pathDisabledFn = this.pathDisabled;
    const initialFieldDisabled =
      initialBuilderState !== null && pathDisabledFn
        ? !!pathDisabledFn(
            this.fieldPaths.find((f) => f.path === initialBuilderState.fieldPath) ?? {
              path: initialBuilderState.fieldPath,
              type: 'unknown',
              system: false,
            },
          )
        : false;

    this._mode =
      this.initialValue && (!initialBuilderState || initialFieldDisabled) ? 'code' : 'builder';

    if (initialBuilderState) {
      this._builderFieldPath = initialBuilderState.fieldPath;
      this._builderFormatPattern = initialBuilderState.formatPattern;
    } else {
      this._builderFieldPath = '';
      this._builderFormatPattern = '';
    }
  }

  /**
   * Show the dialog modally and return a promise that resolves
   * when the dialog closes.
   */
  public async show(): Promise<ExpressionDialogResult> {
    return new Promise((resolve) => {
      // Defensive: if show() is called again before a previous interaction
      // completed, resolve the stale promise so the caller isn't left hanging.
      const prevResolve = this._resolve;
      if (prevResolve) prevResolve({ value: null });
      this._resolve = resolve;

      this._initializeFromProps();

      const dialog = this._dialogRef.value;
      if (dialog) {
        try {
          dialog.showModal();
        } catch {
          // showModal can throw if the dialog is already open; ignore
        }
        // Focus the appropriate input after the dialog is visible
        requestAnimationFrame(() => {
          this._focusActiveInput();
        });
      }

      // Trigger initial preview if there's an expression
      const expr = this._currentExpression.trim();
      if (expr) {
        this._schedulePreview(expr);
      }
    });
  }

  private _focusActiveInput(): void {
    if (this._mode === 'builder' && this.enableBuilderMode) {
      const fieldSelect = this.querySelector<HTMLSelectElement>('.expression-dialog-field-select');
      if (fieldSelect) fieldSelect.focus();
    } else {
      const input = this._inputRef.value;
      if (input) {
        input.focus();
        input.select();
      }
    }
  }

  /**
   * Close the dialog and resolve the pending promise.
   */
  public close(value: string | null): void {
    this._cancelPreview();
    this._previewGeneration += 1; // discard in-flight previews

    const dialog = this._dialogRef.value;
    if (dialog) {
      dialog.close();
    }
    const resolveFn = this._resolve;
    if (resolveFn) resolveFn({ value });
    this._resolve = null;
    this.remove();
  }

  // -----------------------------------------------------------------------
  // Preview
  // -----------------------------------------------------------------------

  private _cancelPreview(): void {
    if (this._previewTimer !== null) {
      clearTimeout(this._previewTimer);
      this._previewTimer = null;
    }
  }

  private _schedulePreview(expression: string): void {
    this._cancelPreview();

    if (!expression.trim()) {
      this._previewState = 'empty';
      this._previewText = '';
      return;
    }

    // Show loading immediately
    this._previewState = 'loading';
    this._previewText = 'Evaluating...';

    this._previewTimer = setTimeout(() => {
      this._runPreview(expression);
    }, 250);
  }

  private _runPreview(expression: string): void {
    const getData = this.getExampleData;
    let data: Record<string, unknown> | undefined;
    if (getData) {
      data = getData();
    }
    if (!data) {
      this._previewState = 'no-data';
      this._previewText = 'No data example selected';
      return;
    }

    const generation = this._previewGeneration + 1;
    this._previewGeneration = generation;

    tryEvaluateExpression(expression, data)
      .then((result) => {
        // Stale check — discard if a newer preview was scheduled
        if (generation !== this._previewGeneration) return;

        if (result.ok) {
          const validator = this.resultValidator;
          const validationError = validator ? validator(result.value) : null;
          if (validationError) {
            this._previewState = 'error';
            this._previewText = validationError;
          } else {
            this._previewState = 'success';
            const placeholder = formatBindingPreviewPlaceholder(result.value);
            this._previewText = `Preview: ${placeholder ?? formatForPreview(result.value)}`;
          }
        } else {
          this._previewState = 'error';
          this._previewText = result.error;
        }
      })
      .catch(() => {
        // Errors are surfaced via result.error; rejections are silently ignored
      });
  }

  // -----------------------------------------------------------------------
  // Mode switching
  // -----------------------------------------------------------------------

  private _switchMode(mode: 'builder' | 'code'): void {
    if (mode === this._mode) {
      // Clicking the already-active mode dismisses any warning
      this._modeWarning = '';
      return;
    }

    if (mode === 'builder') {
      const parsed = this._expression.trim()
        ? tryParseAsBuilderExpression(this._expression.trim(), this.fieldPaths)
        : null;

      if (this._expression.trim() && !parsed) {
        const stale = isStaleFieldReference(this._expression.trim(), this.fieldPaths);
        this._modeWarning = stale
          ? `Field '${this._expression.trim()}' not found. The loop alias may have changed.`
          : 'This expression is too complex for Builder mode.';
        return;
      }

      this._modeWarning = '';
      if (parsed) {
        this._builderFieldPath = parsed.fieldPath;
        this._builderFormatPattern = parsed.formatPattern;
      } else {
        this._builderFieldPath = '';
        this._builderFormatPattern = '';
      }
    } else {
      // Builder → Code: sync expression
      this._expression = this._builderExpression;
      this._modeWarning = '';
    }

    this._mode = mode;
    requestAnimationFrame(() => this._focusActiveInput());

    // Refresh preview for the new mode's expression
    const expr = this._currentExpression.trim();
    if (expr) {
      this._schedulePreview(expr);
    } else {
      this._previewState = 'empty';
      this._previewText = '';
    }
  }

  private _canSwitchToBuilder(): boolean {
    if (!this._expression.trim()) return true;
    return tryParseAsBuilderExpression(this._expression.trim(), this.fieldPaths) !== null;
  }

  // -----------------------------------------------------------------------
  // Builder event handlers
  // -----------------------------------------------------------------------

  private _onBuilderFieldChange(e: Event): void {
    const target = e.target;
    if (!(target instanceof HTMLSelectElement)) return;
    this._builderFieldPath = target.value;
    if (!this._isDateFieldSelected) {
      this._builderFormatPattern = '';
    }
    this._syncBuilderToCode();
  }

  private _onBuilderFormatChange(e: Event): void {
    const target = e.target;
    if (!(target instanceof HTMLSelectElement)) return;
    this._builderFormatPattern = target.value;
    this._syncBuilderToCode();
  }

  private _syncBuilderToCode(): void {
    this._expression = this._builderExpression;
    this._schedulePreview(this._builderExpression);
  }

  // -----------------------------------------------------------------------
  // Code mode event handlers
  // -----------------------------------------------------------------------

  private _onCodeInput(): void {
    const input = this._inputRef.value;
    this._expression = input ? input.value : '';
    this._autoResizeTextarea();
    this._schedulePreview(this._expression);
  }

  private _autoResizeTextarea(): void {
    const ta = this._inputRef.value;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = `${ta.scrollHeight}px`;
  }

  private _onDateFormatChange(e: Event): void {
    const target = e.target;
    if (!(target instanceof HTMLSelectElement)) return;
    const pattern = target.value;
    const barePath = this._getBarePath(this._expression.trim());
    this._expression = pattern ? wrapFormatDate(barePath, pattern) : barePath;
    this._schedulePreview(this._expression);
  }

  private _getBarePath(val: string): string {
    const parsed = parseFormatDateExpression(val);
    return parsed ? parsed.fieldPath : val;
  }

  private _codeDateFormatValue(): string {
    const parsed = parseFormatDateExpression(this._expression.trim());
    return parsed ? parsed.pattern : '';
  }

  private _showCodeDateFormat(): boolean {
    const barePath = this._getBarePath(this._expression.trim());
    return this._dateFieldPaths.has(barePath);
  }

  // -----------------------------------------------------------------------
  // Shared event handlers
  // -----------------------------------------------------------------------

  private _onCancel(): void {
    this.close(null);
  }

  private _onSubmit(e: Event): void {
    e.preventDefault();
    let value: string;
    if (this._mode === 'builder') {
      value = this._builderExpression.trim();
    } else {
      value = this._expression.trim();
    }
    this.close(value || null);
  }

  private _onKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
      e.preventDefault();
      this.close(null);
    }
  }

  private _onBackdropClick(e: MouseEvent): void {
    if (e.target === this._dialogRef.value) {
      this.close(null);
    }
  }

  // -----------------------------------------------------------------------
  // Validation helpers
  // -----------------------------------------------------------------------

  private _inputValidationClass(): string {
    const val = this._expression.trim();
    if (!val) return '';
    return isValidExpression(val) ? 'valid' : 'invalid';
  }

  // -----------------------------------------------------------------------
  // Render helpers
  // -----------------------------------------------------------------------

  private _renderFieldOption(fp: FieldPath): TemplateResult {
    return html`
      <option value="${fp.path}" data-type="${formatFieldPathTypeLabel(fp)}">${fp.path}</option>
    `;
  }

  private _renderFieldOptions(): TemplateResult | typeof nothing {
    return html`
      <option value="">Select a field...</option>
      ${this._builderFields.length > 0
        ? html`
            <optgroup label="Template variables">
              ${this._builderFields.map((fp) => this._renderFieldOption(fp))}
            </optgroup>
          `
        : nothing}
      ${this._builderScopedFields.length > 0
        ? html`
            <optgroup label="Iteration variables">
              ${this._builderScopedFields.map((fp) => this._renderFieldOption(fp))}
            </optgroup>
          `
        : nothing}
      ${this._builderSystemFields.length > 0
        ? html`
            <optgroup label="System parameters">
              ${this._builderSystemFields.map((fp) => this._renderFieldOption(fp))}
            </optgroup>
          `
        : nothing}
    `;
  }

  private _renderFormatOptions(): TemplateResult[] {
    return DATE_FORMAT_PRESETS.map((p) => html`<option value="${p.value}">${p.label}</option>`);
  }

  private _renderModeToggle(): TemplateResult {
    const builderDisabled = !this._canSwitchToBuilder();
    return html`
      <div class="expression-dialog-header">
        <label class="expression-dialog-label" for="expression-dialog-input"> ${this.label} </label>
        <div class="expression-dialog-mode-toggle">
          <button
            type="button"
            class="mode-btn ${this._mode === 'builder' ? 'active' : ''} ${builderDisabled
              ? 'disabled'
              : ''}"
            data-mode="builder"
            @click=${(): void => this._switchMode('builder')}
          >
            Builder
          </button>
          <button
            type="button"
            class="mode-btn ${this._mode === 'code' ? 'active' : ''}"
            data-mode="code"
            @click=${(): void => this._switchMode('code')}
          >
            Code
          </button>
        </div>
      </div>
    `;
  }

  private _renderBuilderPanel(): TemplateResult {
    return html`
      <div
        class="expression-dialog-builder"
        data-mode-panel="builder"
        style="display: ${this._mode === 'builder' ? '' : 'none'}"
      >
        <div class="expression-dialog-builder-row">
          <div class="expression-dialog-builder-field">
            <label for="expression-dialog-field">Field</label>
            <select
              class="expression-dialog-field-select"
              id="expression-dialog-field"
              .value=${this._builderFieldPath}
              @change=${this._onBuilderFieldChange}
            >
              ${this._renderFieldOptions()}
            </select>
          </div>
          <div
            class="expression-dialog-builder-format"
            style="display: ${this._isDateFieldSelected ? '' : 'none'}"
          >
            <label for="expression-dialog-builder-format">Format</label>
            <select
              class="expression-dialog-builder-format-select"
              id="expression-dialog-builder-format"
              .value=${this._builderFormatPattern}
              @change=${this._onBuilderFormatChange}
            >
              ${this._renderFormatOptions()}
            </select>
          </div>
        </div>
      </div>
    `;
  }

  private _renderModeWarning(): TemplateResult | typeof nothing {
    if (!this._modeWarning) return nothing;
    return html` <div class="expression-dialog-mode-warning">${this._modeWarning}</div> `;
  }

  private _renderCodePanel(): TemplateResult {
    return html`
      <div
        class="expression-dialog-code"
        data-mode-panel="code"
        style="display: ${this._mode === 'code' || !this.enableBuilderMode ? '' : 'none'}"
      >
        <textarea
          class="expression-dialog-input ${this._inputValidationClass()}"
          id="expression-dialog-input"
          ${ref(this._inputRef)}
          .value=${this._expression}
          placeholder=${this.placeholder}
          autocomplete="off"
          rows="1"
          @input=${this._onCodeInput}
        ></textarea>
        <div
          class="expression-dialog-format-row"
          style="display: ${this._showCodeDateFormat() ? '' : 'none'}"
        >
          <label class="expression-dialog-format-label" for="expression-dialog-date-format">
            Date format
          </label>
          <select
            class="expression-dialog-format-select"
            id="expression-dialog-date-format"
            .value=${this._codeDateFormatValue()}
            @change=${this._onDateFormatChange}
          >
            ${this._renderFormatOptions()}
          </select>
        </div>
      </div>
    `;
  }

  private _renderPreview(): TemplateResult {
    const stateClass =
      this._previewState === 'success'
        ? 'success'
        : this._previewState === 'error'
          ? 'error'
          : this._previewState === 'no-data'
            ? 'no-data'
            : this._previewState === 'loading'
              ? 'loading'
              : 'empty';

    const text =
      this._previewState === 'empty' ? 'Start typing to see a preview...' : this._previewText;

    return html`
      <div class="expression-dialog-preview ${stateClass}" aria-live="polite" aria-atomic="true">
        ${text}
      </div>
    `;
  }

  // -----------------------------------------------------------------------
  // Field paths
  // -----------------------------------------------------------------------

  private _onFieldFilterInput(e: Event): void {
    const target = e.target;
    if (!(target instanceof HTMLInputElement)) return;
    this._fieldFilter = target.value.toLowerCase();
  }

  private _insertFieldPath(path: string): void {
    this._expression = path;
    this._schedulePreview(path);
    // Focus back on the textarea
    const input = this._inputRef.value;
    if (input) input.focus();
  }

  private _renderFieldPathItem(fp: FieldPath): typeof nothing | ReturnType<typeof html> {
    const pathDisabledFn = this.pathDisabled;
    const fieldPathFilterFn = this.fieldPathFilter;
    const disabledReason = pathDisabledFn ? pathDisabledFn(fp) : null;
    const highlighted = fieldPathFilterFn ? fieldPathFilterFn(fp) : false;
    const matchesFilter = fp.path.toLowerCase().includes(this._fieldFilter);

    if (!matchesFilter) return nothing;

    return html`
      <li
        class="expression-dialog-path-item ${highlighted ? 'highlighted' : ''} ${disabledReason !==
        null
          ? 'disabled'
          : ''} ${fp.scope ? 'scoped' : ''} ${fp.system ? 'system' : ''}"
        tabindex="${disabledReason !== null ? '-1' : '0'}"
        role="button"
        title="${disabledReason !== null ? disabledReason : (fp.description ?? '')}"
        aria-disabled="${disabledReason !== null ? 'true' : 'false'}"
        @click=${() => {
          if (disabledReason === null) this._insertFieldPath(fp.path);
        }}
        @keydown=${(e: KeyboardEvent) => {
          if (disabledReason === null && (e.key === 'Enter' || e.key === ' ')) {
            e.preventDefault();
            this._insertFieldPath(fp.path);
          }
        }}
      >
        <span class="expression-dialog-path-name">${fp.path}</span>
        <span class="expression-dialog-path-type">${formatFieldPathTypeLabel(fp)}</span>
      </li>
    `;
  }

  private _renderFieldPathsSection(
    label: string,
    fields: FieldPath[],
  ): typeof nothing | ReturnType<typeof html> {
    const visible = fields.filter((fp) => fp.path.toLowerCase().includes(this._fieldFilter));
    if (visible.length === 0) return nothing;

    return html`
      <li class="expression-dialog-section-header">${label}</li>
      ${visible.map((fp) => this._renderFieldPathItem(fp))}
    `;
  }

  private _renderFieldPaths(): TemplateResult {
    if (this.fieldPaths.length === 0) {
      return html`
        <div class="expression-dialog-paths">
          <div class="expression-dialog-paths-empty">No fields available</div>
        </div>
      `;
    }

    const dataFields = this._dataFields;
    const scopedFields = this._scopedFields;
    const systemFields = this._systemFields;

    return html`
      <div class="expression-dialog-paths">
        <div class="expression-dialog-paths-header">
          <span>Available fields</span>
          <input
            type="text"
            class="expression-dialog-filter"
            placeholder="Filter..."
            .value=${this._fieldFilter}
            @input=${this._onFieldFilterInput}
          />
        </div>
        <ul class="expression-dialog-paths-list">
          ${this._renderFieldPathsSection('Template variables', dataFields)}
          ${this._renderFieldPathsSection('Iteration variables', scopedFields)}
          ${this._renderFieldPathsSection('System parameters', systemFields)}
        </ul>
      </div>
    `;
  }

  // -----------------------------------------------------------------------
  // Quick reference
  // -----------------------------------------------------------------------

  private _insertQuickRef(code: string): void {
    this._expression = code;
    this._schedulePreview(code);
    const input = this._inputRef.value;
    if (input) input.focus();
  }

  private _renderQuickReference(): TemplateResult {
    return html`
      <details class="expression-dialog-reference" ?open=${this._refExpanded}>
        <summary class="expression-dialog-ref-summary">JSONata Quick Reference</summary>
        <div class="expression-dialog-ref-list">
          ${JSONATA_QUICK_REFERENCE.map(
            (entry) => html`
              <div
                class="expression-dialog-ref-row"
                tabindex="0"
                role="button"
                @click=${(): void => this._insertQuickRef(entry.code)}
                @keydown=${(e: KeyboardEvent): void => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    this._insertQuickRef(entry.code);
                  }
                }}
              >
                <code class="expression-dialog-ref-code">${entry.code}</code>
                <span class="expression-dialog-ref-desc">${entry.desc}</span>
              </div>
            `,
          )}
        </div>
      </details>
    `;
  }

  // -----------------------------------------------------------------------
  // Render
  // -----------------------------------------------------------------------

  public override render(): TemplateResult | typeof nothing {
    return html`
      <dialog
        class="expression-dialog"
        ${ref(this._dialogRef)}
        @keydown=${this._onKeydown}
        @click=${this._onBackdropClick}
      >
        <form method="dialog" class="expression-dialog-form" @submit=${this._onSubmit}>
          ${this.enableBuilderMode
            ? this._renderModeToggle()
            : html`
                <label class="expression-dialog-label" for="expression-dialog-input">
                  ${this.label}
                </label>
              `}
          ${this.enableBuilderMode ? this._renderBuilderPanel() : nothing}
          ${this.enableBuilderMode ? this._renderModeWarning() : nothing} ${this._renderCodePanel()}
          ${this._renderPreview()} ${this._renderFieldPaths()} ${this._renderQuickReference()}
          <div class="expression-dialog-actions">
            <button type="button" class="expression-dialog-btn cancel" @click=${this._onCancel}>
              Cancel
            </button>
            <button type="submit" class="expression-dialog-btn save">Save</button>
          </div>
        </form>
      </dialog>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'ep-expression-dialog': EpExpressionDialog;
  }
}
