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

import { html, nothing } from 'lit'
import type { JsonObject, JsonSchema, JsonSchemaProperty, JsonValue } from '../types.js'
import type { SchemaValidationError } from '../utils/schemaValidation.js'

// ---------------------------------------------------------------------------
// Deep value helpers
// ---------------------------------------------------------------------------

/**
 * Read a nested value from an object using a dot-separated path.
 * Supports array indices as numeric segments (e.g., "items.0.name").
 */
export function getNestedValue(obj: JsonObject, path: string): JsonValue | undefined {
  if (!path) return undefined

  const segments = path.split('.')
  let current: unknown = obj

  for (const segment of segments) {
    if (current === null || current === undefined) return undefined

    if (Array.isArray(current)) {
      const index = parseInt(segment, 10)
      if (isNaN(index)) return undefined
      current = current[index]
    } else if (typeof current === 'object') {
      current = (current as Record<string, unknown>)[segment]
    } else {
      return undefined
    }
  }

  return current as JsonValue | undefined
}

/**
 * Immutably set a nested value in an object using a dot-separated path.
 * Creates intermediate objects/arrays as needed.
 */
export function setNestedValue(obj: JsonObject, path: string, value: JsonValue): JsonObject {
  if (!path) return obj

  const segments = path.split('.')
  const result = structuredClone(obj)
  let current: Record<string, unknown> = result

  for (let i = 0; i < segments.length - 1; i++) {
    const segment = segments[i]
    const nextSegment = segments[i + 1]
    const isNextIndex = /^\d+$/.test(nextSegment)

    if (!(segment in current) || current[segment] === null || current[segment] === undefined) {
      current[segment] = isNextIndex ? [] : {}
    }

    if (Array.isArray(current[segment])) {
      // Make a shallow copy of the array to preserve immutability along the path
      current[segment] = [...(current[segment] as unknown[])]
    } else if (typeof current[segment] === 'object') {
      current[segment] = { ...(current[segment] as Record<string, unknown>) }
    }

    current = current[segment] as Record<string, unknown>
  }

  const lastSegment = segments[segments.length - 1]
  current[lastSegment] = value

  return result
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
    .replace(/^\$\./, '')           // strip leading "$."
    .replace(/\[(\d+)\]/g, '.$1')  // convert [0] to .0
}

/**
 * Build a Map<formPath, errorMessage> from validation errors.
 * Used to drive inline error indicators on form fields.
 */
export function buildFieldErrorMap(errors: SchemaValidationError[]): Map<string, string> {
  const map = new Map<string, string>()
  for (const err of errors) {
    const formPath = validationPathToFormPath(err.path)
    // Keep the first error per path (most relevant)
    if (!map.has(formPath)) {
      map.set(formPath, err.message)
    }
  }
  return map
}

/**
 * Check if any error path starts with the given prefix.
 * Used to show red dots on collapsed groups containing errors.
 */
export function hasChildErrors(parentPath: string, errors: Map<string, string>): boolean {
  const prefix = parentPath + '.'
  for (const key of errors.keys()) {
    if (key === parentPath || key.startsWith(prefix)) return true
  }
  return false
}

// ---------------------------------------------------------------------------
// Render helpers
// ---------------------------------------------------------------------------

const NO_ERRORS: Map<string, string> = new Map()

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
): unknown {
  if (!schema || !schema.properties || Object.keys(schema.properties).length === 0) {
    return html`
      <div class="dc-form-empty">
        Define a schema first to create examples.
      </div>
    `
  }

  const requiredSet = new Set(schema.required ?? [])

  return html`
    <div class="dc-tree">
      ${Object.entries(schema.properties).map(([name, propSchema]) =>
        renderFormField(name, propSchema, name, data, requiredSet.has(name), onChange, 0, errors),
      )}
    </div>
  `
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
): unknown {
  const type = Array.isArray(propSchema.type) ? propSchema.type[0] : propSchema.type
  const value = getNestedValue(rootData, path)
  const fieldError = errors.get(path)

  const label = html`
    <label class="dc-tree-label">
      ${name}${isRequired ? html`<span class="dc-required-mark">*</span>` : nothing}
    </label>
  `

  switch (type) {
    case 'string':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <input
              type="text"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              .value=${String(value ?? '')}
              placeholder="${name}"
              @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).value)}
            />
            ${fieldError ? html`<span class="dc-field-error">${fieldError}</span>` : nothing}
          </div>
        </div>
      `

    case 'number':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <input
              type="number"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              step="any"
              .value=${value != null ? String(value) : ''}
              placeholder="${name}"
              @change=${(e: Event) => {
                const raw = (e.target as HTMLInputElement).value
                onChange(path, raw === '' ? null : parseFloat(raw))
              }}
            />
            ${fieldError ? html`<span class="dc-field-error">${fieldError}</span>` : nothing}
          </div>
        </div>
      `

    case 'integer':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <input
              type="number"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              step="1"
              .value=${value != null ? String(value) : ''}
              placeholder="${name}"
              @change=${(e: Event) => {
                const raw = (e.target as HTMLInputElement).value
                onChange(path, raw === '' ? null : parseInt(raw, 10))
              }}
            />
            ${fieldError ? html`<span class="dc-field-error">${fieldError}</span>` : nothing}
          </div>
        </div>
      `

    case 'boolean':
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <label class="dc-tree-checkbox">
              <input
                type="checkbox"
                class="ep-checkbox"
                .checked=${value === true}
                @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).checked)}
              />
            </label>
            ${fieldError ? html`<span class="dc-field-error">${fieldError}</span>` : nothing}
          </div>
        </div>
      `

    case 'object':
      return renderObjectField(name, propSchema, path, rootData, isRequired, onChange, depth, errors)

    case 'array':
      return renderArrayField(name, propSchema, path, rootData, isRequired, onChange, depth, errors)

    default:
      return html`
        <div class="dc-tree-row">
          ${label}
          <div class="dc-tree-input-wrapper">
            <input
              type="text"
              class="ep-input dc-tree-input ${fieldError ? 'dc-input-error' : ''}"
              .value=${String(value ?? '')}
              placeholder="${name}"
              @change=${(e: Event) => onChange(path, (e.target as HTMLInputElement).value)}
            />
            ${fieldError ? html`<span class="dc-field-error">${fieldError}</span>` : nothing}
          </div>
        </div>
      `
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
): unknown {
  if (!propSchema.properties || Object.keys(propSchema.properties).length === 0) {
    return html`
      <div class="dc-tree-row">
        <label class="dc-tree-label">
          ${name}${isRequired ? html`<span class="dc-required-mark">*</span>` : nothing}
        </label>
        <span class="dc-tree-hint">No properties defined</span>
      </div>
    `
  }

  const nestedRequired = new Set(propSchema.required ?? [])
  const groupHasErrors = hasChildErrors(path, errors)

  return html`
    <details class="dc-tree-group ${groupHasErrors ? 'dc-tree-group-has-errors' : ''}" ?open=${depth < 1}>
      <summary class="dc-tree-group-header">
        ${name}${isRequired ? html`<span class="dc-required-mark">*</span>` : nothing}
        ${groupHasErrors ? html`<span class="dc-tree-group-error-dot"></span>` : nothing}
        <span class="dc-tree-type-badge">Object</span>
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
          ),
        )}
      </div>
    </details>
  `
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
): unknown {
  const currentValue = getNestedValue(rootData, path)
  const items: JsonValue[] = Array.isArray(currentValue) ? currentValue : []
  const itemSchema = propSchema.items
  const itemType = itemSchema ? (Array.isArray(itemSchema.type) ? itemSchema.type[0] : itemSchema.type) : 'string'

  const addItem = () => {
    const defaultValue = getDefaultValueForType(itemType, itemSchema)
    const newItems = [...items, defaultValue]
    onChange(path, newItems)
  }

  const removeItem = (index: number) => {
    const newItems = items.filter((_, i) => i !== index)
    onChange(path, newItems)
  }

  if (itemType === 'object' && itemSchema?.properties) {
    return renderArrayOfObjects(name, itemSchema, path, items, rootData, isRequired, onChange, addItem, removeItem, depth, errors)
  }

  const groupHasErrors = hasChildErrors(path, errors)

  // Array of primitives
  return html`
    <details class="dc-tree-group ${groupHasErrors ? 'dc-tree-group-has-errors' : ''}" ?open=${depth < 1}>
      <summary class="dc-tree-group-header">
        ${name}${isRequired ? html`<span class="dc-required-mark">*</span>` : nothing}
        ${groupHasErrors ? html`<span class="dc-tree-group-error-dot"></span>` : nothing}
        <span class="dc-tree-type-badge">List&lt;${itemType}&gt; (${items.length})</span>
      </summary>
      <div class="dc-tree-group-body">
        ${items.map((item, index) => {
          const itemPath = `${path}.${index}`
          const itemError = errors.get(itemPath)
          return html`
            <div class="dc-tree-row">
              <label class="dc-tree-label">[${index}]</label>
              <div class="dc-tree-array-item">
                <div class="dc-tree-input-wrapper" style="flex:1;min-width:0">
                  ${renderPrimitiveInput(
                    itemType,
                    item,
                    `${name}[${index}]`,
                    (newValue) => {
                      const newItems = [...items]
                      newItems[index] = newValue
                      onChange(path, newItems)
                    },
                    itemError,
                  )}
                  ${itemError ? html`<span class="dc-field-error">${itemError}</span>` : nothing}
                </div>
                <button
                  class="dc-array-item-remove"
                  title="Remove item"
                  aria-label="Remove item"
                  @click=${() => removeItem(index)}
                >\u00D7</button>
              </div>
            </div>
          `
        })}
        <button
          class="ep-btn-outline btn-sm dc-tree-add-btn"
          @click=${() => addItem()}
        >+ Add</button>
      </div>
    </details>
  `
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
): unknown {
  const nestedRequired = new Set(itemSchema.required ?? [])
  const groupHasErrors = hasChildErrors(path, errors)

  return html`
    <details class="dc-tree-group ${groupHasErrors ? 'dc-tree-group-has-errors' : ''}" ?open=${depth < 1}>
      <summary class="dc-tree-group-header">
        ${name}${isRequired ? html`<span class="dc-required-mark">*</span>` : nothing}
        ${groupHasErrors ? html`<span class="dc-tree-group-error-dot"></span>` : nothing}
        <span class="dc-tree-type-badge">List&lt;Object&gt; (${items.length})</span>
      </summary>
      <div class="dc-tree-group-body">
        ${items.map((_item, index) => {
          const itemPath = `${path}.${index}`
          const itemHasErrors = hasChildErrors(itemPath, errors)
          return html`
            <details class="dc-tree-group dc-tree-group-item ${itemHasErrors ? 'dc-tree-group-has-errors' : ''}" open>
              <summary class="dc-tree-group-header dc-tree-group-header-item">
                <span>#${index + 1}</span>
                ${itemHasErrors ? html`<span class="dc-tree-group-error-dot"></span>` : nothing}
                <button
                  class="dc-array-item-remove"
                  title="Remove item"
                  aria-label="Remove item #${index + 1}"
                  @click=${(e: Event) => { e.preventDefault(); removeItem(index) }}
                >\u00D7</button>
              </summary>
              <div class="dc-tree-group-body">
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
                      ),
                    )
                  : nothing
                }
              </div>
            </details>
          `
        })}
        <button
          class="ep-btn-outline btn-sm dc-tree-add-btn"
          @click=${() => addItem()}
        >+ Add</button>
      </div>
    </details>
  `
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
  const errorClass = error ? 'dc-input-error' : ''

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
            const raw = (e.target as HTMLInputElement).value
            onChange(raw === '' ? null : parseFloat(raw))
          }}
        />
      `
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
            const raw = (e.target as HTMLInputElement).value
            onChange(raw === '' ? null : parseInt(raw, 10))
          }}
        />
      `
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
      `
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
      `
  }
}

/**
 * Get a sensible default value for a given JSON Schema type.
 */
function getDefaultValueForType(type: string, schema?: JsonSchemaProperty | null): JsonValue {
  switch (type) {
    case 'string':
      return ''
    case 'number':
    case 'integer':
      return 0
    case 'boolean':
      return false
    case 'object': {
      if (!schema?.properties) return {}
      const obj: Record<string, JsonValue> = {}
      for (const [key, propSchema] of Object.entries(schema.properties)) {
        const propType = Array.isArray(propSchema.type) ? propSchema.type[0] : propSchema.type
        obj[key] = getDefaultValueForType(propType, propSchema)
      }
      return obj
    }
    case 'array':
      return []
    default:
      return ''
  }
}
