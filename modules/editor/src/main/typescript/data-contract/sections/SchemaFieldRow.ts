/**
 * SchemaFieldRow — Renders a single schema field row with type controls.
 *
 * Supports expand/collapse for object/array-of-objects fields,
 * inline editing of name/type/required, and nested field management.
 * Nested rows are prevented from further nesting via the `isNested` flag.
 */

import { html, nothing } from 'lit'
import type { SchemaField, SchemaFieldType, SchemaFieldUpdate } from '../types.js'
import { applyFieldUpdate, createEmptyField, FIELD_TYPE_LABELS } from '../utils/schemaUtils.js'

export interface SchemaFieldRowCallbacks {
  onUpdate: (fieldId: string, updates: SchemaFieldUpdate) => void
  onDelete: (fieldId: string) => void
}

/** All field types available in the type dropdown */
const FIELD_TYPES: SchemaFieldType[] = ['string', 'number', 'integer', 'boolean', 'array', 'object']

/** Item types available for arrays */
const ARRAY_ITEM_TYPES: SchemaFieldType[] = ['string', 'number', 'integer', 'boolean', 'object']

/** Check if a field supports nested fields */
function supportsNestedFields(field: SchemaField): boolean {
  if (field.type === 'object') return true
  if (field.type === 'array' && field.arrayItemType === 'object') return true
  return false
}

export function renderSchemaFieldRow(
  field: SchemaField,
  callbacks: SchemaFieldRowCallbacks,
  isNested: boolean,
  expandedFields?: Set<string>,
  onToggleExpand?: (fieldId: string) => void,
): unknown {
  const hasNested = supportsNestedFields(field)
  const nestedFields = (field.type === 'object' || field.type === 'array') ? field.nestedFields ?? [] : []
  const isExpanded = expandedFields?.has(field.id) ?? false
  const canExpand = hasNested && !isNested

  return html`
    <div class="dc-field-row ${isNested ? 'dc-field-nested' : ''}">
      <div class="dc-field-controls">
        ${canExpand
          ? html`
              <button
                class="dc-field-expand-btn"
                @click=${() => onToggleExpand?.(field.id)}
                title="${isExpanded ? 'Collapse' : 'Expand'} nested fields"
                aria-expanded="${isExpanded}"
              >
                ${isExpanded ? '\u25BC' : '\u25B6'}
              </button>
            `
          : html`<span class="dc-field-expand-spacer"></span>`
        }

        <input
          type="text"
          class="ep-input dc-field-name-input"
          .value=${field.name}
          placeholder="Field name"
          aria-label="Field name"
          @change=${(e: Event) => {
            const value = (e.target as HTMLInputElement).value.trim()
            if (value && value !== field.name) {
              callbacks.onUpdate(field.id, { name: value })
            }
          }}
        />

        <select
          class="ep-select dc-field-type-select"
          .value=${field.type}
          aria-label="Field type"
          @change=${(e: Event) => {
            const newType = (e.target as HTMLSelectElement).value as SchemaFieldType
            const updates: SchemaFieldUpdate = { type: newType }
            if (newType === 'array') {
              updates.arrayItemType = 'string'
            }
            callbacks.onUpdate(field.id, updates)
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
                  const newItemType = (e.target as HTMLSelectElement).value as SchemaFieldType
                  callbacks.onUpdate(field.id, { arrayItemType: newItemType })
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
          : html`<span></span>`
        }

        <label class="dc-field-required-label" title="Required field">
          <input
            type="checkbox"
            class="ep-checkbox"
            .checked=${field.required}
            aria-label="Required"
            @change=${(e: Event) => {
              callbacks.onUpdate(field.id, { required: (e.target as HTMLInputElement).checked })
            }}
          />
        </label>

        ${canExpand
          ? html`
              <button
                class="dc-field-add-nested-btn ep-btn-outline btn-sm"
                title="Add nested field"
                @click=${() => {
                  const newField = createEmptyField('newField')
                  const updatedNested = [...nestedFields, newField]
                  callbacks.onUpdate(field.id, { nestedFields: updatedNested })
                  if (!isExpanded) {
                    onToggleExpand?.(field.id)
                  }
                }}
              >+</button>
            `
          : html`<span></span>`
        }

        <button
          class="dc-field-delete-btn"
          title="Delete field"
          aria-label="Delete field"
          @click=${() => callbacks.onDelete(field.id)}
        >\u00D7</button>
      </div>

      ${canExpand && isExpanded
        ? html`
            <div class="dc-field-nested-container">
              ${nestedFields.length === 0
                ? html`<div class="dc-field-nested-empty">No nested fields. Click "+ Nested" to add one.</div>`
                : nestedFields.map((nestedField) =>
                    renderSchemaFieldRow(
                      nestedField,
                      {
                        onUpdate: (nestedFieldId, updates) => {
                          const updatedNested = nestedFields.map((nf) =>
                            nf.id === nestedFieldId ? applyFieldUpdate(nf, updates) : nf,
                          )
                          callbacks.onUpdate(field.id, { nestedFields: updatedNested })
                        },
                        onDelete: (nestedFieldId) => {
                          const updatedNested = nestedFields.filter((nf) => nf.id !== nestedFieldId)
                          callbacks.onUpdate(field.id, { nestedFields: updatedNested })
                        },
                      },
                      true, // isNested = true, prevents further nesting
                    ),
                  )
              }
            </div>
          `
        : nothing
      }
    </div>
  `
}
