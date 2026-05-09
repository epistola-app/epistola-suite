/**
 * StencilParameterDefinitionsPanel — author UI for declaring a stencil's
 * input parameters.
 *
 * Lives on the stencil editor page (sidebar / dedicated tab), separate from
 * the per-instance binding UX in [StencilInspector]. The author edits a list
 * of parameters (name, primitive type, list flag, required, description,
 * default); the panel emits `parameter-schema-change` with a full JsonSchema
 * (`{type:"object", properties:{...}, required:[...]}`) which the host then
 * persists alongside the draft content via `PUT /draft`.
 *
 * V1 surfaces the primitive subset users actually need:
 *   - string / number / integer / boolean / date / date-time
 *   - "list of <primitive>" toggle wraps as `{type:"array",items:{type:<primitive>}}`
 *
 * The canonical storage stays JSON Schema, so v2 can lift restrictions
 * (nested objects, enums, formats) without a migration.
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { JsonSchema, JsonSchemaProperty } from '../../data-contract/types.js';

interface ParamRow {
  name: string;
  type: 'string' | 'number' | 'integer' | 'boolean' | 'date' | 'date-time';
  isList: boolean;
  required: boolean;
  description: string;
  defaultText: string;
}

const NAME_RE = /^[a-z][a-zA-Z0-9_]{0,63}$/;
const RESERVED = new Set(['params', 'item', 'sys', 'index']);

@customElement('stencil-parameter-definitions-panel')
export class StencilParameterDefinitionsPanel extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) schema?: JsonSchema;

  @state() private _rows: ParamRow[] = [];
  @state() private _errors: Record<number, string> = {};

  override connectedCallback(): void {
    super.connectedCallback();
    this._rows = parseSchemaToRows(this.schema);
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('schema')) {
      this._rows = parseSchemaToRows(this.schema);
    }
  }

  override render() {
    return html`
      <div class="stencil-param-panel" style="padding: var(--ep-space-3) 0;">
        <div
          style="display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--ep-space-2);"
        >
          <div style="font-weight: 600; font-size: var(--ep-text-sm);">
            Parameters (${this._rows.length})
          </div>
          <button
            type="button"
            class="ep-btn ep-btn-secondary"
            style="font-size: var(--ep-text-xs);"
            @click=${this._addRow}
          >
            + Add parameter
          </button>
        </div>

        ${this._rows.length === 0
          ? html`
              <div
                style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); padding: var(--ep-space-3); border: 1px dashed var(--ep-border); border-radius: var(--ep-radius);"
              >
                Stencils with no parameters render the same way for every consumer. Add a parameter
                to make a value (e.g. recipient name, page number) configurable per insertion.
              </div>
            `
          : nothing}
        ${this._rows.map((row, index) => this._renderRow(row, index))}
      </div>
    `;
  }

  private _renderRow(row: ParamRow, index: number) {
    const error = this._errors[index];
    return html`
      <div
        style="border: 1px solid var(--ep-border); border-radius: var(--ep-radius); padding: var(--ep-space-2); margin-bottom: var(--ep-space-2);"
      >
        <div style="display: flex; gap: var(--ep-space-2); align-items: flex-end;">
          <div style="flex: 1;">
            <label style="font-size: var(--ep-text-xs); font-weight: 500;">Name</label>
            <input
              type="text"
              class="ep-input"
              style="width: 100%; ${error ? 'border-color: var(--ep-destructive, #dc2626);' : ''}"
              .value=${row.name}
              @input=${(e: Event) =>
                this._updateRow(index, { name: (e.target as HTMLInputElement).value })}
              placeholder="recipientName"
            />
          </div>
          <div style="width: 110px;">
            <label style="font-size: var(--ep-text-xs); font-weight: 500;">Type</label>
            <select
              class="ep-input"
              style="width: 100%;"
              .value=${row.type}
              @change=${(e: Event) =>
                this._updateRow(index, {
                  type: (e.target as HTMLSelectElement).value as ParamRow['type'],
                })}
            >
              <option value="string">string</option>
              <option value="number">number</option>
              <option value="integer">integer</option>
              <option value="boolean">boolean</option>
              <option value="date">date</option>
              <option value="date-time">date-time</option>
            </select>
          </div>
          <label
            style="display: flex; align-items: center; gap: 4px; font-size: var(--ep-text-xs);"
          >
            <input
              type="checkbox"
              .checked=${row.isList}
              @change=${(e: Event) =>
                this._updateRow(index, { isList: (e.target as HTMLInputElement).checked })}
            />
            list
          </label>
          <label
            style="display: flex; align-items: center; gap: 4px; font-size: var(--ep-text-xs);"
          >
            <input
              type="checkbox"
              .checked=${row.required}
              @change=${(e: Event) =>
                this._updateRow(index, { required: (e.target as HTMLInputElement).checked })}
            />
            required
          </label>
          <button
            type="button"
            class="ep-btn ep-btn-secondary"
            style="font-size: var(--ep-text-xs);"
            @click=${() => this._removeRow(index)}
            aria-label="Remove parameter"
          >
            Remove
          </button>
        </div>

        <div style="margin-top: var(--ep-space-2);">
          <label style="font-size: var(--ep-text-xs); font-weight: 500;">Description</label>
          <input
            type="text"
            class="ep-input"
            style="width: 100%;"
            .value=${row.description}
            @input=${(e: Event) =>
              this._updateRow(index, { description: (e.target as HTMLInputElement).value })}
            placeholder="What this parameter is for"
          />
        </div>

        <div style="margin-top: var(--ep-space-2);">
          <label style="font-size: var(--ep-text-xs); font-weight: 500;">
            Default ${row.isList ? '(comma-separated)' : ''}
          </label>
          <input
            type="text"
            class="ep-input"
            style="width: 100%;"
            .value=${row.defaultText}
            @input=${(e: Event) =>
              this._updateRow(index, { defaultText: (e.target as HTMLInputElement).value })}
            placeholder=${defaultPlaceholder(row)}
          />
        </div>

        ${error
          ? html`<div
              style="font-size: var(--ep-text-xs); color: var(--ep-destructive, #dc2626); margin-top: var(--ep-space-1);"
            >
              ${error}
            </div>`
          : nothing}
      </div>
    `;
  }

  private _addRow = () => {
    const baseName = `param${this._rows.length + 1}`;
    const newRows = [
      ...this._rows,
      {
        name: baseName,
        type: 'string' as const,
        isList: false,
        required: false,
        description: '',
        defaultText: '',
      },
    ];
    this._rows = newRows;
    this._validateAndEmit();
  };

  private _removeRow(index: number) {
    this._rows = this._rows.filter((_, i) => i !== index);
    this._validateAndEmit();
  }

  private _updateRow(index: number, patch: Partial<ParamRow>) {
    this._rows = this._rows.map((row, i) => (i === index ? { ...row, ...patch } : row));
    this._validateAndEmit();
  }

  private _validateAndEmit() {
    const errors: Record<number, string> = {};
    const seenNames = new Set<string>();
    for (let i = 0; i < this._rows.length; i++) {
      const row = this._rows[i];
      if (!NAME_RE.test(row.name)) {
        errors[i] = 'Name must match ^[a-z][a-zA-Z0-9_]{0,63}$';
        continue;
      }
      if (RESERVED.has(row.name)) {
        errors[i] = `Name '${row.name}' collides with a reserved scope`;
        continue;
      }
      if (seenNames.has(row.name)) {
        errors[i] = `Duplicate name '${row.name}'`;
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
