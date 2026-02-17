import { nanoid } from 'nanoid'
import type {
  JsonObject,
  JsonSchema,
  JsonSchemaProperty,
  JsonValue,
  SchemaField,
  SchemaFieldType,
  SchemaFieldUpdate,
  VisualSchema,
} from '../types.js'

/**
 * Convert a visual schema to JSON Schema format.
 */
export function visualSchemaToJsonSchema(visual: VisualSchema): JsonSchema {
  const properties: Record<string, JsonSchemaProperty> = {}
  const required: string[] = []

  for (const field of visual.fields) {
    properties[field.name] = fieldToJsonSchemaProperty(field)
    if (field.required) {
      required.push(field.name)
    }
  }

  return {
    $schema: 'http://json-schema.org/draft-07/schema#',
    type: 'object',
    properties,
    required: required.length > 0 ? required : undefined,
    additionalProperties: true,
  }
}

/**
 * Convert a single field to a JSON Schema property.
 */
function fieldToJsonSchemaProperty(field: SchemaField): JsonSchemaProperty {
  const prop: JsonSchemaProperty = {
    type: field.type,
  }

  if (field.description) {
    prop.description = field.description
  }

  if (field.type === 'array' && field.arrayItemType) {
    if (field.arrayItemType === 'object' && field.nestedFields) {
      // Array of objects
      const nestedRequired = field.nestedFields.filter((f) => f.required).map((f) => f.name)
      prop.items = {
        type: 'object',
        properties: nestedFieldsToProperties(field.nestedFields),
      }
      if (nestedRequired.length > 0) {
        prop.items.required = nestedRequired
      }
    } else {
      prop.items = { type: field.arrayItemType }
    }
  }

  if (field.type === 'object' && field.nestedFields) {
    prop.properties = nestedFieldsToProperties(field.nestedFields)
    const nestedRequired = field.nestedFields.filter((f) => f.required).map((f) => f.name)
    if (nestedRequired.length > 0) {
      prop.required = nestedRequired
    }
  }

  return prop
}

/**
 * Convert nested fields to JSON Schema properties.
 */
function nestedFieldsToProperties(fields: SchemaField[]): Record<string, JsonSchemaProperty> {
  const properties: Record<string, JsonSchemaProperty> = {}
  for (const field of fields) {
    properties[field.name] = fieldToJsonSchemaProperty(field)
  }
  return properties
}

/**
 * Convert a JSON Schema to a visual schema.
 *
 * Field IDs are deterministic based on their path (e.g., `field:name`,
 * `field:address.street`) so that UI state like expanded-fields survives
 * re-renders without stale random IDs.
 */
export function jsonSchemaToVisualSchema(schema: JsonSchema | JsonObject | null): VisualSchema {
  if (!schema || typeof schema !== 'object') {
    return { fields: [] }
  }

  const jsonSchema = schema as JsonSchema
  if (jsonSchema.type !== 'object' || !jsonSchema.properties) {
    return { fields: [] }
  }

  const requiredFields = new Set(jsonSchema.required || [])
  const fields: SchemaField[] = []

  for (const [name, prop] of Object.entries(jsonSchema.properties)) {
    fields.push(jsonSchemaPropertyToField(name, prop, requiredFields.has(name), name))
  }

  return { fields }
}

/**
 * Convert a JSON Schema property to a visual field.
 * Uses a deterministic `field:${path}` ID so UI state survives re-renders.
 */
function jsonSchemaPropertyToField(
  name: string,
  prop: JsonSchemaProperty,
  required: boolean,
  path: string,
): SchemaField {
  const type = Array.isArray(prop.type) ? prop.type[0] : prop.type
  const baseField = {
    id: `field:${path}`,
    name,
    required,
    description: prop.description,
  }

  if (type === 'array') {
    const itemType = prop.items
      ? Array.isArray(prop.items.type)
        ? prop.items.type[0]
        : prop.items.type
      : 'string'
    const nestedFields =
      itemType === 'object' && prop.items?.properties
        ? Object.entries(prop.items.properties).map(([n, p]) =>
            jsonSchemaPropertyToField(n, p, new Set(prop.items?.required || []).has(n), `${path}.${n}`),
          )
        : undefined
    return {
      ...baseField,
      type: 'array' as const,
      arrayItemType: itemType as SchemaFieldType,
      nestedFields,
    }
  }

  if (type === 'object') {
    const nestedRequired = new Set(prop.required || [])
    const nestedFields = prop.properties
      ? Object.entries(prop.properties).map(([n, p]) =>
          jsonSchemaPropertyToField(n, p, nestedRequired.has(n), `${path}.${n}`),
        )
      : undefined
    return {
      ...baseField,
      type: 'object' as const,
      nestedFields,
    }
  }

  // Primitive types
  return {
    ...baseField,
    type: type as 'string' | 'number' | 'integer' | 'boolean',
  }
}

/**
 * Generate a draft schema from a data example.
 * Infers types from values and defaults all fields to optional.
 */
export function generateSchemaFromData(data: JsonObject): VisualSchema {
  const fields: SchemaField[] = []

  for (const [name, value] of Object.entries(data)) {
    fields.push(inferFieldFromValue(name, value, name))
  }

  return { fields }
}

/**
 * Infer a schema field from a value.
 * Uses deterministic path-based IDs for consistency.
 */
function inferFieldFromValue(name: string, value: JsonValue, path: string): SchemaField {
  const baseField = {
    id: `field:${path}`,
    name,
    required: false,
  }
  const type = inferType(value)

  if (type === 'array' && Array.isArray(value)) {
    const firstItem = value.length > 0 ? value[0] : null
    const arrayItemType = inferType(firstItem)
    const nestedFields =
      typeof firstItem === 'object' && firstItem !== null && !Array.isArray(firstItem)
        ? Object.entries(firstItem).map(([n, v]) => inferFieldFromValue(n, v, `${path}.${n}`))
        : undefined
    return {
      ...baseField,
      type: 'array' as const,
      arrayItemType,
      nestedFields,
    }
  }

  if (type === 'object' && typeof value === 'object' && value !== null && !Array.isArray(value)) {
    return {
      ...baseField,
      type: 'object' as const,
      nestedFields: Object.entries(value).map(([n, v]) => inferFieldFromValue(n, v, `${path}.${n}`)),
    }
  }

  // Primitive types
  return {
    ...baseField,
    type: type as 'string' | 'number' | 'integer' | 'boolean',
  }
}

/**
 * Infer the JSON Schema type from a value.
 */
function inferType(value: JsonValue): SchemaFieldType {
  if (value === null) {
    return 'string' // Default null to string
  }
  if (Array.isArray(value)) {
    return 'array'
  }
  if (typeof value === 'object') {
    return 'object'
  }
  if (typeof value === 'boolean') {
    return 'boolean'
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? 'integer' : 'number'
  }
  return 'string'
}

/**
 * Create an empty field with default values.
 */
export function createEmptyField(name = 'newField'): SchemaField {
  return {
    id: nanoid(),
    name,
    type: 'string',
    required: false,
  }
}

/**
 * Apply updates to a schema field, returning a properly typed SchemaField.
 * Handles type changes by constructing the appropriate discriminated union variant.
 */
export function applyFieldUpdate(field: SchemaField, updates: SchemaFieldUpdate): SchemaField {
  const type = updates.type ?? field.type
  const baseField = {
    id: field.id,
    name: updates.name ?? field.name,
    required: updates.required ?? field.required,
    description: updates.description ?? field.description,
  }

  if (type === 'array') {
    const arrayItemType =
      updates.arrayItemType ?? (field.type === 'array' ? field.arrayItemType : 'string')
    const nestedFields =
      updates.nestedFields !== undefined
        ? updates.nestedFields
        : field.type === 'array'
          ? field.nestedFields
          : undefined
    return {
      ...baseField,
      type: 'array' as const,
      arrayItemType,
      nestedFields,
    }
  }

  if (type === 'object') {
    const nestedFields =
      updates.nestedFields !== undefined
        ? updates.nestedFields
        : field.type === 'object'
          ? field.nestedFields
          : undefined
    return {
      ...baseField,
      type: 'object' as const,
      nestedFields,
    }
  }

  // Primitive types
  return {
    ...baseField,
    type: type as 'string' | 'number' | 'integer' | 'boolean',
  }
}

/**
 * Get all paths from a schema (for expression matching).
 */
export function getSchemaFieldPaths(schema: VisualSchema): Set<string> {
  const paths = new Set<string>()

  function traverse(fields: SchemaField[], prefix = '') {
    for (const field of fields) {
      const path = prefix ? `${prefix}.${field.name}` : field.name
      paths.add(path)

      if (field.type === 'object' && field.nestedFields) {
        traverse(field.nestedFields, path)
      }

      if (field.type === 'array') {
        const arrayPath = `${path}[]`
        paths.add(arrayPath)

        if (field.arrayItemType === 'object' && field.nestedFields) {
          traverse(field.nestedFields, arrayPath)
        }
      }
    }
  }

  traverse(schema.fields)
  return paths
}

/**
 * Type display names for the UI.
 */
export const FIELD_TYPE_LABELS: Record<SchemaFieldType, string> = {
  string: 'Text',
  number: 'Number',
  integer: 'Integer',
  boolean: 'Yes/No',
  array: 'List',
  object: 'Object',
}
