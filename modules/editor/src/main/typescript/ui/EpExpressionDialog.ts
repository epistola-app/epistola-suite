import { LitElement, html, nothing, type TemplateResult } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { createRef, ref, type Ref } from 'lit/directives/ref.js';
import type { FieldPath } from '../engine/schema-paths.js';
import {
  formatFieldPathTypeLabel,
  formatBindingPreview,
} from '../data-contract/binding-compatibility.js';
import {
  isValidExpression,
  tryEvaluateExpression,
  formatForPreview,
  formatDateValue,
  formatLocaleNumberValue,
} from '../engine/resolve-expression.js';
import { DEFAULT_LOCALE } from '../engine/locale.js';
import {
  parseFormatDateExpression,
  wrapFormatDate,
  parseFormatLocaleNumberExpression,
  wrapFormatLocaleNumber,
  tryParseAsBuilderExpression,
  buildExpression,
  isStaleFieldReference,
  type BuilderState,
} from './expression-builder.js';
import { JSONATA_QUICK_REFERENCE } from './expression-dialog.js';
import { icon } from './icons.js';
import type { ExpressionDialogResult } from './expression-dialog.js';

/*
 * Lit binds `this` automatically when using `@event=${this.handler}` syntax,
 * so `unbound-method` warnings are false positives in this file.
 * See: https://lit.dev/docs/components/events/#understanding-this-in-event-listeners
 */
/* eslint-disable unbound-method */

/** Sample instant used to render locale-aware date format examples. */
const DATE_FORMAT_SAMPLE = '2024-01-15T14:30:00';

/** Date-only format patterns offered by the format dropdown, in display order. */
const DATE_FORMAT_PATTERNS: string[] = [
  'dd-MM-yyyy',
  'yyyy-MM-dd',
  'dd/MM/yyyy',
  'MM/dd/yyyy',
  'd MMMM yyyy',
  'EEEE MMMM d yyyy',
  'EEEE d MMMM yyyy',
];

/**
 * Patterns offered only for date-time fields — they include a time-of-day
 * component, which is meaningless for a plain `date` field (whose value carries
 * no time), so they are hidden when a `date` field is selected.
 */
const DATE_TIME_FORMAT_PATTERNS: string[] = ['dd-MM-yyyy HH:mm', 'yyyy-MM-dd HH:mm'];

/**
 * Number format presets for the format dropdown. `value` is the
 * `$formatLocaleNumber` picture; `name` is the human-readable label; `sample`
 * is a representative number used to render a *live, locale-aware* example next
 * to the name (so a Dutch session sees `1.234,56` where an en-US session sees
 * `1,234.56` — the separators follow the culture, exactly like the output).
 */
const NUMBER_FORMAT_PRESET_DEFS: { value: string; name: string; sample?: number }[] = [
  { value: '', name: 'No formatting' },
  { value: '#,##0', name: 'Whole number', sample: 1234 },
  { value: '#,##0.00', name: 'Decimal, grouped', sample: 1234.5 },
  { value: '0', name: 'Whole number, no grouping', sample: 1234 },
  { value: '0.00', name: 'Decimal, no grouping', sample: 1234.5 },
  { value: '0%', name: 'Percentage', sample: 0.21 },
  { value: '0.0%', name: 'Percentage, 1 decimal', sample: 0.21 },
];

/** Non-empty number pictures the builder's Format dropdown can represent. */
const BUILDER_NUMBER_FORMAT_PATTERNS: ReadonlySet<string> = new Set(
  NUMBER_FORMAT_PRESET_DEFS.map((p) => p.value).filter((v) => v !== ''),
);

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
  /**
   * BCP-47 locale (variant attribute → tenant default → app default) used to
   * format the live preview and the number-format example labels, so both
   * match the generated PDF. Falls back to `"en-US"` when absent.
   */
  @property({ type: String }) public locale = DEFAULT_LOCALE;
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
  @state() private _refExpanded = false;

  private _dialogRef: Ref<HTMLDialogElement> = createRef();
  private _inputRef: Ref<HTMLTextAreaElement> = createRef();
  private _resolve: ((value: ExpressionDialogResult) => void) | null = null;
  private _previewTimer: ReturnType<typeof setTimeout> | null = null;
  private _warningTimer: ReturnType<typeof setTimeout> | null = null;
  private _previewGeneration = 0;

  // -----------------------------------------------------------------------
  // Computed data
  // -----------------------------------------------------------------------

  private get _dataFields(): FieldPath[] {
    return this.fieldPaths.filter((fp) => !fp.system && !fp.scope);
  }

  private get _iterationFields(): FieldPath[] {
    return this.fieldPaths.filter(
      (fp) => fp.scope && (fp.scopeKind === 'iteration' || !fp.scopeKind),
    );
  }

  private get _stencilParameterFields(): FieldPath[] {
    return this.fieldPaths.filter((fp) => fp.scope && fp.scopeKind === 'stencil-parameter');
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

  private get _numberFieldPaths(): Set<string> {
    return new Set(
      this.fieldPaths
        .filter((fp) => fp.type === 'number' || fp.type === 'integer')
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

  private get _builderIterationFields(): FieldPath[] {
    return this._iterationFields.filter(this._isPickableForBuilder);
  }

  private get _builderStencilParameterFields(): FieldPath[] {
    return this._stencilParameterFields.filter(this._isPickableForBuilder);
  }

  private get _builderSystemFields(): FieldPath[] {
    return this._systemFields.filter(this._isPickableForBuilder);
  }

  /** The declared type of the field at `path`, or `''` when unknown. */
  private _fieldTypeForPath(path: string): string {
    if (!path) return '';
    const fp = this.fieldPaths.find((f) => f.path === path);
    return fp ? fp.type : '';
  }

  private get _selectedFieldType(): string {
    return this._fieldTypeForPath(this._builderFieldPath);
  }

  /**
   * Date patterns to offer for a field of `fieldType`. A `datetime` field gets
   * both date-only and date-time patterns; a plain `date` field gets only the
   * date-only patterns (time-of-day patterns make no sense without a time).
   */
  private _dateFormatPatternsFor(fieldType: string): string[] {
    return fieldType === 'datetime'
      ? [...DATE_FORMAT_PATTERNS, ...DATE_TIME_FORMAT_PATTERNS]
      : DATE_FORMAT_PATTERNS;
  }

  private get _isDateFieldSelected(): boolean {
    const type = this._selectedFieldType;
    return type === 'date' || type === 'datetime';
  }

  private get _isNumberFieldSelected(): boolean {
    const type = this._selectedFieldType;
    return type === 'number' || type === 'integer';
  }

  /** The format kind offered for the currently selected builder field. */
  private get _selectedFormatType(): 'none' | 'date' | 'number' {
    if (this._isDateFieldSelected) return 'date';
    if (this._isNumberFieldSelected) return 'number';
    return 'none';
  }

  /**
   * Number-format presets with locale-aware example labels, e.g.
   * `Decimal, grouped (1.234,50)` for `nl-NL` and `(1,234.50)` for `en-US`.
   */
  private _numberFormatPresets(): { value: string; label: string }[] {
    return NUMBER_FORMAT_PRESET_DEFS.map((preset) => {
      if (preset.sample === undefined) return { value: preset.value, label: preset.name };
      const example = formatLocaleNumberValue(preset.sample, preset.value, this.locale);
      return { value: preset.value, label: `${preset.name} (${example})` };
    });
  }

  /**
   * Date-format presets with locale-aware example labels, e.g.
   * `d MMMM yyyy (15 januari 2024)` for `nl-NL`. Numeric patterns render the
   * same in every locale (by design — only month-name tokens localize, mirroring
   * the PDF renderer), so those examples don't change between cultures.
   */
  private _dateFormatPresets(fieldType: string): { value: string; label: string }[] {
    return [
      { value: '', label: 'No formatting' },
      ...this._dateFormatPatternsFor(fieldType).map((pattern) => ({
        value: pattern,
        label: `${pattern} (${formatDateValue(DATE_FORMAT_SAMPLE, pattern, this.locale)})`,
      })),
    ];
  }

  /** The preset list matching the selected field's format kind. */
  private get _selectedFormatPresets(): { value: string; label: string }[] {
    return this._selectedFormatType === 'number'
      ? this._numberFormatPresets()
      : this._dateFormatPresets(this._selectedFieldType);
  }

  /** Non-empty patterns the builder can represent for the selected field's format kind. */
  private get _selectedFormatPatterns(): ReadonlySet<string> {
    if (this._selectedFormatType === 'date') {
      return new Set(this._dateFormatPatternsFor(this._selectedFieldType));
    }
    if (this._selectedFormatType === 'number') return BUILDER_NUMBER_FORMAT_PATTERNS;
    return new Set();
  }

  private get _builderExpression(): string {
    if (!this._builderFieldPath) return '';
    return buildExpression({
      fieldPath: this._builderFieldPath,
      fieldType: this._selectedFieldType,
      formatType: this._builderFormatPattern ? this._selectedFormatType : 'none',
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

    const initialBuilderState = this.initialValue ? this._parseBuilder(this.initialValue) : null;

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
    this._cancelWarningTimer();
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

    tryEvaluateExpression(expression, data, this.locale)
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
            const richTextPreview = formatBindingPreview(result.value);
            this._previewText = `Preview: ${richTextPreview ?? formatForPreview(result.value)}`;
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
      this._clearModeWarning();
      return;
    }

    if (mode === 'builder') {
      const trimmed = this._expression.trim();
      const parsed = trimmed ? this._parseBuilder(trimmed) : null;

      if (trimmed && !parsed) {
        if (this._hasUnsupportedBuilderFormat(trimmed)) {
          const isNumberFormat = parseFormatLocaleNumberExpression(trimmed) !== null;
          this._modeWarning = isNumberFormat
            ? "This number format isn't available in Builder mode — edit it in Code."
            : "This date format isn't available in Builder mode — edit it in Code.";
        } else {
          const stale = isStaleFieldReference(trimmed, this.fieldPaths);
          this._modeWarning = stale
            ? `Field '${trimmed}' not found. It may have been renamed, removed, or its scope may have changed.`
            : 'This expression is too complex for Builder mode.';
        }
        this._scheduleWarningDismiss();
        return;
      }

      this._clearModeWarning();
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
      this._clearModeWarning();
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
    return this._parseBuilder(this._expression.trim()) !== null;
  }

  /**
   * Parse for builder mode. Rejects a `$formatDate` whose pattern the
   * builder's preset dropdown can't represent (a custom format) — those
   * belong in Code, where the raw pattern is visible and editable. Without
   * this the builder would show an empty Format dropdown while silently
   * holding (and risking loss of) the custom pattern.
   */
  private _parseBuilder(expr: string): BuilderState | null {
    const parsed = tryParseAsBuilderExpression(expr, this.fieldPaths);
    if (!parsed) return null;
    // Accept a date pattern only if it's valid for THIS field's type — a plain
    // `date` field must not keep a time-of-day pattern. Checking the field-
    // specific set (not the union) here means an existing time pattern on a
    // date field opens in code mode with the "unsupported format" warning,
    // rather than silently surviving in a builder dropdown that can't show it.
    if (
      parsed.formatType === 'date' &&
      !this._dateFormatPatternsFor(this._fieldTypeForPath(parsed.fieldPath)).includes(
        parsed.formatPattern,
      )
    ) {
      return null;
    }
    if (
      parsed.formatType === 'number' &&
      !BUILDER_NUMBER_FORMAT_PATTERNS.has(parsed.formatPattern)
    ) {
      return null;
    }
    return parsed;
  }

  /**
   * True when `expr` is a valid `$formatDate` on a known field but uses a
   * pattern the builder can't represent — distinguishes it from genuinely
   * complex JSONata so we can show a precise warning.
   */
  private _hasUnsupportedBuilderFormat(expr: string): boolean {
    return (
      tryParseAsBuilderExpression(expr, this.fieldPaths) !== null &&
      this._parseBuilder(expr) === null
    );
  }

  private _clearModeWarning(): void {
    this._cancelWarningTimer();
    this._modeWarning = '';
  }

  private _cancelWarningTimer(): void {
    if (this._warningTimer !== null) {
      clearTimeout(this._warningTimer);
      this._warningTimer = null;
    }
  }

  private _scheduleWarningDismiss(): void {
    this._cancelWarningTimer();
    this._warningTimer = setTimeout(() => {
      this._modeWarning = '';
    }, 3000);
  }

  // -----------------------------------------------------------------------
  // Builder event handlers
  // -----------------------------------------------------------------------

  private _onBuilderFieldChange(e: Event): void {
    const target = e.target;
    if (!(target instanceof HTMLSelectElement)) return;
    this._builderFieldPath = target.value;
    // Drop the chosen pattern unless it's still valid for the new field's
    // format kind — switching date↔number (or to an unformattable type)
    // must not leave a stale pattern that builds a nonsensical expression.
    if (!this._selectedFormatPatterns.has(this._builderFormatPattern)) {
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
    const maxHeight = 160; // must match CSS max-height
    ta.style.height = `${Math.min(ta.scrollHeight, maxHeight)}px`;
  }

  private _onCodeFormatChange(e: Event): void {
    const target = e.target;
    if (!(target instanceof HTMLSelectElement)) return;
    const pattern = target.value;
    const barePath = this._getBarePath(this._expression.trim());
    if (!pattern) {
      this._expression = barePath;
    } else if (this._codeFormatType() === 'number') {
      this._expression = wrapFormatLocaleNumber(barePath, pattern);
    } else {
      this._expression = wrapFormatDate(barePath, pattern);
    }
    this._schedulePreview(this._expression);
  }

  private _getBarePath(val: string): string {
    const date = parseFormatDateExpression(val);
    if (date) return date.fieldPath;
    const number = parseFormatLocaleNumberExpression(val);
    if (number) return number.fieldPath;
    return val;
  }

  private _codeFormatValue(): string {
    const trimmed = this._expression.trim();
    const date = parseFormatDateExpression(trimmed);
    if (date) return date.pattern;
    const number = parseFormatLocaleNumberExpression(trimmed);
    if (number) return number.pattern;
    return '';
  }

  /** The format kind offered in the code box for the current bare path. */
  private _codeFormatType(): 'none' | 'date' | 'number' {
    const barePath = this._getBarePath(this._expression.trim());
    if (this._dateFieldPaths.has(barePath)) return 'date';
    if (this._numberFieldPaths.has(barePath)) return 'number';
    return 'none';
  }

  private _showCodeFormat(): boolean {
    // Formatting is the builder's job when builder mode is available.
    // The code box only offers it for code-only dialogs (no builder), which
    // is the inspector's conditional/loop expression fields.
    if (this.enableBuilderMode) return false;
    return this._codeFormatType() !== 'none';
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
      ${this._builderIterationFields.length > 0
        ? html`
            <optgroup label="Iteration variables">
              ${this._builderIterationFields.map((fp) => this._renderFieldOption(fp))}
            </optgroup>
          `
        : nothing}
      ${this._builderStencilParameterFields.length > 0
        ? html`
            <optgroup label="Stencil parameters">
              ${this._builderStencilParameterFields.map((fp) => this._renderFieldOption(fp))}
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

  private _renderFormatOptions(
    presets: { value: string; label: string }[],
    selected: string,
  ): TemplateResult[] {
    // Mark the selected option explicitly rather than relying solely on the
    // <select>'s `.value` binding: when the preset list itself changes
    // (date↔number), Lit commits `.value` against the previous options and the
    // value fails to stick. `?selected` is set as the options render, so it wins.
    return presets.map(
      (p) => html`<option value="${p.value}" ?selected=${p.value === selected}>${p.label}</option>`,
    );
  }

  private _renderModeToggle(): TemplateResult {
    const builderDisabled = !this._canSwitchToBuilder();
    return html`
      <div class="expression-dialog-header">
        <span class="expression-dialog-title">${this.label}</span>
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
            <label class="expression-dialog-field-label" for="expression-dialog-field">Field</label>
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
            style="display: ${this._selectedFormatType !== 'none' ? '' : 'none'}"
          >
            <label class="expression-dialog-field-label" for="expression-dialog-builder-format">
              Format
            </label>
            <select
              class="expression-dialog-builder-format-select"
              id="expression-dialog-builder-format"
              .value=${this._builderFormatPattern}
              @change=${this._onBuilderFormatChange}
            >
              ${this._renderFormatOptions(this._selectedFormatPresets, this._builderFormatPattern)}
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

  /**
   * Builder-mode placeholder shown where the field picker / quick reference
   * would otherwise be. The full field list and JSONata reference are
   * code-mode tools — point the user there instead of surfacing them in a
   * mode that can't represent what they'd build with them.
   */
  private _renderBuilderCodeHint(): TemplateResult {
    return html`
      <div class="expression-dialog-builder-hint">
        Need a field reference or an advanced expression? Switch to
        <button
          type="button"
          class="expression-dialog-builder-hint-link"
          @click=${(): void => this._switchMode('code')}
        >
          Code
        </button>
        for the full field list and JSONata quick reference.
      </div>
    `;
  }

  private _renderCodePanel(): TemplateResult {
    return html`
      <div
        class="expression-dialog-code"
        data-mode-panel="code"
        style="display: ${this._mode === 'code' || !this.enableBuilderMode ? '' : 'none'}"
      >
        ${this.enableBuilderMode
          ? html`<label class="expression-dialog-field-label" for="expression-dialog-input"
              >Expression</label
            >`
          : nothing}
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
          style="display: ${this._showCodeFormat() ? '' : 'none'}"
        >
          <label class="expression-dialog-format-label" for="expression-dialog-date-format">
            ${this._codeFormatType() === 'number' ? 'Number format' : 'Date format'}
          </label>
          <select
            class="expression-dialog-format-select"
            id="expression-dialog-date-format"
            .value=${this._codeFormatValue()}
            @change=${this._onCodeFormatChange}
          >
            ${this._renderFormatOptions(
              this._codeFormatType() === 'number'
                ? this._numberFormatPresets()
                : this._dateFormatPresets(
                    this._fieldTypeForPath(this._getBarePath(this._expression.trim())),
                  ),
              this._codeFormatValue(),
            )}
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

    const emptyText =
      this._mode === 'builder' && this.enableBuilderMode
        ? 'Select a field to see a preview'
        : 'Start typing to see a preview';
    const text = this._previewState === 'empty' ? emptyText : this._previewText;

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
    const iterationFields = this._iterationFields;
    const stencilParameterFields = this._stencilParameterFields;
    const systemFields = this._systemFields;

    return html`
      <div class="expression-dialog-paths">
        <div class="expression-dialog-paths-header">
          <span>Available fields</span>
          <div class="expression-dialog-filter-wrap">
            <input
              type="text"
              class="expression-dialog-filter"
              placeholder="Filter..."
              aria-label="Filter available fields"
              .value=${this._fieldFilter}
              @input=${this._onFieldFilterInput}
            />
            <span class="expression-dialog-filter-icon" aria-hidden="true">
              ${icon('search', 16)}
            </span>
          </div>
        </div>
        <ul class="expression-dialog-paths-list">
          ${this._renderFieldPathsSection('Template variables', dataFields)}
          ${this._renderFieldPathsSection('Iteration variables', iterationFields)}
          ${this._renderFieldPathsSection('Stencil parameters', stencilParameterFields)}
          ${this._renderFieldPathsSection('System parameters', systemFields)}
        </ul>
      </div>
    `;
  }

  // -----------------------------------------------------------------------
  // Quick reference
  // -----------------------------------------------------------------------

  private _insertQuickRef(code: string): void {
    if (this._mode === 'builder' && this.enableBuilderMode) {
      this._switchMode('code');
    }
    this._expression = code;
    this._schedulePreview(code);
    requestAnimationFrame(() => {
      const input = this._inputRef.value;
      if (input) input.focus();
    });
  }

  /** Keep `_refExpanded` in sync with native <details> toggling so a
   * re-render doesn't reset the disclosure to its default state. */
  private _onRefToggle(e: Event): void {
    const details = e.target;
    if (details instanceof HTMLDetailsElement) {
      this._refExpanded = details.open;
    }
  }

  private _renderQuickReference(): TemplateResult {
    return html`
      <details
        class="expression-dialog-reference"
        ?open=${this._refExpanded}
        @toggle=${this._onRefToggle}
      >
        <summary class="expression-dialog-ref-summary">
          <span>JSONata quick reference</span>
          <span class="expression-dialog-ref-chevron" aria-hidden="true">
            ${icon('chevron-right', 16)}
          </span>
        </summary>
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
    // The field picker and quick reference only make sense in code mode —
    // they write raw expressions the builder `<select>` can't represent.
    // In builder mode show a hint pointing to Code instead.
    const showCodeTools = !this.enableBuilderMode || this._mode === 'code';
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
                <label class="expression-dialog-field-label" for="expression-dialog-input">
                  ${this.label}
                </label>
              `}
          <div class="expression-dialog-body">
            ${this.enableBuilderMode ? this._renderBuilderPanel() : nothing}
            ${this.enableBuilderMode ? this._renderModeWarning() : nothing}
            ${this._renderCodePanel()} ${this._renderPreview()}
            ${showCodeTools ? this._renderFieldPaths() : this._renderBuilderCodeHint()}
            ${showCodeTools ? this._renderQuickReference() : nothing}
          </div>
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
