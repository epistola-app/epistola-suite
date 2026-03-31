/**
 * SchemaFieldRow — Renders a single schema field row with type controls.
 *
 * Supports expand/collapse for object/array-of-objects fields at any depth.
 * Emits SchemaCommand objects instead of managing field mutations directly.
 * Recursive rendering passes `depth + 1` for infinite nesting support.
 */

import { html, nothing } from 'lit';
import type {
  ArrayField,
  PrimitiveField,
  SchemaField,
  SchemaFieldType,
  SchemaFieldUpdate,
  StringFormat,
} from '../types.js';
import type { SchemaCommand } from '../utils/schemaCommands.js';
import { FIELD_TYPE_LABELS } from '../utils/schemaUtils.js';

/** All field types available in the type dropdown */
const FIELD_TYPES: SchemaFieldType[] = [
  'string',
  'number',
  'integer',
  'boolean',
  'date',
  'array',
  'object',
];

/** Item types available for arrays */
const ARRAY_ITEM_TYPES: SchemaFieldType[] = [
  'string',
  'number',
  'integer',
  'boolean',
  'date',
  'object',
];

/** Check if a field supports nested fields */
function supportsNestedFields(field: SchemaField): boolean {
  if (field.type === 'object') return true;
  if (field.type === 'array' && field.arrayItemType === 'object') return true;
  return false;
}

export function renderSchemaFieldRow(
  field: SchemaField,
  onCommand: (command: SchemaCommand) => void,
  depth: number,
  expandedFields: Set<string>,
  onToggleExpand: (fieldId: string) => void,
): unknown {
  const canExpand = supportsNestedFields(field);
  const nestedFields =
    field.type === 'object' || field.type === 'array' ? (field.nestedFields ?? []) : [];
  const isExpanded = expandedFields.has(field.id);

  const nestStyle = depth > 0 ? `--nest-depth: ${depth}` : undefined;

  return html`
    <div class="dc-field-row ${depth > 0 ? 'dc-field-nested' : ''}" style=${nestStyle ?? nothing}>
      <div class="dc-field-controls">
        ${canExpand
          ? html`
              <button
                class="dc-field-expand-btn"
                @click=${() => onToggleExpand(field.id)}
                title="${isExpanded ? 'Collapse' : 'Expand'} nested fields"
                aria-expanded="${isExpanded}"
              >
                ${isExpanded ? '\u25BC' : '\u25B6'}
              </button>
            `
          : html`<span class="dc-field-expand-spacer"></span>`}

        <input
          type="text"
          class="ep-input dc-field-name-input"
          .value=${field.name}
          placeholder="Field name"
          aria-label="Field name"
          @change=${(e: Event) => {
            const value = (e.target as HTMLInputElement).value.trim();
            if (value && value !== field.name) {
              onCommand({ type: 'updateField', fieldId: field.id, updates: { name: value } });
            }
          }}
        />

        <select
          class="ep-select dc-field-type-select"
          .value=${field.type}
          aria-label="Field type"
          @change=${(e: Event) => {
            const newType = (e.target as HTMLSelectElement).value as SchemaFieldType;
            const updates: SchemaFieldUpdate = { type: newType };
            if (newType === 'array') {
              updates.arrayItemType = 'string';
            }
            onCommand({ type: 'updateField', fieldId: field.id, updates });
          }}
        >
          ${FIELD_TYPES.map(
            (t) => html`
              <option value=${t} ?selected=${field.type === t}>${FIELD_TYPE_LABELS[t]}</option>
            `,
          )}
        </select>

        ${field.type === 'array'
          ? html`
              <select
                class="ep-select dc-field-array-type-select"
                .value=${field.arrayItemType}
                aria-label="Array item type"
                @change=${(e: Event) => {
                  const newItemType = (e.target as HTMLSelectElement).value as SchemaFieldType;
                  onCommand({
                    type: 'updateField',
                    fieldId: field.id,
                    updates: { arrayItemType: newItemType },
                  });
                }}
              >
                ${ARRAY_ITEM_TYPES.map(
                  (t) => html`
                    <option value=${t} ?selected=${field.arrayItemType === t}>
                      ${FIELD_TYPE_LABELS[t]}
                    </option>
                  `,
                )}
              </select>
            `
          : html`<span></span>`}

        <label class="dc-field-required-label" title="Required field">
          <input
            type="checkbox"
            class="ep-checkbox"
            .checked=${field.required}
            aria-label="Required"
            @change=${(e: Event) => {
              onCommand({
                type: 'updateField',
                fieldId: field.id,
                updates: { required: (e.target as HTMLInputElement).checked },
              });
            }}
          />
        </label>

        ${canExpand
          ? html`
              <button
                class="dc-field-add-nested-btn ep-btn-outline btn-sm"
                title="Add nested field"
                @click=${() => {
                  onCommand({ type: 'addField', parentFieldId: field.id });
                  if (!isExpanded) {
                    onToggleExpand(field.id);
                  }
                }}
              >
                +
              </button>
            `
          : html`<span></span>`}

        <button
          class="dc-field-delete-btn"
          title="Delete field"
          aria-label="Delete field"
          @click=${() => onCommand({ type: 'deleteField', fieldId: field.id })}
        >
          ×
        </button>
      </div>

      ${renderConstraints(field, onCommand, depth)}
      ${canExpand && isExpanded
        ? html`
            <div class="dc-field-nested-container">
              ${nestedFields.length === 0
                ? html`<div class="dc-field-nested-empty">
                    No nested fields. Click "+" to add one.
                  </div>`
                : nestedFields.map((nestedField) =>
                    renderSchemaFieldRow(
                      nestedField,
                      onCommand,
                      depth + 1,
                      expandedFields,
                      onToggleExpand,
                    ),
                  )}
            </div>
          `
        : nothing}
    </div>
  `;
}

// =============================================================================
// Constraint inputs (minimum, maximum, minItems, format)
// =============================================================================

/** Check if a field has editable constraints */
function hasConstraints(field: SchemaField): boolean {
  return (
    field.type === 'number' ||
    field.type === 'integer' ||
    field.type === 'array' ||
    field.type === 'string'
  );
}

/** String format options */
const STRING_FORMATS: Array<{ value: StringFormat | ''; label: string }> = [
  { value: '', label: 'None' },
  { value: 'email', label: 'Email' },
];

function renderConstraints(
  field: SchemaField,
  onCommand: (command: SchemaCommand) => void,
  depth: number,
): unknown {
  if (!hasConstraints(field)) return nothing;

  const nestStyle = depth > 0 ? `--nest-depth: ${depth}` : undefined;

  const emitUpdate = (updates: SchemaFieldUpdate) => {
    onCommand({ type: 'updateField', fieldId: field.id, updates });
  };

  return html`
    <div
      class="dc-field-constraints ${depth > 0 ? 'dc-field-nested' : ''}"
      style=${nestStyle ?? nothing}
    >
      ${field.type === 'string'
        ? renderFormatConstraint(field as PrimitiveField, emitUpdate)
        : nothing}
      ${field.type === 'number' || field.type === 'integer'
        ? renderNumericConstraints(field as PrimitiveField, emitUpdate)
        : nothing}
      ${field.type === 'array' ? renderArrayConstraints(field as ArrayField, emitUpdate) : nothing}
    </div>
  `;
}

function renderFormatConstraint(
  field: PrimitiveField,
  emitUpdate: (updates: SchemaFieldUpdate) => void,
): unknown {
  const currentFormat = 'format' in field ? (field.format ?? '') : '';
  return html`
    <label class="dc-constraint-item">
      <span class="dc-constraint-label">Format</span>
      <select
        class="ep-select dc-constraint-input"
        .value=${currentFormat}
        @change=${(e: Event) => {
          const val = (e.target as HTMLSelectElement).value;
          emitUpdate({ format: val ? (val as StringFormat) : undefined });
        }}
      >
        ${STRING_FORMATS.map(
          (f) =>
            html`<option value=${f.value} ?selected=${currentFormat === f.value}>
              ${f.label}
            </option>`,
        )}
      </select>
    </label>
  `;
}

function renderNumericConstraints(
  field: PrimitiveField,
  emitUpdate: (updates: SchemaFieldUpdate) => void,
): unknown {
  const min = 'minimum' in field ? field.minimum : undefined;
  const max = 'maximum' in field ? field.maximum : undefined;
  const step = field.type === 'integer' ? '1' : 'any';

  return html`
    <label class="dc-constraint-item">
      <span class="dc-constraint-label">Min</span>
      <input
        type="number"
        class="ep-input dc-constraint-input"
        step=${step}
        .value=${min !== undefined ? String(min) : ''}
        placeholder="—"
        aria-label="Minimum value"
        @change=${(e: Event) => {
          const val = (e.target as HTMLInputElement).value;
          emitUpdate({ minimum: val ? Number(val) : undefined });
        }}
      />
    </label>
    <label class="dc-constraint-item">
      <span class="dc-constraint-label">Max</span>
      <input
        type="number"
        class="ep-input dc-constraint-input"
        step=${step}
        .value=${max !== undefined ? String(max) : ''}
        placeholder="—"
        aria-label="Maximum value"
        @change=${(e: Event) => {
          const val = (e.target as HTMLInputElement).value;
          emitUpdate({ maximum: val ? Number(val) : undefined });
        }}
      />
    </label>
  `;
}

function renderArrayConstraints(
  field: ArrayField,
  emitUpdate: (updates: SchemaFieldUpdate) => void,
): unknown {
  const minItems = 'minItems' in field ? field.minItems : undefined;

  return html`
    <label class="dc-constraint-item">
      <span class="dc-constraint-label">Min items</span>
      <input
        type="number"
        class="ep-input dc-constraint-input"
        min="0"
        step="1"
        .value=${minItems !== undefined ? String(minItems) : ''}
        placeholder="—"
        aria-label="Minimum number of items"
        @change=${(e: Event) => {
          const val = (e.target as HTMLInputElement).value;
          emitUpdate({ minItems: val ? Number(val) : undefined });
        }}
      />
    </label>
  `;
}
