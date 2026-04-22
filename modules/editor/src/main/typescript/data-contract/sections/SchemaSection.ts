/**
 * SchemaSection — Two-panel visual schema builder.
 *
 * Left panel: compact field list with expand/collapse for nested fields.
 * Right panel: detail form for the selected field (name, type, constraints).
 *
 * Accepts VisualSchema directly — no conversion in the render path.
 */

import { html, nothing } from 'lit';
import type {
  ArrayField,
  PrimitiveField,
  SchemaField,
  SchemaFieldType,
  SchemaFieldUpdate,
  StringFormat,
  VisualSchema,
} from '../types.js';
import type { SchemaCommand } from '../utils/schemaCommands.js';
import { FIELD_TYPE_LABELS, isValidFieldName } from '../utils/schemaUtils.js';
import type { BreakingChange } from '../utils/schemaBreakingChanges.js';
import { renderSchemaFieldListItem } from './SchemaFieldRow.js';
import { renderValidationMessages } from './ValidationMessages.js';

export interface SchemaUiState {
  warnings: Array<{ path: string; message: string }>;
  breakingChanges: BreakingChange[];
  canUndo: boolean;
  canRedo: boolean;
  selectedFieldId: string | null;
  readOnly: boolean;
}

export interface SchemaSectionCallbacks {
  onCommand: (command: SchemaCommand) => void;
  onToggleFieldExpand: (fieldId: string) => void;
  onSelectField: (fieldId: string) => void;
  onUndo: () => void;
  onRedo: () => void;
}

// =============================================================================
// Main render
// =============================================================================

export function renderSchemaSection(
  visualSchema: VisualSchema,
  uiState: SchemaUiState,
  callbacks: SchemaSectionCallbacks,
  expandedFields: Set<string>,
): unknown {
  const fields = visualSchema.fields;
  const hasFields = fields.length > 0;
  const selectedField = uiState.selectedFieldId
    ? findFieldById(fields, uiState.selectedFieldId)
    : null;

  return html`
    <section class="dc-section">
      <h3 class="dc-section-label">Schema Definition</h3>
      <p class="dc-section-hint">
        Define the data fields that templates can use. Each field becomes a variable available in
        template expressions.
      </p>

      <!-- Toolbar -->
      <div class="dc-toolbar">
        <div class="dc-toolbar-spacer"></div>

        <button
          class="ep-btn-outline btn-sm dc-undo-btn"
          @click=${() => callbacks.onUndo()}
          ?disabled=${uiState.readOnly || !uiState.canUndo}
          title="Undo (Ctrl+Z)"
          aria-label="Undo"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path
              d="M3 6h7a4 4 0 014 4v0M3 6l3-3M3 6l3 3"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          Undo
        </button>

        <button
          class="ep-btn-outline btn-sm dc-redo-btn"
          @click=${() => callbacks.onRedo()}
          ?disabled=${uiState.readOnly || !uiState.canRedo}
          title="Redo (Ctrl+Shift+Z)"
          aria-label="Redo"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path
              d="M13 6H6a4 4 0 00-4 4v0M13 6l-3-3M13 6l-3 3"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          Redo
        </button>
      </div>

      <!-- Breaking changes banner -->
      ${uiState.breakingChanges.length > 0
        ? html`
            <div class="dc-breaking-changes-banner">
              <div class="dc-breaking-changes-banner-title">
                ${uiState.breakingChanges.length} breaking
                change${uiState.breakingChanges.length === 1 ? '' : 's'}
              </div>
              ${uiState.breakingChanges.map(
                (c) => html`
                  <span class="dc-breaking-change-chip dc-breaking-change-chip-${c.type}">
                    ${c.description}
                  </span>
                `,
              )}
            </div>
          `
        : nothing}

      <!-- Validation warnings -->
      ${renderValidationMessages(uiState.warnings)}

      <!-- Two-panel layout -->
      ${hasFields
        ? html`
            <div class="dc-schema-layout">
              ${renderFieldList(fields, uiState, callbacks, expandedFields)}
              ${renderDetailPanel(selectedField, uiState, callbacks)}
            </div>
          `
        : html`<div class="dc-empty-state">No fields defined yet. Add a field below.</div>`}

      <!-- Add field button -->
      <button
        class="ep-btn-outline btn-sm dc-add-field-btn"
        @click=${() => callbacks.onCommand({ type: 'addField', parentFieldId: null })}
        ?disabled=${uiState.readOnly}
      >
        + Add Field
      </button>
    </section>
  `;
}

// =============================================================================
// Left panel: field list
// =============================================================================

function renderFieldList(
  fields: SchemaField[],
  uiState: SchemaUiState,
  callbacks: SchemaSectionCallbacks,
  expandedFields: Set<string>,
): unknown {
  return html`
    <div class="dc-field-list">
      <div class="dc-field-list-header">
        <span>Fields</span>
      </div>
      <div class="dc-field-list-items">
        ${fields.map((field) =>
          renderSchemaFieldListItem(
            field,
            0,
            expandedFields,
            uiState.selectedFieldId,
            callbacks.onToggleFieldExpand,
            callbacks.onSelectField,
          ),
        )}
      </div>
    </div>
  `;
}

// =============================================================================
// Right panel: detail form
// =============================================================================

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

/** String format options */
const STRING_FORMATS: Array<{ value: StringFormat | ''; label: string }> = [
  { value: '', label: 'None' },
  { value: 'date-time', label: 'Date-Time' },
  { value: 'email', label: 'Email' },
  { value: 'uri', label: 'URI' },
];

function renderDetailPanel(
  field: SchemaField | null,
  uiState: SchemaUiState,
  callbacks: SchemaSectionCallbacks,
): unknown {
  if (!field) {
    return html`
      <div class="dc-detail-panel">
        <div class="dc-detail-empty">Select a field to edit its properties</div>
      </div>
    `;
  }

  const emitUpdate = (updates: SchemaFieldUpdate) => {
    callbacks.onCommand({ type: 'updateField', fieldId: field.id, updates });
  };

  const canHaveNested =
    field.type === 'object' || (field.type === 'array' && field.arrayItemType === 'object');

  return html`
    <div class="dc-detail-panel">
      <h4 class="dc-detail-title">${field.name}</h4>

      <div class="dc-detail-form">
        <!-- Name -->
        <div class="dc-detail-row">
          <label class="dc-detail-label">Name</label>
          <input
            type="text"
            class="ep-input dc-detail-input"
            .value=${field.name}
            placeholder="Field name"
            title="Letters, digits, and underscores only. Must start with a letter or underscore."
            ?disabled=${uiState.readOnly}
            @input=${(e: Event) => {
              const input = e.target as HTMLInputElement;
              const pos = input.selectionStart ?? 0;
              const filtered = input.value.replace(/[^a-zA-Z0-9_]/g, '');
              if (filtered !== input.value) {
                input.value = filtered;
                input.selectionStart = input.selectionEnd = pos - 1;
              }
            }}
            @change=${(e: Event) => {
              const value = (e.target as HTMLInputElement).value.trim();
              if (value && value !== field.name && isValidFieldName(value)) {
                emitUpdate({ name: value });
              }
            }}
          />
        </div>

        <!-- Type -->
        <div class="dc-detail-row">
          <label class="dc-detail-label">Type</label>
          <select
            class="ep-select dc-detail-select"
            .value=${field.type}
            ?disabled=${uiState.readOnly}
            @change=${(e: Event) => {
              const newType = (e.target as HTMLSelectElement).value as SchemaFieldType;
              const updates: SchemaFieldUpdate = { type: newType };
              if (newType === 'array') {
                updates.arrayItemType = 'string';
              }
              emitUpdate(updates);
            }}
          >
            ${FIELD_TYPES.map(
              (t) =>
                html`<option value=${t} ?selected=${field.type === t}>
                  ${FIELD_TYPE_LABELS[t]}
                </option>`,
            )}
          </select>
        </div>

        <!-- Array item type -->
        ${field.type === 'array'
          ? html`
              <div class="dc-detail-row">
                <label class="dc-detail-label">Item type</label>
                <select
                  class="ep-select dc-detail-select"
                  .value=${field.arrayItemType}
                  ?disabled=${uiState.readOnly}
                  @change=${(e: Event) => {
                    const newItemType = (e.target as HTMLSelectElement).value as SchemaFieldType;
                    emitUpdate({ arrayItemType: newItemType });
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
              </div>
            `
          : nothing}

        <!-- Required -->
        <div class="dc-detail-row dc-detail-row-inline">
          <input
            type="checkbox"
            class="ep-checkbox"
            id="dc-detail-required"
            .checked=${field.required}
            ?disabled=${uiState.readOnly}
            @change=${(e: Event) => {
              emitUpdate({ required: (e.target as HTMLInputElement).checked });
            }}
          />
          <label class="dc-detail-label" for="dc-detail-required">Required</label>
        </div>

        <!-- Description -->
        <div class="dc-detail-row">
          <label class="dc-detail-label">Description</label>
          <textarea
            class="ep-input dc-detail-textarea"
            .value=${field.description ?? ''}
            placeholder="Optional description"
            ?disabled=${uiState.readOnly}
            @change=${(e: Event) => {
              const value = (e.target as HTMLTextAreaElement).value;
              emitUpdate({ description: value || undefined });
            }}
          ></textarea>
        </div>

        <!-- Type-specific constraints -->
        ${renderTypeConstraints(field, uiState, emitUpdate)}

        <!-- Actions -->
        <div class="dc-detail-actions">
          ${canHaveNested
            ? html`
                <button
                  class="ep-btn-outline btn-sm"
                  @click=${() => {
                    callbacks.onCommand({ type: 'addField', parentFieldId: field.id });
                    callbacks.onToggleFieldExpand(field.id);
                  }}
                  ?disabled=${uiState.readOnly}
                >
                  + Add Nested Field
                </button>
              `
            : nothing}

          <div class="dc-toolbar-spacer"></div>

          <button
            class="dc-detail-delete-btn"
            @click=${() => callbacks.onCommand({ type: 'deleteField', fieldId: field.id })}
            ?disabled=${uiState.readOnly}
          >
            Delete Field
          </button>
        </div>
      </div>
    </div>
  `;
}

// =============================================================================
// Type-specific constraints
// =============================================================================

function renderTypeConstraints(
  field: SchemaField,
  uiState: SchemaUiState,
  emitUpdate: (updates: SchemaFieldUpdate) => void,
): unknown {
  if (field.type === 'string') {
    return renderStringConstraints(field as PrimitiveField, uiState, emitUpdate);
  }
  if (field.type === 'number' || field.type === 'integer') {
    return renderNumericConstraints(field as PrimitiveField, uiState, emitUpdate);
  }
  if (field.type === 'array') {
    return renderArrayConstraints(field as ArrayField, uiState, emitUpdate);
  }
  return nothing;
}

function renderStringConstraints(
  field: PrimitiveField,
  uiState: SchemaUiState,
  emitUpdate: (updates: SchemaFieldUpdate) => void,
): unknown {
  const currentFormat = field.format ?? '';
  return html`
    <div class="dc-detail-section-label">Constraints</div>
    <div class="dc-detail-row">
      <label class="dc-detail-label">Format</label>
      <select
        class="ep-select dc-detail-select"
        .value=${currentFormat}
        ?disabled=${uiState.readOnly}
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
    </div>
  `;
}

function renderNumericConstraints(
  field: PrimitiveField,
  uiState: SchemaUiState,
  emitUpdate: (updates: SchemaFieldUpdate) => void,
): unknown {
  const min = field.minimum;
  const max = field.maximum;
  const step = field.type === 'integer' ? '1' : 'any';

  return html`
    <div class="dc-detail-section-label">Constraints</div>
    <div class="dc-detail-constraints">
      <div class="dc-detail-row">
        <label class="dc-detail-label">Minimum</label>
        <input
          type="number"
          class="ep-input dc-detail-input"
          step=${step}
          .value=${min !== undefined ? String(min) : ''}
          placeholder="—"
          ?disabled=${uiState.readOnly}
          @change=${(e: Event) => {
            const val = (e.target as HTMLInputElement).value;
            emitUpdate({ minimum: val ? Number(val) : undefined });
          }}
        />
      </div>
      <div class="dc-detail-row">
        <label class="dc-detail-label">Maximum</label>
        <input
          type="number"
          class="ep-input dc-detail-input"
          step=${step}
          .value=${max !== undefined ? String(max) : ''}
          placeholder="—"
          ?disabled=${uiState.readOnly}
          @change=${(e: Event) => {
            const val = (e.target as HTMLInputElement).value;
            emitUpdate({ maximum: val ? Number(val) : undefined });
          }}
        />
      </div>
    </div>
  `;
}

function renderArrayConstraints(
  field: ArrayField,
  uiState: SchemaUiState,
  emitUpdate: (updates: SchemaFieldUpdate) => void,
): unknown {
  const minItems = field.minItems;

  return html`
    <div class="dc-detail-section-label">Constraints</div>
    <div class="dc-detail-row">
      <label class="dc-detail-label">Min items</label>
      <input
        type="number"
        class="ep-input dc-detail-input"
        min="0"
        step="1"
        .value=${minItems !== undefined ? String(minItems) : ''}
        placeholder="—"
        ?disabled=${uiState.readOnly}
        @change=${(e: Event) => {
          const val = (e.target as HTMLInputElement).value;
          emitUpdate({ minItems: val ? Number(val) : undefined });
        }}
      />
    </div>
  `;
}

// =============================================================================
// Helpers
// =============================================================================

/** Find a field by ID in a nested field tree. */
function findFieldById(fields: SchemaField[], id: string): SchemaField | null {
  for (const field of fields) {
    if (field.id === id) return field;
    if (field.type === 'object' && field.nestedFields) {
      const found = findFieldById(field.nestedFields, id);
      if (found) return found;
    }
    if (field.type === 'array' && field.nestedFields) {
      const found = findFieldById(field.nestedFields, id);
      if (found) return found;
    }
  }
  return null;
}
