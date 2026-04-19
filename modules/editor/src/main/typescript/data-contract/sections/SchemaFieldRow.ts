/**
 * SchemaFieldRow — Renders a compact, clickable field list item.
 *
 * Shows field name, type badge, and required indicator.
 * Supports expand/collapse for object/array-of-objects fields.
 * No inline editing — selection opens the detail panel.
 */

import { html, nothing } from 'lit';
import type { SchemaField } from '../types.js';
import { FIELD_TYPE_LABELS } from '../utils/schemaUtils.js';

/** Check if a field supports nested fields */
function supportsNestedFields(field: SchemaField): boolean {
  if (field.type === 'object') return true;
  if (field.type === 'array' && field.arrayItemType === 'object') return true;
  return false;
}

export function renderSchemaFieldListItem(
  field: SchemaField,
  depth: number,
  expandedFields: Set<string>,
  selectedFieldId: string | null,
  onToggleExpand: (fieldId: string) => void,
  onSelectField: (fieldId: string) => void,
): unknown {
  const canExpand = supportsNestedFields(field);
  const nestedFields =
    field.type === 'object' || field.type === 'array' ? (field.nestedFields ?? []) : [];
  const isExpanded = expandedFields.has(field.id);
  const isSelected = selectedFieldId === field.id;

  const nestStyle = depth > 0 ? `--nest-depth: ${depth}` : null;

  return html`
    <div
      class="dc-field-list-item ${isSelected ? 'dc-field-list-item-selected' : ''}"
      style=${nestStyle ?? nothing}
      @click=${(e: Event) => {
        e.stopPropagation();
        onSelectField(field.id);
      }}
    >
      ${canExpand
        ? html`
            <button
              class="dc-field-expand-btn"
              @click=${(e: Event) => {
                e.stopPropagation();
                onToggleExpand(field.id);
              }}
              title="${isExpanded ? 'Collapse' : 'Expand'} nested fields"
              aria-expanded="${isExpanded}"
            >
              ${isExpanded ? '\u25BC' : '\u25B6'}
            </button>
          `
        : html`<span class="dc-field-expand-spacer"></span>`}

      <span class="dc-field-list-item-name">${field.name}</span>
      <span class="dc-field-type-badge">${FIELD_TYPE_LABELS[field.type]}</span>
      ${field.required ? html`<span class="dc-field-required-dot"></span>` : nothing}
    </div>

    ${canExpand && isExpanded && nestedFields.length > 0
      ? html`
          <div class="dc-field-nested-items">
            ${nestedFields.map((nestedField) =>
              renderSchemaFieldListItem(
                nestedField,
                depth + 1,
                expandedFields,
                selectedFieldId,
                onToggleExpand,
                onSelectField,
              ),
            )}
          </div>
        `
      : nothing}
  `;
}
