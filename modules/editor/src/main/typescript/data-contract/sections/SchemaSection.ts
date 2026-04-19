/* eslint-disable no-use-before-define, no-undefined */

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
import { FIELD_TYPE_LABELS } from '../utils/schemaUtils.js';
import { renderSchemaFieldListItem } from './SchemaFieldRow.js';
import { renderValidationSummary } from './ValidationMessages.js';

export interface SchemaUiState {
  warnings: Array<{ path: string; message: string }>;
  canUndo: boolean;
  canRedo: boolean;
  selectedFieldId: string | null;
}

export interface SchemaSectionCallbacks {
  onCommand: (command: SchemaCommand) => void;
  onToggleFieldExpand: (fieldId: string) => void;
  onSelectField: (fieldId: string) => void;
  onUndo: () => void;
  onRedo: () => void;
  onReviewWarnings: () => void;
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
  const isLastRootFieldSelected =
    !!selectedField && fields.length === 1 && fields[0].id === selectedField.id;

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
          ?disabled=${!uiState.canUndo}
          title="Undo (Ctrl+Z)"
          aria-label="Undo"
        >
          Undo
        </button>

        <button
          class="ep-btn-outline btn-sm dc-redo-btn"
          @click=${() => callbacks.onRedo()}
          ?disabled=${!uiState.canRedo}
          title="Redo (Ctrl+Shift+Z)"
          aria-label="Redo"
        >
          Redo
        </button>
      </div>

      <!-- Validation warnings -->
      ${renderValidationSummary(uiState.warnings, callbacks.onReviewWarnings)}

      <div class="dc-schema-save-note" role="note">
        Removing schema fields can leave template expressions unresolved if generation input data no
        longer provides those values.
      </div>

      <!-- Two-panel layout -->
      ${hasFields
        ? html`
            <div class="dc-schema-layout">
              ${renderFieldList(fields, uiState, callbacks, expandedFields)}
              ${renderDetailPanel(selectedField, callbacks, !isLastRootFieldSelected)}
            </div>
          `
        : html`<div class="dc-empty-state">No fields defined yet. Add a field below.</div>`}

      <!-- Add field button -->
      <button
        class="ep-btn-outline btn-sm dc-add-field-btn"
        @click=${() => callbacks.onCommand({ type: 'addField', parentFieldId: null })}
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

function getInputValue(event: Event): string {
  const target = event.currentTarget;
  if (!(target instanceof HTMLInputElement)) {
    return '';
  }
  return target.value;
}

function getInputChecked(event: Event): boolean {
  const target = event.currentTarget;
  if (!(target instanceof HTMLInputElement)) {
    return false;
  }
  return target.checked;
}

function getSelectValue(event: Event): string {
  const target = event.currentTarget;
  if (!(target instanceof HTMLSelectElement)) {
    return '';
  }
  return target.value;
}

function getTextareaValue(event: Event): string {
  const target = event.currentTarget;
  if (!(target instanceof HTMLTextAreaElement)) {
    return '';
  }
  return target.value;
}

function isSchemaFieldType(value: string): value is SchemaFieldType {
  return FIELD_TYPES.some((type) => type === value);
}

function isStringFormat(value: string): value is StringFormat {
  return value === 'date-time' || value === 'email' || value === 'uri';
}

function renderDetailPanel(
  field: SchemaField | null,
  callbacks: SchemaSectionCallbacks,
  canDeleteField: boolean,
): unknown {
  if (!field) {
    return html`
      <div class="dc-detail-panel">
        <div class="dc-detail-empty">Select a field to edit its properties</div>
      </div>
    `;
  }

  const emitUpdate = (updates: SchemaFieldUpdate): void => {
    callbacks.onCommand({ type: 'updateField', fieldId: field.id, updates });
  };

  const canHaveNested =
    field.type === 'object' || (field.type === 'array' && field.arrayItemType === 'object');
  const deleteA11y = getDeleteFieldA11y(field.id, canDeleteField);

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
            @change=${(e: Event) => {
              const value = getInputValue(e).trim();
              if (value && value !== field.name) {
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
            @change=${(e: Event) => {
              const nextType = getSelectValue(e);
              if (!isSchemaFieldType(nextType)) {
                return;
              }
              const newType = nextType;
              const updates: SchemaFieldUpdate = { type: newType };
              if (nextType === 'array') {
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
                  @change=${(e: Event) => {
                    const nextItemType = getSelectValue(e);
                    if (!isSchemaFieldType(nextItemType)) {
                      return;
                    }
                    const newItemType = nextItemType;
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
            @change=${(e: Event) => {
              emitUpdate({ required: getInputChecked(e) });
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
            @change=${(e: Event) => {
              const value = getTextareaValue(e);
              emitUpdate({ description: value || undefined });
            }}
          ></textarea>
        </div>

        <!-- Type-specific constraints -->
        ${renderTypeConstraints(field, emitUpdate)}

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
                >
                  + Add Nested Field
                </button>
              `
            : nothing}

          <div class="dc-toolbar-spacer"></div>

          <button
            class="dc-detail-delete-btn"
            ?disabled=${!canDeleteField}
            title=${deleteA11y.title}
            aria-describedby=${deleteA11y.ariaDescribedBy ?? nothing}
            @click=${() => callbacks.onCommand({ type: 'deleteField', fieldId: field.id })}
          >
            Delete Field
          </button>
          ${deleteA11y.showHint
            ? html`<span
                id=${deleteA11y.hintId}
                style="position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0, 0, 0, 0);white-space:nowrap;border:0;"
              >
                ${deleteA11y.hintText}
              </span>`
            : nothing}
        </div>
      </div>
    </div>
  `;
}

interface DeleteFieldA11yState {
  title: string;
  ariaDescribedBy: string | null;
  showHint: boolean;
  hintId: string;
  hintText: string;
}

export function getDeleteFieldA11y(fieldId: string, canDeleteField: boolean): DeleteFieldA11yState {
  const hintId = `dc-delete-hint-${fieldId}`;
  const hintText = 'Delete is disabled. A schema must contain at least one field.';

  if (canDeleteField) {
    return {
      title: 'Delete field',
      ariaDescribedBy: null,
      showHint: false,
      hintId,
      hintText,
    };
  }

  return {
    title: 'A schema must contain at least one field',
    ariaDescribedBy: hintId,
    showHint: true,
    hintId,
    hintText,
  };
}

// =============================================================================
// Type-specific constraints
// =============================================================================

function renderTypeConstraints(
  field: SchemaField,
  emitUpdate: (updates: SchemaFieldUpdate) => void,
): unknown {
  if (field.type === 'string') {
    return renderStringConstraints(field, emitUpdate);
  }
  if (field.type === 'number' || field.type === 'integer') {
    return renderNumericConstraints(field, emitUpdate);
  }
  if (field.type === 'array') {
    return renderArrayConstraints(field, emitUpdate);
  }
  return nothing;
}

function renderStringConstraints(
  field: PrimitiveField,
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
        @change=${(e: Event) => {
          const val = getSelectValue(e);
          if (!val) {
            emitUpdate({ format: undefined });
            return;
          }
          if (!isStringFormat(val)) {
            return;
          }
          emitUpdate({ format: val });
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
          .value=${typeof min !== 'undefined' ? String(min) : ''}
          placeholder="—"
          @change=${(e: Event) => {
            const val = getInputValue(e);
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
          .value=${typeof max !== 'undefined' ? String(max) : ''}
          placeholder="—"
          @change=${(e: Event) => {
            const val = getInputValue(e);
            emitUpdate({ maximum: val ? Number(val) : undefined });
          }}
        />
      </div>
    </div>
  `;
}

function renderArrayConstraints(
  field: ArrayField,
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
        .value=${typeof minItems !== 'undefined' ? String(minItems) : ''}
        placeholder="—"
        @change=${(e: Event) => {
          const val = getInputValue(e);
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
