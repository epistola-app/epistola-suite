// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
import type { SchemaCommand } from '../schema/commands.js';
import { isValidFieldName } from '../schema/conversion.js';
import {
  type FieldLocation,
  fieldTargetLabel,
  findFieldLocation,
  supportsNestedFields,
} from '../schema/field-location.js';
import {
  ARRAY_ITEM_FIELD_TYPES as ARRAY_ITEM_TYPES,
  CONTRACT_FIELD_TYPES as FIELD_TYPES,
  FIELD_TYPE_LABELS,
} from '../field-types.js';
import { renderSchemaFieldListItem } from './SchemaFieldRow.js';
import { renderValidationMessages } from './ValidationMessages.js';

export interface SchemaUiState {
  warnings: Array<{ path: string; message: string }>;
  canUndo: boolean;
  canRedo: boolean;
  selectedFieldId: string | null;
  readOnly: boolean;
  jsonPanelOpen: boolean;
}

export interface SchemaSectionCallbacks {
  onCommand: (command: SchemaCommand) => void;
  onToggleFieldExpand: (fieldId: string) => void;
  onSelectField: (fieldId: string) => void;
  onUndo: () => void;
  onRedo: () => void;
  onAddField: (parentFieldId: string | null) => void;
  onRequestFieldNameFocus: (fieldId: string, selectAll: boolean) => void;
  onImport: () => void;
  onToggleJson: () => void;
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
    ? findFieldLocation(fields, uiState.selectedFieldId)
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
        <button
          class="ep-btn ep-btn-outline ep-btn-sm dc-add-field-btn"
          @click=${() => callbacks.onAddField(null)}
          ?disabled=${uiState.readOnly}
          aria-label="Add field to data contract"
        >
          + Add field to data contract
        </button>

        <button
          class="ep-btn ep-btn-outline ep-btn-sm dc-btn-icon"
          ?disabled=${uiState.readOnly}
          @click=${() => callbacks.onImport()}
          title="Import a JSON Schema"
        >
          Import
        </button>

        <button
          class="ep-btn ep-btn-outline ep-btn-sm dc-btn-icon"
          @click=${() => callbacks.onToggleJson()}
        >
          <span
            class="dc-json-toggle-arrow ${uiState.jsonPanelOpen ? 'dc-json-toggle-arrow-open' : ''}"
            >&#9654;</span
          >
          JSON
        </button>

        <div class="dc-toolbar-spacer"></div>

        <button
          class="ep-btn ep-btn-outline ep-btn-sm dc-undo-btn"
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
          class="ep-btn ep-btn-outline ep-btn-sm dc-redo-btn"
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

      <!-- Validation warnings -->
      ${renderValidationMessages(uiState.warnings)}

      <!-- Two-panel layout -->
      ${
        hasFields
          ? html`
              <div class="dc-schema-layout">
                ${renderFieldList(fields, uiState, callbacks, expandedFields)}
                ${renderDetailPanel(selectedField, fields, uiState, callbacks)}
              </div>
            `
          : html`<div class="dc-empty-state">
              No fields defined yet. Use Add field to data contract above.
            </div>`
      }
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

/**
 * String format options. Date and date-time are first-class field types (see
 * FIELD_TYPES), not string formats, so only the non-date formats appear here.
 */
const STRING_FORMATS: Array<{ value: StringFormat | ''; label: string }> = [
  { value: '', label: 'None' },
  { value: 'email', label: 'Email' },
  { value: 'uri', label: 'URI' },
];

function renderDetailPanel(
  location: FieldLocation | null,
  fields: readonly SchemaField[],
  uiState: SchemaUiState,
  callbacks: SchemaSectionCallbacks,
): unknown {
  if (!location) {
    return html`
      <div class="dc-detail-panel">
        <div class="dc-detail-empty">Select a field to edit its properties</div>
      </div>
    `;
  }

  const field = location.field;

  const emitUpdate = (updates: SchemaFieldUpdate) => {
    callbacks.onCommand({ type: 'updateField', fieldId: field.id, updates });
  };

  const canHaveNested = supportsNestedFields(field);
  const childTarget = canHaveNested ? fieldTargetLabel(fields, field.id) : null;
  const siblingTarget = fieldTargetLabel(fields, location.parentFieldId);

  const commitName = (input: HTMLInputElement): void => {
    const value = input.value.trim();
    if (!value || !isValidFieldName(value)) {
      input.value = field.name;
      return;
    }
    if (value !== field.name) emitUpdate({ name: value });
  };

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
            id="dc-schema-field-name-${field.id}"
            data-schema-field-name
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
              commitName(e.target as HTMLInputElement);
            }}
            @keydown=${(e: KeyboardEvent) => {
              const input = e.currentTarget as HTMLInputElement;
              if (e.key === 'Enter') {
                e.preventDefault();
                commitName(input);
                callbacks.onRequestFieldNameFocus(field.id, false);
              } else if (e.key === 'Escape') {
                e.preventDefault();
                input.value = field.name;
                input.focus();
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
        ${
          field.type === 'array'
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
            : nothing
        }

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
          <div class="dc-detail-add-actions">
            ${
              childTarget
                ? renderAddFieldAction(field.id, childTarget, true, uiState.readOnly, callbacks)
                : nothing
            }
            ${renderAddFieldAction(
              location.parentFieldId,
              siblingTarget,
              !childTarget,
              uiState.readOnly,
              callbacks,
            )}
          </div>

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

function renderAddFieldAction(
  parentFieldId: string | null,
  target: { visible: string; accessible: string },
  primary: boolean,
  readOnly: boolean,
  callbacks: SchemaSectionCallbacks,
): unknown {
  const accessibleLabel = `Add field to ${target.accessible}`;
  return html`
    <button
      class="ep-btn ${
        primary ? 'ep-btn-primary' : 'ep-btn-outline'
      } ep-btn-sm dc-context-add-field-btn"
      @click=${() => callbacks.onAddField(parentFieldId)}
      ?disabled=${readOnly}
      aria-label=${accessibleLabel}
      title=${accessibleLabel}
    >
      <span aria-hidden="true">+ Add field to</span>
      <span class="dc-context-target" aria-hidden="true">${target.visible}</span>
    </button>
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
    return renderStringConstraints(field, uiState, emitUpdate);
  }
  if (field.type === 'number' || field.type === 'integer') {
    return renderNumericConstraints(field, uiState, emitUpdate);
  }
  if (field.type === 'array') {
    return renderArrayConstraints(field, uiState, emitUpdate);
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
