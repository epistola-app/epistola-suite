/**
 * SchemaFixScreen — Interactive overlay for fixing schema/example mismatches.
 *
 * Shows problematic fields grouped by example. Each field shows its current
 * value so the user can edit it. Validates against the new schema on continue.
 *
 * Field values are stored in the store's FixScreenState, not in a mutable Map.
 * The @input handler dispatches fix-field-change to the store.
 */

import { html, nothing } from 'lit';
import type {
  DataExample,
  JsonSchema,
  JsonObject,
  JsonSchemaProperty,
  MigrationIssueType,
  JsonValue,
} from '../types.js';
import type { CompatibilityMigrationSuggestion } from '../types.js';
import type { FixFieldValue } from '../store-types.js';
import { renderFormField, normalizePath } from './ExampleForm.js';
import { renderValidationMessages, type ValidationWarning } from './ValidationMessages.js';

/* oxlint-disable eslint/no-use-before-define */

export interface SchemaFixScreenCallbacks {
  onFieldChange: (exampleId: string, path: string, value: JsonValue) => void;
  onRemoveField: (exampleId: string, path: string) => void;
  onRemoveAllUnknown: () => void;
  onRevert: () => void;
  onContinue: () => void;
  onForceSave: () => void;
  onCancel: () => void;
}

interface FixFieldGroup {
  exampleId: string;
  exampleName: string;
  fields: FixField[];
}

interface FixField {
  path: string;
  issue: MigrationIssueType;
  currentValue: JsonValue | null;
  expectedType: string;
}

const noopClear = (_path: string): void => {
  return;
};

export function renderSchemaFixScreen(
  migrations: CompatibilityMigrationSuggestion[],
  examples: DataExample[],
  newSchema: JsonSchema | null,
  fieldValues: Map<string, FixFieldValue>,
  fieldErrors: Map<string, string>,
  editedData: Map<string, JsonObject>,
  warnings: ValidationWarning[],
  saveError: string | null,
  canForceSave: boolean,
  callbacks: SchemaFixScreenCallbacks,
): unknown {
  if (!newSchema) return nothing;
  const groups = groupByExample(migrations);
  const unknownFields = migrations.filter((m) => m.issue === 'UNKNOWN_FIELD');
  const totalFields = migrations.length;
  const hasFixes = totalFields > 0;

  return html`
    <div class="dc-fix-overlay">
      <div class="dc-fix-screen">
        <div class="dc-fix-header">
          <div class="dc-fix-title-row">
            <h3 class="dc-fix-title">Fix Schema Changes</h3>
            <button
              class="dc-fix-close-btn"
              aria-label="Close"
              @click=${(e: Event) => {
                e.preventDefault();
                callbacks.onCancel();
              }}
            >
              <svg width="18" height="18" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M4 4l8 8M12 4l-8 8"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                />
              </svg>
            </button>
          </div>
          <p class="dc-fix-subtitle">
            ${hasFixes
              ? html`${totalFields} field${totalFields !== 1 ? 's' : ''} in ${groups.length}
                example${groups.length !== 1 ? 's' : ''} need updating before saving the schema
                change.`
              : html`Recent generation compatibility issues need review before saving.`}
          </p>
        </div>

        ${unknownFields.length > 0
          ? html`
              <div class="dc-fix-bulk-actions">
                <button
                  class="ep-btn-outline btn-sm dc-fix-remove-unknown-btn"
                  @click=${(e: Event) => {
                    e.preventDefault();
                    callbacks.onRemoveAllUnknown();
                  }}
                >
                  Remove ${unknownFields.length} unknown
                  field${unknownFields.length !== 1 ? 's' : ''}
                </button>
              </div>
            `
          : nothing}
        ${hasFixes
          ? html`
              <div class="dc-fix-groups">
                ${groups.map((group) =>
                  renderFixFieldGroup(
                    group,
                    examples,
                    fieldValues,
                    fieldErrors,
                    editedData,
                    callbacks,
                    newSchema,
                  ),
                )}
              </div>
            `
          : nothing}
        ${warnings.length > 0
          ? html` <div class="dc-fix-warnings">${renderValidationMessages(warnings)}</div> `
          : nothing}
        ${saveError ? html`<div class="dc-fix-error">${saveError}</div>` : nothing}

        <div class="dc-fix-footer">
          <button
            class="btn btn-sm btn-ghost"
            @click=${(e: Event) => {
              e.preventDefault();
              callbacks.onCancel();
            }}
          >
            Cancel
          </button>

          ${canForceSave
            ? html`
                <button
                  class="btn btn-sm dc-fix-force-save-btn"
                  @click=${(e: Event) => {
                    e.preventDefault();
                    callbacks.onForceSave();
                  }}
                  title="Save schema despite compatibility warnings"
                >
                  Save Anyway
                </button>
              `
            : nothing}
          ${saveError
            ? html`
                <button
                  class="btn btn-sm btn-ghost dc-fix-revert-btn"
                  @click=${(e: Event) => {
                    e.preventDefault();
                    callbacks.onRevert();
                  }}
                  title="Revert schema and test data to last saved state"
                >
                  Revert
                </button>
              `
            : nothing}
          ${hasFixes
            ? html`
                <button
                  class="btn btn-sm btn-primary dc-fix-continue-btn"
                  @click=${() => callbacks.onContinue()}
                >
                  Continue
                </button>
              `
            : nothing}
        </div>
      </div>
    </div>
  `;
}

function renderFixFieldGroup(
  group: FixFieldGroup,
  examples: DataExample[],
  fieldValues: Map<string, FixFieldValue>,
  fieldErrors: Map<string, string>,
  editedData: Map<string, JsonObject>,
  callbacks: SchemaFixScreenCallbacks,
  newSchema: JsonSchema,
): unknown {
  const example = examples.find((e) => e.id === group.exampleId);
  if (!example) return nothing;
  const rootData = editedData.get(group.exampleId) ?? example.data;

  return html`
    <div class="dc-fix-group">
      <div class="dc-fix-group-header">
        <span class="dc-fix-group-name">${group.exampleName}</span>
        <span class="dc-fix-group-count"
          >${group.fields.length} field${group.fields.length !== 1 ? 's' : ''}</span
        >
      </div>
      <div class="dc-fix-group-fields">
        ${group.fields.map((field) =>
          renderFixField(
            group.exampleId,
            field,
            fieldValues,
            fieldErrors,
            callbacks,
            newSchema,
            rootData,
          ),
        )}
      </div>
    </div>
  `;
}

function renderFixField(
  exampleId: string,
  field: FixField,
  fieldValues: Map<string, FixFieldValue>,
  fieldErrors: Map<string, string>,
  callbacks: SchemaFixScreenCallbacks,
  newSchema: JsonSchema,
  rootData: JsonObject,
): unknown {
  const stateKey = `${exampleId}:${field.path}`;
  const state = fieldValues.get(stateKey);
  const removed = state ? state.removed : false;

  if (removed) {
    return html`
      <div class="dc-fix-field dc-fix-field-removed">
        <div class="dc-fix-field-header">
          <code class="dc-fix-field-path">${field.path}</code>
          <span class="badge badge-warning dc-fix-field-badge">Removed</span>
        </div>
      </div>
    `;
  }

  if (field.issue === 'UNKNOWN_FIELD') {
    const currentValue = state ? state.value : field.currentValue;
    return html`
      <div class="dc-fix-field">
        <div class="dc-fix-field-header">
          <code class="dc-fix-field-path">${field.path}</code>
          <span class="badge badge-info dc-fix-field-badge">Unknown field</span>
        </div>
        <div class="dc-fix-field-unknown-value">
          <span class="dc-fix-field-readonly">${formatValue(currentValue)}</span>
          <span class="dc-fix-field-hint">Not in new schema</span>
        </div>
      </div>
    `;
  }

  // TYPE_MISMATCH or MISSING_REQUIRED — use full form field renderer
  const dotPath = normalizePath(field.path);
  const segments = dotPath.split('.');
  const fieldName = segments[segments.length - 1];
  const propSchema = getPropertySchema(newSchema, dotPath);
  const error = fieldErrors.get(stateKey) ?? null;
  const errorMap = error ? new Map([[dotPath, error]]) : new Map<string, string>();

  // Wrap onChange to dispatch fix-field-change with the actual sub-path
  const onChange = (subPath: string, value: JsonValue): void => {
    callbacks.onFieldChange(exampleId, subPath, value);
  };

  return html`
    <div class="dc-fix-field ${error ? 'dc-fix-field-error' : ''}">
      <div class="dc-fix-field-header">
        <code class="dc-fix-field-path">${field.path}</code>
        ${field.issue === 'TYPE_MISMATCH'
          ? html`
              <span class="badge badge-warning dc-fix-field-badge">Type mismatch</span>
              <span class="dc-fix-field-type-hint">
                Expected: <strong>${field.expectedType}</strong>
              </span>
            `
          : html`<span class="badge badge-error dc-fix-field-badge">Required</span>`}
      </div>
      ${propSchema
        ? renderFormField(
            fieldName,
            propSchema,
            dotPath,
            rootData,
            true,
            onChange,
            noopClear,
            0,
            errorMap,
            false,
          )
        : html`<input
            type="text"
            class="ep-input dc-fix-field-input"
            .value=${toInputString(field.currentValue)}
            placeholder="${fieldName}"
          />`}
      ${error ? html`<span class="dc-field-error">${error}</span>` : nothing}
    </div>
  `;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function groupByExample(migrations: CompatibilityMigrationSuggestion[]): FixFieldGroup[] {
  const groups = new Map<string, FixFieldGroup>();

  for (const m of migrations) {
    const existing = groups.get(m.exampleId);
    if (existing) {
      existing.fields.push({
        path: m.path,
        issue: m.issue,
        currentValue: m.currentValue,
        expectedType: m.expectedType,
      });
    } else {
      groups.set(m.exampleId, {
        exampleId: m.exampleId,
        exampleName: m.exampleName,
        fields: [
          {
            path: m.path,
            issue: m.issue,
            currentValue: m.currentValue,
            expectedType: m.expectedType,
          },
        ],
      });
    }
  }

  return Array.from(groups.values());
}

/**
 * Look up a property's JSON Schema by dot-separated path.
 * e.g., "person.age" → schema.properties.person.properties.age
 */
function getPropertySchema(schema: JsonSchema, dotPath: string): JsonSchemaProperty | null {
  const segments = dotPath.split('.');
  let current: unknown = schema;

  for (let i = 0; i < segments.length; i += 1) {
    if (!isRecord(current)) {
      return null;
    }

    const segment = segments[i];
    const props = getProperties(current);

    // Last segment: return the property schema
    if (i === segments.length - 1) {
      let candidate: unknown = null;
      if (props) {
        candidate = props[segment];
      }

      if (isJsonSchemaProperty(candidate)) {
        return candidate;
      }

      // Numeric segment: return the array items schema
      if (/^\d+$/.test(segment)) {
        const items = getItems(current);
        if (isJsonSchemaProperty(items)) {
          return items;
        }
      }

      return null;
    }

    // Numeric segment: skip through array items schema
    if (/^\d+$/.test(segment)) {
      const items = getItems(current);
      if (!items) {
        return null;
      }

      current = items;
      continue;
    }

    // Navigate through properties or items
    let childFromProperties: unknown = null;
    if (props) {
      childFromProperties = props[segment];
    }

    if (isRecord(childFromProperties)) {
      current = childFromProperties;
      continue;
    }

    const items = getItems(current);
    if (!items) {
      return null;
    }

    const nestedProps = getProperties(items);
    let childFromItems: unknown = null;
    if (nestedProps) {
      childFromItems = nestedProps[segment];
    }

    if (isRecord(childFromItems)) {
      current = childFromItems;
    } else {
      return null;
    }
  }

  return isJsonSchemaProperty(current) ? current : null;
}

function formatValue(value: unknown): string {
  if (value === null) return 'null';
  if (typeof value === 'undefined') return 'undefined';
  if (typeof value === 'string') return `"${value}"`;
  if (typeof value === 'number') return `${value}`;
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'bigint') return `${value}`;
  if (typeof value === 'symbol') return value.toString();
  if (typeof value === 'object') return JSON.stringify(value);
  return '';
}

function toInputString(value: JsonValue | null): string {
  if (value === null) {
    return '';
  }

  if (typeof value === 'string') {
    return value;
  }

  if (typeof value === 'number' || typeof value === 'boolean') {
    return `${value}`;
  }

  return JSON.stringify(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function getProperties(node: Record<string, unknown>): Record<string, unknown> | null {
  const properties = node.properties;
  return isRecord(properties) ? properties : null;
}

function getItems(node: Record<string, unknown>): Record<string, unknown> | null {
  const items = node.items;
  return isRecord(items) ? items : null;
}

function isJsonSchemaProperty(value: unknown): value is JsonSchemaProperty {
  if (!isRecord(value)) {
    return false;
  }

  const typeValue = value.type;
  if (typeof typeValue === 'string') {
    return true;
  }

  return Array.isArray(typeValue) && typeValue.every((item) => typeof item === 'string');
}
