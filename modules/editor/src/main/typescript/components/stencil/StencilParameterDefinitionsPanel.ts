/**
 * StencilParameterDefinitionsPanel — author UI for declaring a stencil's
 * input parameters.
 *
 * Two-panel layout matching the data-contract editor:
 *   - Left: compact, clickable list of parameters (name + type badge).
 *   - Right: detail form for the selected parameter (name, type, list flag,
 *     required, description, default).
 *
 * Lives inside the parameter-definitions dialog opened from the stencil
 * inspector. Emits `parameter-schema-change` with a full JsonSchema; the
 * host (the inspector) drops it onto the stencil node's `parameterSchemaSnapshot`
 * prop, and `saveDraft` persists it via `PUT /draft`.
 *
 * V1 surfaces the primitive subset users actually need (string / number /
 * integer / boolean / date / date-time, and "list of <primitive>"). The
 * canonical storage stays JSON Schema, so v2 can lift restrictions without
 * a migration.
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { JsonSchema, JsonSchemaProperty } from '../../data-contract/types.js';

interface ParamRow {
  /** Stable identifier so list selection survives renames. */
  id: string;
  name: string;
  type: 'string' | 'number' | 'integer' | 'boolean' | 'date' | 'date-time';
  isList: boolean;
  required: boolean;
  description: string;
  defaultText: string;
}

const NAME_RE = /^[a-z][a-zA-Z0-9_]{0,63}$/;
const RESERVED = new Set(['params', 'item', 'sys', 'index']);

const TYPE_OPTIONS: Array<{ value: ParamRow['type']; label: string }> = [
  { value: 'string', label: 'String' },
  { value: 'number', label: 'Number' },
  { value: 'integer', label: 'Integer' },
  { value: 'boolean', label: 'Boolean' },
  { value: 'date', label: 'Date' },
  { value: 'date-time', label: 'Date-time' },
];

@customElement('stencil-parameter-definitions-panel')
export class StencilParameterDefinitionsPanel extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) schema?: JsonSchema;

  @state() private _rows: ParamRow[] = [];
  @state() private _errors: Record<string, string> = {};
  @state() private _selectedId: string | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    this._rows = parseSchemaToRows(this.schema);
    if (this._rows.length > 0 && this._selectedId == null) {
      this._selectedId = this._rows[0].id;
    }
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('schema')) {
      this._rows = parseSchemaToRows(this.schema);
      if (
        this._rows.length > 0 &&
        (this._selectedId == null || !this._rows.some((r) => r.id === this._selectedId))
      ) {
        this._selectedId = this._rows[0].id;
      }
    }
  }

  override render() {
    const selected = this._rows.find((r) => r.id === this._selectedId);
    return html`
      <div class="stencil-params-panel">
        <p class="stencil-picker-muted" style="margin: 0;">
          Define the parameters that consumers must bind when inserting this stencil. Each parameter
          becomes a variable available inside the stencil under the configured alias (default
          <code>params</code>).
        </p>
        <div class="stencil-params-note" role="note">
          Schema changes only reach existing consumers when this draft is
          <strong>published</strong> and each consuming template <strong>upgrades</strong> the
          stencil. Until then, consumers continue to validate and render against the previously
          published schema.
        </div>

        <div class="stencil-picker-flex-row">
          <button type="button" class="ep-btn ep-btn-outline ep-btn-sm" @click=${this._addRow}>
            + Add parameter
          </button>
          <span class="stencil-picker-muted">
            ${this._rows.length} ${this._rows.length === 1 ? 'parameter' : 'parameters'}
          </span>
        </div>

        ${this._rows.length === 0 ? this._renderEmpty() : this._renderTwoPanel(selected)}
      </div>
    `;
  }

  private _renderEmpty() {
    return html`
      <div class="stencil-picker-muted stencil-params-empty">
        No parameters defined. Add one to make a value (e.g. recipient name, page number)
        configurable per insertion.
      </div>
    `;
  }

  private _renderTwoPanel(selected: ParamRow | undefined) {
    return html`
      <div class="stencil-params-two-panel">
        ${this._renderList()} ${this._renderDetail(selected)}
      </div>
    `;
  }

  private _renderList() {
    return html`
      <div class="stencil-params-box stencil-params-box-scroll">
        ${this._rows.map((row) => this._renderListItem(row))}
      </div>
    `;
  }

  private _renderListItem(row: ParamRow) {
    const isSelected = row.id === this._selectedId;
    const hasError = row.id in this._errors;
    const typeLabel = row.isList ? `${row.type}[]` : row.type;
    return html`
      <div
        @click=${() => this._select(row.id)}
        class="stencil-params-list-item"
        style=${`background: ${isSelected ? 'var(--ep-accent, #eef2ff)' : 'transparent'};`}
      >
        <span
          class="stencil-params-list-name"
          style=${`
            font-weight: ${isSelected ? '600' : '500'};
            color: ${hasError ? 'var(--ep-destructive, #dc2626)' : 'inherit'};
          `}
          >${row.name || '(unnamed)'}</span
        >
        <span class="stencil-picker-badge">${typeLabel}</span>
        ${row.required
          ? html`<span title="Required" class="stencil-params-required-dot"></span>`
          : nothing}
      </div>
    `;
  }

  private _renderDetail(row: ParamRow | undefined) {
    if (!row) {
      return html`<div class="stencil-params-detail-empty">Select a parameter to edit</div>`;
    }
    const error = this._errors[row.id];
    return html`
      <div class="stencil-params-detail">
        <div class="stencil-params-header">
          <h4 class="stencil-params-heading">${row.name || '(unnamed)'}</h4>
          <button
            type="button"
            class="ep-btn ep-btn-outline ep-btn-destructive ep-btn-sm stencil-picker-error"
            @click=${() => this._removeRow(row.id)}
          >
            Delete
          </button>
        </div>

        ${this._renderDetailRow(
          'Name',
          html`<input
            type="text"
            class="ep-input stencil-picker-full"
            style=${error ? 'border-color: var(--ep-destructive, #dc2626);' : ''}
            .value=${row.name}
            @input=${(e: Event) =>
              this._updateRow(row.id, { name: (e.target as HTMLInputElement).value })}
            placeholder="recipientName"
          />`,
        )}
        ${this._renderDetailRow(
          'Type',
          html`<select
            class="ep-input stencil-picker-full"
            .value=${row.type}
            @change=${(e: Event) =>
              this._updateRow(row.id, {
                type: (e.target as HTMLSelectElement).value as ParamRow['type'],
              })}
          >
            ${TYPE_OPTIONS.map(
              (opt) =>
                html`<option value=${opt.value} ?selected=${row.type === opt.value}>
                  ${opt.label}
                </option>`,
            )}
          </select>`,
        )}
        ${this._renderInlineToggle(
          row.id,
          'List of values',
          row.isList,
          (checked) => this._updateRow(row.id, { isList: checked }),
          'Wrap as `array<type>` so the parameter accepts a list',
        )}
        ${this._renderInlineToggle(
          row.id,
          'Required',
          row.required,
          (checked) => this._updateRow(row.id, { required: checked }),
          'Consumers must bind this parameter (or set a default)',
        )}
        ${this._renderDetailRow(
          'Description',
          html`<textarea
            class="ep-input stencil-picker-full"
            style="min-height: 60px;"
            .value=${row.description}
            @input=${(e: Event) =>
              this._updateRow(row.id, { description: (e.target as HTMLTextAreaElement).value })}
            placeholder="What this parameter is for"
          ></textarea>`,
        )}
        ${this._renderDetailRow(
          row.isList ? 'Default (comma-separated)' : 'Default',
          html`<input
            type="text"
            class="ep-input stencil-picker-full"
            .value=${row.defaultText}
            @input=${(e: Event) =>
              this._updateRow(row.id, { defaultText: (e.target as HTMLInputElement).value })}
            placeholder=${defaultPlaceholder(row)}
          />`,
        )}
        ${error
          ? html`<div class="stencil-picker-error">${error}</div>`
          : nothing}
      </div>
    `;
  }

  private _renderDetailRow(label: string, control: unknown) {
    return html`
      <div class="stencil-params-row">
        <label class="stencil-picker-label-inline">${label}</label>
        ${control}
      </div>
    `;
  }

  private _renderInlineToggle(
    rowId: string,
    label: string,
    checked: boolean,
    onChange: (checked: boolean) => void,
    hint: string,
  ) {
    const id = `param-${rowId}-${label.replace(/\s+/g, '-').toLowerCase()}`;
    return html`
      <div class="stencil-picker-flex-row">
        <input
          type="checkbox"
          id=${id}
          .checked=${checked}
          @change=${(e: Event) => onChange((e.target as HTMLInputElement).checked)}
        />
        <label for=${id} class="stencil-picker-label-inline" style="cursor: pointer;">
          <span style="font-weight: 500;">${label}</span>
          <span class="stencil-params-toggle-hint">— ${hint}</span>
        </label>
      </div>
    `;
  }

  private _select(id: string) {
    this._selectedId = id;
  }

  private _addRow = () => {
    const id = makeRowId();
    const baseName = `param${this._rows.length + 1}`;
    this._rows = [
      ...this._rows,
      {
        id,
        name: baseName,
        type: 'string' as const,
        isList: false,
        required: false,
        description: '',
        defaultText: '',
      },
    ];
    this._selectedId = id;
    this._validateAndEmit();
  };

  private _removeRow(id: string) {
    const idx = this._rows.findIndex((r) => r.id === id);
    this._rows = this._rows.filter((r) => r.id !== id);
    if (this._selectedId === id) {
      // Pick a sensible neighbour to keep the user oriented.
      this._selectedId = this._rows[idx]?.id ?? this._rows[idx - 1]?.id ?? null;
    }
    this._validateAndEmit();
  }

  private _updateRow(id: string, patch: Partial<ParamRow>) {
    this._rows = this._rows.map((row) => (row.id === id ? { ...row, ...patch } : row));
    this._validateAndEmit();
  }

  private _validateAndEmit() {
    const errors: Record<string, string> = {};
    const seenNames = new Set<string>();
    for (const row of this._rows) {
      if (!NAME_RE.test(row.name)) {
        errors[row.id] = 'Name must match ^[a-z][a-zA-Z0-9_]{0,63}$';
        continue;
      }
      if (RESERVED.has(row.name)) {
        errors[row.id] = `Name '${row.name}' collides with a reserved scope`;
        continue;
      }
      if (seenNames.has(row.name)) {
        errors[row.id] = `Duplicate name '${row.name}'`;
        continue;
      }
      seenNames.add(row.name);
    }
    this._errors = errors;

    if (Object.keys(errors).length > 0) {
      this.dispatchEvent(
        new CustomEvent('parameter-schema-validation', { detail: { valid: false } }),
      );
      return;
    }

    const schema = rowsToSchema(this._rows);
    this.dispatchEvent(
      new CustomEvent('parameter-schema-change', {
        detail: { schema, valid: true },
        bubbles: true,
        composed: true,
      }),
    );
  }
}

function makeRowId(): string {
  return `p_${Math.random().toString(36).slice(2, 10)}`;
}

function parseSchemaToRows(schema: JsonSchema | undefined): ParamRow[] {
  if (!schema?.properties) return [];
  const required = new Set(schema.required ?? []);
  return Object.entries(schema.properties).map(([name, prop]) =>
    paramRowFromProp(name, prop, required.has(name)),
  );
}

function paramRowFromProp(name: string, prop: JsonSchemaProperty, isRequired: boolean): ParamRow {
  const isList = (Array.isArray(prop.type) ? prop.type[0] : prop.type) === 'array';
  const inner: JsonSchemaProperty | undefined = isList ? prop.items : prop;
  const innerType = (Array.isArray(inner?.type) ? inner?.type[0] : inner?.type) ?? 'string';
  const format = inner?.format;
  let type: ParamRow['type'] = 'string';
  if (innerType === 'string' && format === 'date') type = 'date';
  else if (innerType === 'string' && format === 'date-time') type = 'date-time';
  else if (innerType === 'number') type = 'number';
  else if (innerType === 'integer') type = 'integer';
  else if (innerType === 'boolean') type = 'boolean';

  const defaultRaw = (prop as JsonSchemaProperty & { default?: unknown }).default;
  const defaultText = stringifyDefault(defaultRaw, isList);

  return {
    id: makeRowId(),
    name,
    type,
    isList,
    required: isRequired,
    description: prop.description ?? '',
    defaultText,
  };
}

function rowsToSchema(rows: ParamRow[]): JsonSchema {
  const properties: Record<string, JsonSchemaProperty> = {};
  const required: string[] = [];
  for (const row of rows) {
    properties[row.name] = rowToProperty(row);
    if (row.required) required.push(row.name);
  }
  const schema: JsonSchema = { type: 'object', properties };
  if (required.length > 0) schema.required = required;
  return schema;
}

function rowToProperty(row: ParamRow): JsonSchemaProperty {
  const inner = innerProp(row);
  const base: JsonSchemaProperty = row.isList ? { type: 'array', items: inner } : inner;
  if (row.description) base.description = row.description;
  const def = parseDefault(row);
  if (def !== undefined) (base as JsonSchemaProperty & { default?: unknown }).default = def;
  return base;
}

function innerProp(row: ParamRow): JsonSchemaProperty {
  switch (row.type) {
    case 'date':
      return { type: 'string', format: 'date' };
    case 'date-time':
      return { type: 'string', format: 'date-time' };
    case 'string':
    case 'number':
    case 'integer':
    case 'boolean':
      return { type: row.type };
  }
}

function parseDefault(row: ParamRow): unknown {
  const raw = row.defaultText.trim();
  if (!raw) return undefined;
  if (row.isList) {
    return raw.split(',').map((part) => parseScalar(part.trim(), row.type));
  }
  return parseScalar(raw, row.type);
}

function parseScalar(raw: string, type: ParamRow['type']): unknown {
  switch (type) {
    case 'number': {
      const n = Number(raw);
      return Number.isFinite(n) ? n : raw;
    }
    case 'integer': {
      const n = parseInt(raw, 10);
      return Number.isFinite(n) ? n : raw;
    }
    case 'boolean':
      return raw === 'true';
    default:
      return raw;
  }
}

function stringifyDefault(value: unknown, isList: boolean): string {
  if (value === undefined || value === null) return '';
  if (isList && Array.isArray(value)) return value.map(String).join(', ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function defaultPlaceholder(row: ParamRow): string {
  switch (row.type) {
    case 'number':
    case 'integer':
      return row.isList ? '1, 2, 3' : '0';
    case 'boolean':
      return row.isList ? 'true, false' : 'true';
    case 'date':
      return row.isList ? '2024-01-01, 2024-12-31' : '2024-01-01';
    case 'date-time':
      return '2024-01-01T12:00:00Z';
    default:
      return row.isList ? 'one, two, three' : '';
  }
}
