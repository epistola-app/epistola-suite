/**
 * ExampleForm — Auto-generates form inputs from a JSON Schema.
 *
 * Renders type-appropriate inputs for each schema property:
 *   string  -> text input
 *   number  -> number input (step="any")
 *   integer -> number input (step="1")
 *   boolean -> checkbox
 *   object  -> collapsible section with nested fields
 *   array   -> repeatable rows with add/remove controls
 *
 * The onChange callback receives (dotPath, newValue) for immutable deep updates.
 *
 * Inline validation: an optional `errors` map (form path -> error message)
 * adds red borders and error text to invalid fields, and red dots to
 * collapsed groups containing errors.
 */

import { html, nothing } from 'lit';
import type { JsonObject, JsonSchema, JsonSchemaProperty, JsonValue } from '../types.js';
import type { SchemaValidationError } from '../utils/schemaValidation.js';
import {
  getNestedValue,
  validationPathToFormPath,
} from '../utils/nestedValue.js';

export {
  deleteNestedValue,
  getNestedValue,
  normalizePath,
  setNestedValue,
  validationPathToFormPath,
} from '../utils/nestedValue.js';

/**
 * Build a Map<formPath, errorMessage> from validation errors.
 * Used to drive inline error indicators on form fields.
 */
export function buildFieldErrorMap(errors: SchemaValidationError[]): Map<string, string> {
  const map = new Map<string, string>();
  for (const err of errors) {
    const formPath = validationPathToFormPath(err.path);
    // Keep the first error per path (most relevant)
    if (!map.has(formPath)) {
      map.set(formPath, err.message);
    }
  }
  return map;
}

/**
 * Check if any error path starts with the given prefix.
 * Used to show red dots on collapsed groups containing errors.
 */
export function hasChildErrors(parentPath: string, errors: Map<string, string>): boolean {
  const prefix = parentPath + '.';
  for (const key of errors.keys()) {
    if (key === parentPath || key.startsWith(prefix)) return true;
  }
  return false;
}

// ---------------------------------------------------------------------------
// Render helpers
// ---------------------------------------------------------------------------

const NO_ERRORS: Map<string, string> = new Map();

export function hasFieldValue(value: JsonValue | undefined): boolean {
  return value !== undefined && value !== null;
}

export function canClearOptionalField(
  isRequired: boolean,
  currentValue: JsonValue | undefined,
): boolean {
  return !isRequired && hasFieldValue(currentValue);
}

function renderOptionalClearButton(
  path: string,
  isRequired: boolean,
  currentValue: JsonValue | undefined,
  onClear: (path: string) => void,
  label: string,
  compact = false,
): unknown {
  if (isRequired) return nothing;

  const canClear = canClearOptionalField(isRequired, currentValue);

  return html`
    <button
      type="button"
      class="dc-field-clear-btn ${compact ? 'dc-field-clear-btn-compact' : ''}"
      title="Clear ${label}"
      aria-label="Clear ${label}"
      ?disabled=${!canClear}
      @click=${(e: Event) => {
        e.preventDefault();
        e.stopPropagation();
        if (canClear) {
          onClear(path);
        }
      }}
    >
      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path
          d="M4 4l8 8M12 4l-8 8"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
        />
      </svg>
    </button>
  `;
}

function pathToErrorId(path: string): string {
  return `error-${path.replace(/\./g, '-')}`;
}

function fieldIdFromPath(path: string): string {
  return `dc-field-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
}

/**
 * Render the example form from a JSON Schema.
 * When no schema exists, shows a placeholder message.
 *
 * @param errors Optional map of form path -> error message for inline validation.
 */
export function renderExampleForm(
  schema: JsonSchema | null,
  data: JsonObject,
  onChange: (path: string, value: JsonValue) => void,
  onClear: (path: string) => void,
  errors: Map<string, string> = NO_ERRORS,
): unknown {
  if (!schema || !schema.properties || Object.keys(schema.properties).length === 0) {
    return html` <div class="dc-form-empty">Define a schema first to create examples.</div> `;
  }

  const requiredSet = new Set(schema.required ?? []);

  return html`
    <div class="dc-tree">
      ${Object.entries(schema.properties).map(([name, propSchema]) =>
        renderFormField(
          name,
          propSchema,
          name,
          data,
          requiredSet.has(name),
          onChange,
          onClear,
          0,
          errors,
        ),
      )}
    </div>
  `;
}

/**
 * Render a single form field based on its JSON Schema property type.
 * Uses compact inline rows: label and input side-by-side.
 */
export function renderFormField(
  name: string,
  propSchema: JsonSchemaProperty,
  path: string,
  rootData: JsonObject,
  isRequired: boolean,
  onChange: (path: string, value: JsonValue) => void,
  onClear: (path: string) => void,
  depth: number,
  errors: Map<string, string>,
): unknown {
  const rawType = Array.isArray(propSchema.type) ? propSchema.type[0] : propSchema.type;
  const type = rawType === 'string' && propSchema.format === 'date' ? 'date' : rawType;
  const value = getNestedValue(rootData, path);
  const fieldError = errors.get(path);
  const fieldId = fieldIdFromPath(path);

  const errorId = fieldError ? pathToErrorId(path) : undefined;

  const label = html`
    <label class="dc-tree-label" for=${fieldId}>
      ${name}${isRequired
        ? html`<span class="dc-required-mark" aria-hidden="true">*</span>`
        : nothing}
    </label>
  `;

  switch (type) {
    case 'string':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <div class="dc-tree-input-inline">
              <input
                type="text"
                class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
                id=${fieldId}
                .value=${String(value ?? '')}
                placeholder="${name}"
                aria-describedby=${fieldError ? errorId : nothing}
                @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).value)}
              />
              ${renderOptionalClearButton(path, isRequired, value, onClear, name)}
            </div>
            ${fieldError
              ? html`<span class="dc-field-error" id=${errorId}>${fieldError}</span>`
              : nothing}
          </div>
        </div>
      `;

    case 'number':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <div class="dc-tree-input-inline">
              <input
                type="number"
                class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
                step="any"
                id=${fieldId}
                .value=${value != null ? String(value) : ''}
                placeholder="${name}"
                aria-describedby=${fieldError ? errorId : nothing}
                @change=${(e: Event) => {
                  const raw = (e.target as HTMLInputElement).value;
                  if (raw === '') {
                    onClear(path);
                  } else {
                    onChange(path, parseFloat(raw));
                  }
                }}
              />
              ${renderOptionalClearButton(path, isRequired, value, onClear, name)}
            </div>
            ${fieldError
              ? html`<span class="dc-field-error" id=${errorId}>${fieldError}</span>`
              : nothing}
          </div>
        </div>
      `;

    case 'integer':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <div class="dc-tree-input-inline">
              <input
                type="number"
                class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
                step="1"
                id=${fieldId}
                .value=${value != null ? String(value) : ''}
                placeholder="${name}"
                aria-describedby=${fieldError ? errorId : nothing}
                @change=${(e: Event) => {
                  const raw = (e.target as HTMLInputElement).value;
                  if (raw === '') {
                    onClear(path);
                  } else {
                    onChange(path, parseInt(raw, 10));
                  }
                }}
              />
              ${renderOptionalClearButton(path, isRequired, value, onClear, name)}
            </div>
            ${fieldError
              ? html`<span class="dc-field-error" id=${errorId}>${fieldError}</span>`
              : nothing}
          </div>
        </div>
      `;

    case 'boolean':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <div class="dc-tree-input-inline">
              <label class="dc-tree-checkbox">
                <input
                  type="checkbox"
                  class="ep-checkbox"
                  id=${fieldId}
                  .checked=${value === true}
                  aria-label="${name}"
                  aria-describedby=${fieldError ? errorId : nothing}
                  @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).checked)}
                />
              </label>
              ${renderOptionalClearButton(path, isRequired, value, onClear, name)}
            </div>
            ${fieldError
              ? html`<span class="dc-field-error" id=${errorId}>${fieldError}</span>`
              : nothing}
          </div>
        </div>
      `;

    case 'date':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <div class="dc-tree-input-inline">
              <input
                type="date"
                class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
                id=${fieldId}
                .value=${String(value ?? '')}
                aria-describedby=${fieldError ? errorId : nothing}
                @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).value)}
              />
              ${renderOptionalClearButton(path, isRequired, value, onClear, name)}
            </div>
            ${fieldError
              ? html`<span class="dc-field-error" id=${errorId}>${fieldError}</span>`
              : nothing}
          </div>
        </div>
      `;

    case 'object':
      return renderObjectField(
        name,
        propSchema,
        path,
        rootData,
        isRequired,
        onChange,
        onClear,
        depth,
        errors,
      );

    case 'array':
      return renderArrayField(
        name,
        propSchema,
        path,
        rootData,
        isRequired,
        onChange,
        onClear,
        depth,
        errors,
      );

    default:
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <div class="dc-tree-input-inline">
              <input
                type="text"
                class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
                id=${fieldId}
                .value=${String(value ?? '')}
                placeholder="${name}"
                aria-describedby=${fieldError ? errorId : nothing}
                @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).value)}
              />
              ${renderOptionalClearButton(path, isRequired, value, onClear, name)}
            </div>
            ${fieldError
              ? html`<span class="dc-field-error" id=${errorId}>${fieldError}</span>`
              : nothing}
          </div>
        </div>
      `;
  }
}

/**
 * Render a collapsible object field with nested properties.
 * Top-level objects open by default; deeper nesting collapsed.
 */
function renderObjectField(
  name: string,
  propSchema: JsonSchemaProperty,
  path: string,
  rootData: JsonObject,
  isRequired: boolean,
  onChange: (path: string, value: JsonValue) => void,
  onClear: (path: string) => void,
  depth: number,
  errors: Map<string, string>,
): unknown {
  const currentValue = getNestedValue(rootData, path);

  if (!propSchema.properties || Object.keys(propSchema.properties).length === 0) {
    return html`
      <div class="dc-tree-row">
        <span class="dc-tree-label">
          ${name}${isRequired
            ? html`<span class="dc-required-mark" aria-hidden="true">*</span>`
            : nothing}
        </span>
        <div class="dc-tree-inline-controls">
          <span class="dc-tree-hint">No properties defined</span>
          ${renderOptionalClearButton(path, isRequired, currentValue, onClear, name)}
        </div>
      </div>
    `;
  }

  const nestedRequired = new Set(propSchema.required ?? []);
  const groupHasErrors = hasChildErrors(path, errors);
  const isTopLevel = depth === 0;

  return html`
    <details
      class="dc-tree-group ${groupHasErrors ? 'dc-tree-group-has-errors' : ''}"
      ?open=${isTopLevel}
      aria-label="${name} properties"
    >
      <summary class="dc-tree-group-header">
        <span class="dc-tree-group-title">
          ${name}${isRequired
            ? html`<span class="dc-required-mark" aria-hidden="true">*</span>`
            : nothing}
        </span>
        ${groupHasErrors
          ? html`<span class="dc-tree-group-error-dot" aria-hidden="true"></span>`
          : nothing}
        ${renderOptionalClearButton(path, isRequired, currentValue, onClear, name, true)}
        <span class="dc-tree-type-badge" data-type="object" aria-hidden="true">object</span>
      </summary>
      <div class="dc-tree-group-body">
        ${Object.entries(propSchema.properties).map(([nestedName, nestedProp]) =>
          renderFormField(
            nestedName,
            nestedProp,
            `${path}.${nestedName}`,
            rootData,
            nestedRequired.has(nestedName),
            onChange,
            onClear,
            depth + 1,
            errors,
          ),
        )}
      </div>
    </details>
  `;
}

/**
 * Render an array field with repeatable rows and add/remove controls.
 */
function renderArrayField(
  name: string,
  propSchema: JsonSchemaProperty,
  path: string,
  rootData: JsonObject,
  isRequired: boolean,
  onChange: (path: string, value: JsonValue) => void,
  onClear: (path: string) => void,
  depth: number,
  errors: Map<string, string>,
): unknown {
  const currentValue = getNestedValue(rootData, path);
  const items: JsonValue[] = Array.isArray(currentValue) ? currentValue : [];
  const itemSchema = propSchema.items;
  const rawItemType = itemSchema
    ? Array.isArray(itemSchema.type)
      ? itemSchema.type[0]
      : itemSchema.type
    : 'string';
  const itemType = rawItemType === 'string' && itemSchema?.format === 'date' ? 'date' : rawItemType;

  const addItem = () => {
    const defaultValue = getDefaultValueForType(itemType, itemSchema);
    const newItems = [...items, defaultValue];
    onChange(path, newItems);
  };

  const removeItem = (index: number) => {
    const newItems = items.filter((_, i) => i !== index);
    onChange(path, newItems);
  };

  if (itemType === 'object' && itemSchema?.properties) {
    return renderArrayOfObjects(
      name,
      itemSchema,
      path,
      items,
      currentValue,
      rootData,
      isRequired,
      onChange,
      onClear,
      addItem,
      removeItem,
      depth,
      errors,
    );
  }

  const groupHasErrors = hasChildErrors(path, errors);

  // Array of primitives
  return html`
    <details
      class="dc-tree-group ${groupHasErrors ? 'dc-tree-group-has-errors' : ''}"
      ?open=${items.length > 0}
      aria-label="${name} array"
    >
      <summary class="dc-tree-group-header">
        <span class="dc-tree-group-title">
          ${name}${isRequired
            ? html`<span class="dc-required-mark" aria-hidden="true">*</span>`
            : nothing}
        </span>
        ${groupHasErrors
          ? html`<span class="dc-tree-group-error-dot" aria-hidden="true"></span>`
          : nothing}
        ${renderOptionalClearButton(path, isRequired, currentValue, onClear, name, true)}
        <span class="dc-tree-type-badge" data-type="list" aria-hidden="true">${itemType}[]</span>
        <span class="dc-tree-count-badge" aria-hidden="true">${items.length}</span>
      </summary>
      <div class="dc-tree-group-body dc-tree-group-body-array" role="list">
        ${items.map((item, index) => {
          const itemPath = `${path}.${index}`;
          const itemError = errors.get(itemPath);
          return html`
            <div class="dc-array-item-row" role="listitem">
              <span class="dc-array-item-number" aria-hidden="true">${index + 1}</span>
              <div class="dc-array-item-content">
                <div class="dc-array-item-input-wrapper">
                  ${renderPrimitiveInput(
                    itemType,
                    item,
                    `${name}[${index}]`,
                    (newValue) => {
                      const newItems = [...items];
                      newItems[index] = newValue;
                      onChange(path, newItems);
                    },
                    itemError,
                  )}
                </div>
                ${itemError ? html`<span class="dc-field-error">${itemError}</span>` : nothing}
              </div>
              <button
                class="dc-array-item-remove"
                title="Remove item"
                aria-label="Remove item ${index + 1}"
                @click=${() => removeItem(index)}
              >
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path
                    d="M4 4l8 8M12 4l-8 8"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-linecap="round"
                  />
                </svg>
              </button>
            </div>
          `;
        })}
        <button class="dc-array-add-btn" @click=${() => addItem()}>
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path
              d="M8 3v10M3 8h10"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
            />
          </svg>
          Add ${itemType} item
        </button>
      </div>
    </details>
  `;
}

/**
 * Render an array of objects with nested forms per item.
 */
function renderArrayOfObjects(
  name: string,
  itemSchema: JsonSchemaProperty,
  path: string,
  items: JsonValue[],
  currentValue: JsonValue | undefined,
  rootData: JsonObject,
  isRequired: boolean,
  onChange: (path: string, value: JsonValue) => void,
  onClear: (path: string) => void,
  addItem: () => void,
  removeItem: (index: number) => void,
  depth: number,
  errors: Map<string, string>,
): unknown {
  const nestedRequired = new Set(itemSchema.required ?? []);
  const groupHasErrors = hasChildErrors(path, errors);

  return html`
    <details
      class="dc-tree-group ${groupHasErrors ? 'dc-tree-group-has-errors' : ''}"
      ?open=${items.length > 0}
      aria-label="${name} array of objects"
    >
      <summary class="dc-tree-group-header">
        <span class="dc-tree-group-title">
          ${name}${isRequired
            ? html`<span class="dc-required-mark" aria-hidden="true">*</span>`
            : nothing}
        </span>
        ${groupHasErrors
          ? html`<span class="dc-tree-group-error-dot" aria-hidden="true"></span>`
          : nothing}
        ${renderOptionalClearButton(path, isRequired, currentValue, onClear, name, true)}
        <span class="dc-tree-type-badge" data-type="list" aria-hidden="true">object[]</span>
        <span class="dc-tree-count-badge" aria-hidden="true">${items.length}</span>
      </summary>
      <div class="dc-tree-group-body dc-tree-group-body-array" role="list">
        ${items.map((_item, index) => {
          const itemPath = `${path}.${index}`;
          const itemHasErrors = hasChildErrors(itemPath, errors);
          return html`
            <details
              class="dc-array-object-item ${itemHasErrors ? 'dc-tree-group-has-errors' : ''}"
              open
              role="listitem"
              aria-label="Item ${index + 1}"
            >
              <summary class="dc-array-object-header">
                <span class="dc-array-item-number dc-array-item-number-lg" aria-hidden="true"
                  >${index + 1}</span
                >
                ${itemHasErrors
                  ? html`<span class="dc-tree-group-error-dot" aria-hidden="true"></span>`
                  : nothing}
                <div class="dc-array-object-spacer" aria-hidden="true"></div>
                <button
                  class="dc-array-item-remove dc-array-item-remove-subtle"
                  title="Remove item"
                  aria-label="Remove item ${index + 1}"
                  @click=${(e: Event) => {
                    e.preventDefault();
                    removeItem(index);
                  }}
                >
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path
                      d="M4 4l8 8M12 4l-8 8"
                      stroke="currentColor"
                      stroke-width="1.5"
                      stroke-linecap="round"
                    />
                  </svg>
                </button>
              </summary>
              <div class="dc-array-object-content">
                ${itemSchema.properties
                  ? Object.entries(itemSchema.properties).map(([nestedName, nestedProp]) =>
                      renderFormField(
                        nestedName,
                        nestedProp,
                        `${path}.${index}.${nestedName}`,
                        rootData,
                        nestedRequired.has(nestedName),
                        onChange,
                        onClear,
                        depth + 1,
                        errors,
                      ),
                    )
                  : nothing}
              </div>
            </details>
          `;
        })}
        <button class="dc-array-add-btn" @click=${() => addItem()}>
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path
              d="M8 3v10M3 8h10"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
            />
          </svg>
          Add object item
        </button>
      </div>
    </details>
  `;
}

/**
 * Render a primitive input (string, number, integer, boolean).
 */
function renderPrimitiveInput(
  type: string,
  value: JsonValue,
  label: string,
  onChange: (value: JsonValue) => void,
  error?: string,
): unknown {
  const errorClass = error ? 'dc-input-error' : '';

  switch (type) {
    case 'number':
      return html`
        <input
          type="number"
          class="ep-input dc-array-item-input ${errorClass}"
          step="any"
          .value=${value != null ? String(value) : ''}
          placeholder="${label}"
          aria-label="${label}"
          @change=${(e: Event) => {
            const raw = (e.target as HTMLInputElement).value;
            onChange(raw === '' ? null : parseFloat(raw));
          }}
        />
      `;
    case 'integer':
      return html`
        <input
          type="number"
          class="ep-input dc-array-item-input ${errorClass}"
          step="1"
          .value=${value != null ? String(value) : ''}
          placeholder="${label}"
          aria-label="${label}"
          @change=${(e: Event) => {
            const raw = (e.target as HTMLInputElement).value;
            onChange(raw === '' ? null : parseInt(raw, 10));
          }}
        />
      `;
    case 'boolean':
      return html`
        <label class="dc-form-checkbox-label">
          <input
            type="checkbox"
            class="ep-checkbox"
            .checked=${value === true}
            aria-label="${label}"
            @change=${(e: Event) => onChange((e.target as HTMLInputElement).checked)}
          />
        </label>
      `;
    case 'date':
      return html`
        <input
          type="date"
          class="ep-input dc-array-item-input ${errorClass}"
          .value=${String(value ?? '')}
          aria-label="${label}"
          @change=${(e: Event) => onChange((e.target as HTMLInputElement).value)}
        />
      `;
    default:
      return html`
        <input
          type="text"
          class="ep-input dc-array-item-input ${errorClass}"
          .value=${String(value ?? '')}
          placeholder="${label}"
          aria-label="${label}"
          @change=${(e: Event) => onChange((e.target as HTMLInputElement).value)}
        />
      `;
  }
}

/**
 * Get a sensible default value for a given JSON Schema type.
 */
function getDefaultValueForType(type: string, schema?: JsonSchemaProperty | null): JsonValue {
  switch (type) {
    case 'string':
      return '';
    case 'number':
    case 'integer':
      return 0;
    case 'boolean':
      return false;
    case 'date':
      return '';
    case 'object': {
      if (!schema?.properties) return {};
      const obj: Record<string, JsonValue> = {};
      for (const [key, propSchema] of Object.entries(schema.properties)) {
        const propType = Array.isArray(propSchema.type) ? propSchema.type[0] : propSchema.type;
        obj[key] = getDefaultValueForType(propType, propSchema);
      }
      return obj;
    }
    case 'array':
      return [];
    default:
      return '';
  }
}
