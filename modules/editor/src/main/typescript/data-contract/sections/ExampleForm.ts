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
import {
  type JsonObject,
  type JsonSchema,
  type JsonSchemaProperty,
  type JsonValue,
} from '../types.js';
import { findRefType, getRefTypeById, type RefTypeId } from '../ref-types.js';
import { scalarFromJsonSchema } from '../field-types.js';
import type { SchemaValidationError } from '../utils/schemaValidation.js';
import './EpistolaRichTextInput.js';

/** Resolve a JSON Schema property to a simple type label, including ref types. */
function resolvePropertyType(prop: JsonSchemaProperty | null | undefined): string {
  if (!prop) return 'string';
  const refType = findRefType(prop.$ref);
  if (refType !== null) return refType.id;
  const raw = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  if (raw === undefined) return 'string';
  // Collapse scalars (incl. date / date-time) via the registry; non-scalars
  // (containers, email/uri strings) keep their raw type.
  return scalarFromJsonSchema(raw, prop.format) ?? raw;
}

/** True for any field type that the registry recognises as a ref-shaped value. */
function isRefFieldType(type: string): type is RefTypeId {
  return type === 'richTextInline' || type === 'richTextBlock';
}

/**
 * Coerce a stored ISO date-time into a value `<input type="datetime-local">`
 * accepts. The control has no timezone/fractional-second support, so keep only
 * the calendar date and wall-clock time.
 */
export function toDateTimeLocal(value: JsonValue | undefined): string {
  if (typeof value !== 'string' || value === '') return '';
  // `datetime-local` needs `YYYY-MM-DDTHH:MM(:SS)`. Show the local wall-clock
  // part, dropping any zone designator — the zone is shown/edited separately via
  // the offset dropdown and re-applied on save by `combineDateTime`. A date-only
  // value is widened to midnight so it still renders; anything else unparseable
  // yields '' rather than a value the control silently rejects (which would
  // blank the picker and mask the data).
  const dateTime = value.match(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?/);
  if (dateTime) return dateTime[0];
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return `${value}T00:00`;
  return '';
}

/**
 * The zone designator of a stored date-time (`Z` / `±HH:MM`), or `''` when the
 * value carries none ("time is time"). Drives the offset dropdown's selection.
 */
export function dateTimeOffset(value: JsonValue | undefined): string {
  if (typeof value !== 'string') return '';
  return value.match(/(Z|[+-]\d{2}:\d{2})$/)?.[0] ?? '';
}

/**
 * Recombine a `datetime-local` wall-clock part with an explicit zone offset
 * (from the offset dropdown) into the stored value. Seconds are filled in so an
 * offset-bearing result is a valid RFC 3339 instant; an empty offset leaves the
 * value naive ("time is time"). `offset` is `''` (none), `'Z'`, or `'±HH:MM'`.
 */
export function combineDateTime(localPart: string, offset: string): string {
  if (localPart === '') return '';
  const withSeconds = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(localPart)
    ? `${localPart}:00`
    : localPart;
  return `${withSeconds}${offset}`;
}

/**
 * Zone-offset choices offered next to a date-time picker. `''` means "no
 * timezone" (stored naive); `Z` is UTC; the rest are whole-hour offsets across
 * the valid range. Half-hour/quarter zones are omitted (rare); a value that
 * already carries one still round-trips via [dateTimeOffset].
 */
export const DATETIME_OFFSET_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'No timezone' },
  { value: 'Z', label: 'UTC (Z)' },
  ...Array.from({ length: 27 }, (_, i) => {
    const hours = 14 - i; // +14 … -12
    if (hours === 0) return null; // Z already covers +00:00
    const sign = hours > 0 ? '+' : '-';
    const hh = String(Math.abs(hours)).padStart(2, '0');
    return { value: `${sign}${hh}:00`, label: `${sign}${hh}:00` };
  }).filter((o): o is { value: string; label: string } => o !== null),
];

// ---------------------------------------------------------------------------
// Deep value helpers
// ---------------------------------------------------------------------------

/**
 * Read a nested value from an object using a dot-separated path.
 * Supports array indices as numeric segments (e.g., "items.0.name").
 */
export function getNestedValue(obj: JsonObject, path: string): JsonValue | undefined {
  if (!path) return undefined;

  const segments = path.split('.');
  let current: unknown = obj;

  for (const segment of segments) {
    if (current === null || current === undefined) return undefined;

    if (Array.isArray(current)) {
      const index = parseInt(segment, 10);
      if (isNaN(index)) return undefined;
      current = current[index];
    } else if (typeof current === 'object') {
      current = (current as Record<string, unknown>)[segment];
    } else {
      return undefined;
    }
  }

  return current as JsonValue | undefined;
}

/**
 * Immutably set a nested value in an object using a dot-separated path.
 * Creates intermediate objects/arrays as needed.
 */
export function setNestedValue(obj: JsonObject, path: string, value: JsonValue): JsonObject {
  if (!path) return obj;

  const segments = path.split('.');
  const result = structuredClone(obj);
  let current: Record<string, unknown> = result;

  for (let i = 0; i < segments.length - 1; i++) {
    const segment = segments[i];
    const nextSegment = segments[i + 1];
    const isNextIndex = /^\d+$/.test(nextSegment);

    if (!(segment in current) || current[segment] === null || current[segment] === undefined) {
      current[segment] = isNextIndex ? [] : {};
    }

    if (Array.isArray(current[segment])) {
      // Make a shallow copy of the array to preserve immutability along the path
      current[segment] = [...(current[segment] as unknown[])];
    } else if (typeof current[segment] === 'object') {
      current[segment] = { ...(current[segment] as Record<string, unknown>) };
    }

    current = current[segment] as Record<string, unknown>;
  }

  const lastSegment = segments[segments.length - 1];
  current[lastSegment] = value;

  return result;
}

// ---------------------------------------------------------------------------
// Validation path utilities
// ---------------------------------------------------------------------------

/**
 * Convert a JSON Schema validation path (e.g., "$.users[0].email")
 * to the dot-separated form path used by the form renderer (e.g., "users.0.email").
 */
export function validationPathToFormPath(path: string): string {
  return path
    .replace(/^\$\./, '') // strip leading "$."
    .replace(/\[(\d+)\]/g, '.$1'); // convert [0] to .0
}

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
  errors: Map<string, string> = NO_ERRORS,
  readOnly = false,
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
          0,
          errors,
          readOnly,
        ),
      )}
    </div>
  `;
}

/**
 * Render a single form field based on its JSON Schema property type.
 * Uses compact inline rows: label and input side-by-side.
 */
function renderFormField(
  name: string,
  propSchema: JsonSchemaProperty,
  path: string,
  rootData: JsonObject,
  isRequired: boolean,
  onChange: (path: string, value: JsonValue) => void,
  depth: number,
  errors: Map<string, string>,
  readOnly: boolean,
): unknown {
  const type = resolvePropertyType(propSchema);
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
            <input
              type="text"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              id=${fieldId}
              .value=${String(value ?? '')}
              placeholder="${name}"
              ?disabled=${readOnly}
              aria-describedby=${fieldError ? errorId : nothing}
              @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).value)}
            />
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
            <input
              type="number"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              step="any"
              id=${fieldId}
              .value=${value != null ? String(value) : ''}
              placeholder="${name}"
              ?disabled=${readOnly}
              aria-describedby=${fieldError ? errorId : nothing}
              @change=${(e: Event) => {
                const raw = (e.target as HTMLInputElement).value;
                onChange(path, raw === '' ? null : parseFloat(raw));
              }}
            />
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
            <input
              type="number"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              step="1"
              id=${fieldId}
              .value=${value != null ? String(value) : ''}
              placeholder="${name}"
              ?disabled=${readOnly}
              aria-describedby=${fieldError ? errorId : nothing}
              @change=${(e: Event) => {
                const raw = (e.target as HTMLInputElement).value;
                onChange(path, raw === '' ? null : parseInt(raw, 10));
              }}
            />
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
            <label class="dc-tree-checkbox">
              <input
                type="checkbox"
                class="ep-checkbox"
                id=${fieldId}
                .checked=${value === true}
                ?disabled=${readOnly}
                aria-label="${name}"
                aria-describedby=${fieldError ? errorId : nothing}
                @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).checked)}
              />
            </label>
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
            <input
              type="date"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              id=${fieldId}
              .value=${String(value ?? '')}
              ?disabled=${readOnly}
              aria-describedby=${fieldError ? errorId : nothing}
              @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).value)}
            />
            ${fieldError
              ? html`<span class="dc-field-error" id=${errorId}>${fieldError}</span>`
              : nothing}
          </div>
        </div>
      `;

    case 'datetime':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <div class="dc-datetime-group">
              <input
                type="datetime-local"
                step="1"
                class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
                id=${fieldId}
                .value=${toDateTimeLocal(value)}
                ?disabled=${readOnly}
                aria-describedby=${fieldError ? errorId : nothing}
                @change=${(e: Event) =>
                  onChange(
                    path,
                    combineDateTime((e.target as HTMLInputElement).value, dateTimeOffset(value)),
                  )}
              />
              <select
                class="ep-input dc-datetime-offset"
                aria-label="Timezone"
                ?disabled=${readOnly}
                .value=${dateTimeOffset(value)}
                @change=${(e: Event) =>
                  onChange(
                    path,
                    combineDateTime(toDateTimeLocal(value), (e.target as HTMLSelectElement).value),
                  )}
              >
                ${DATETIME_OFFSET_OPTIONS.map(
                  (o) =>
                    html`<option value=${o.value} ?selected=${o.value === dateTimeOffset(value)}>
                      ${o.label}
                    </option>`,
                )}
              </select>
            </div>
            ${fieldError
              ? html`<span class="dc-field-error" id=${errorId}>${fieldError}</span>`
              : nothing}
          </div>
        </div>
      `;

    case 'richTextInline':
    case 'richTextBlock':
      return html`
        <div class="dc-tree-row dc-tree-row-rich-text">
          <span
            class="dc-tree-label"
            role="label"
            @click=${() => document.getElementById(fieldId)?.focus()}
          >
            ${name}${isRequired
              ? html`<span class="dc-required-mark" aria-hidden="true">*</span>`
              : nothing}
          </span>
          <div class="dc-tree-input-wrapper">
            <epistola-rich-text-input
              class="dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              id=${fieldId}
              .value=${value ?? null}
              ?readOnly=${readOnly}
              .placeholder=${name}
              .mode=${type === 'richTextInline' ? 'inline' : 'block'}
              @rich-text-change=${(e: CustomEvent<{ value: JsonValue }>) =>
                onChange(path, e.detail.value)}
            ></epistola-rich-text-input>
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
        depth,
        errors,
        readOnly,
      );

    case 'array':
      return renderArrayField(
        name,
        propSchema,
        path,
        rootData,
        isRequired,
        onChange,
        depth,
        errors,
        readOnly,
      );

    default:
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <input
              type="text"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              id=${fieldId}
              .value=${String(value ?? '')}
              placeholder="${name}"
              ?disabled=${readOnly}
              aria-describedby=${fieldError ? errorId : nothing}
              @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).value)}
            />
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
  depth: number,
  errors: Map<string, string>,
  readOnly: boolean,
): unknown {
  if (!propSchema.properties || Object.keys(propSchema.properties).length === 0) {
    return html`
      <div class="dc-tree-row">
        <span class="dc-tree-label">
          ${name}${isRequired
            ? html`<span class="dc-required-mark" aria-hidden="true">*</span>`
            : nothing}
        </span>
        <span class="dc-tree-hint">No properties defined</span>
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
            depth + 1,
            errors,
            readOnly,
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
  depth: number,
  errors: Map<string, string>,
  readOnly: boolean,
): unknown {
  const currentValue = getNestedValue(rootData, path);
  const items: JsonValue[] = Array.isArray(currentValue) ? currentValue : [];
  const itemSchema = propSchema.items;
  const itemType = resolvePropertyType(itemSchema);

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
      rootData,
      isRequired,
      onChange,
      addItem,
      removeItem,
      depth,
      errors,
      readOnly,
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
                    readOnly,
                  )}
                </div>
                ${itemError ? html`<span class="dc-field-error">${itemError}</span>` : nothing}
              </div>
              <button
                class="dc-array-item-remove"
                title="Remove item"
                aria-label="Remove item ${index + 1}"
                ?disabled=${readOnly}
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
        <button class="dc-array-add-btn" ?disabled=${readOnly} @click=${() => addItem()}>
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
  rootData: JsonObject,
  isRequired: boolean,
  onChange: (path: string, value: JsonValue) => void,
  addItem: () => void,
  removeItem: (index: number) => void,
  depth: number,
  errors: Map<string, string>,
  readOnly: boolean,
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
                  ?disabled=${readOnly}
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
                        depth + 1,
                        errors,
                        readOnly,
                      ),
                    )
                  : nothing}
              </div>
            </details>
          `;
        })}
        <button class="dc-array-add-btn" ?disabled=${readOnly} @click=${() => addItem()}>
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
  readOnly = false,
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
          ?disabled=${readOnly}
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
          ?disabled=${readOnly}
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
            ?disabled=${readOnly}
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
          ?disabled=${readOnly}
          aria-label="${label}"
          @change=${(e: Event) => onChange((e.target as HTMLInputElement).value)}
        />
      `;
    case 'datetime':
      return html`
        <div class="dc-datetime-group">
          <input
            type="datetime-local"
            step="1"
            class="ep-input dc-array-item-input ${errorClass}"
            .value=${toDateTimeLocal(value)}
            ?disabled=${readOnly}
            aria-label="${label}"
            @change=${(e: Event) =>
              onChange(
                combineDateTime((e.target as HTMLInputElement).value, dateTimeOffset(value)),
              )}
          />
          <select
            class="ep-input dc-datetime-offset"
            aria-label="Timezone"
            ?disabled=${readOnly}
            .value=${dateTimeOffset(value)}
            @change=${(e: Event) =>
              onChange(
                combineDateTime(toDateTimeLocal(value), (e.target as HTMLSelectElement).value),
              )}
          >
            ${DATETIME_OFFSET_OPTIONS.map(
              (o) =>
                html`<option value=${o.value} ?selected=${o.value === dateTimeOffset(value)}>
                  ${o.label}
                </option>`,
            )}
          </select>
        </div>
      `;
    case 'richTextInline':
    case 'richTextBlock':
      return html`
        <epistola-rich-text-input
          class="dc-array-item-input ${errorClass}"
          .value=${value ?? null}
          ?readOnly=${readOnly}
          .placeholder=${label}
          aria-label="${label}"
          .mode=${type === 'richTextInline' ? 'inline' : 'block'}
          @rich-text-change=${(e: CustomEvent<{ value: JsonValue }>) => onChange(e.detail.value)}
        ></epistola-rich-text-input>
      `;
    default:
      return html`
        <input
          type="text"
          class="ep-input dc-array-item-input ${errorClass}"
          .value=${String(value ?? '')}
          placeholder="${label}"
          ?disabled=${readOnly}
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
    case 'datetime':
      return '';
    case 'object': {
      if (!schema?.properties) return {};
      const obj: Record<string, JsonValue> = {};
      for (const [key, propSchema] of Object.entries(schema.properties)) {
        obj[key] = getDefaultValueForType(resolvePropertyType(propSchema), propSchema);
      }
      return obj;
    }
    case 'array':
      return [];
    default:
      // Ref-based field types deliver their default via the registry.
      if (isRefFieldType(type)) {
        return getRefTypeById(type).defaultValue();
      }
      return '';
  }
}
