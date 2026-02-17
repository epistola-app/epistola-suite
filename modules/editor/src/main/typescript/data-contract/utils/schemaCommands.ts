/**
 * Schema commands — discriminated union command types and pure tree operations.
 *
 * Commands represent atomic mutations on VisualSchema. The executeSchemaCommand
 * function is a pure reducer: (VisualSchema, SchemaCommand) → VisualSchema.
 * Tree operations (update, delete, add) work at arbitrary depth via recursion.
 */

import type { JsonObject, SchemaField, SchemaFieldUpdate, VisualSchema } from '../types.js'
import { applyFieldUpdate, createEmptyField, generateSchemaFromData } from './schemaUtils.js'

// =============================================================================
// Command types
// =============================================================================

export type SchemaCommand =
  | { type: 'addField'; parentFieldId: string | null; name?: string }
  | { type: 'deleteField'; fieldId: string }
  | { type: 'updateField'; fieldId: string; updates: SchemaFieldUpdate }
  | { type: 'generateFromExample'; data: JsonObject }

// =============================================================================
// Command executor (pure function)
// =============================================================================

/**
 * Execute a schema command against a VisualSchema, returning a new VisualSchema.
 * The input schema is NOT mutated.
 */
export function executeSchemaCommand(schema: VisualSchema, command: SchemaCommand): VisualSchema {
  switch (command.type) {
    case 'addField': {
      const newField = createEmptyField(command.name ?? `field${countAllFields(schema.fields) + 1}`)
      return { fields: addFieldToTree(schema.fields, command.parentFieldId, newField) }
    }
    case 'deleteField':
      return { fields: deleteFieldFromTree(schema.fields, command.fieldId) }
    case 'updateField':
      return { fields: updateFieldInTree(schema.fields, command.fieldId, command.updates) }
    case 'generateFromExample':
      return generateSchemaFromData(command.data)
  }
}

// =============================================================================
// Tree operations (pure, recursive)
// =============================================================================

/**
 * Find and update a field anywhere in the tree by ID.
 * Returns a new array — does not mutate the input.
 */
export function updateFieldInTree(
  fields: SchemaField[],
  fieldId: string,
  updates: SchemaFieldUpdate,
): SchemaField[] {
  return fields.map((field) => {
    if (field.id === fieldId) {
      return applyFieldUpdate(field, updates)
    }
    // Recurse into nested fields
    const nested = getNestedFields(field)
    if (nested && nested.length > 0) {
      const updatedNested = updateFieldInTree(nested, fieldId, updates)
      if (updatedNested !== nested) {
        return applyFieldUpdate(field, { nestedFields: updatedNested })
      }
    }
    return field
  })
}

/**
 * Find and remove a field anywhere in the tree by ID.
 * Returns a new array — does not mutate the input.
 */
export function deleteFieldFromTree(fields: SchemaField[], fieldId: string): SchemaField[] {
  let changed = false
  const result: SchemaField[] = []
  for (const field of fields) {
    if (field.id === fieldId) {
      changed = true
      continue // skip this field (delete it)
    }
    const nested = getNestedFields(field)
    if (nested && nested.length > 0) {
      const updatedNested = deleteFieldFromTree(nested, fieldId)
      if (updatedNested !== nested) {
        result.push(applyFieldUpdate(field, { nestedFields: updatedNested }))
        changed = true
        continue
      }
    }
    result.push(field)
  }
  return changed ? result : fields
}

/**
 * Add a child field to a parent (null = root level).
 * Returns a new array — does not mutate the input.
 */
export function addFieldToTree(
  fields: SchemaField[],
  parentFieldId: string | null,
  newField: SchemaField,
): SchemaField[] {
  if (parentFieldId === null) {
    return [...fields, newField]
  }

  return fields.map((field) => {
    if (field.id === parentFieldId) {
      const nested = getNestedFields(field) ?? []
      return applyFieldUpdate(field, { nestedFields: [...nested, newField] })
    }
    // Recurse into nested fields
    const nested = getNestedFields(field)
    if (nested && nested.length > 0) {
      const updatedNested = addFieldToTree(nested, parentFieldId, newField)
      if (updatedNested !== nested) {
        return applyFieldUpdate(field, { nestedFields: updatedNested })
      }
    }
    return field
  })
}

// =============================================================================
// Helpers
// =============================================================================

function getNestedFields(field: SchemaField): SchemaField[] | undefined {
  if (field.type === 'object') return field.nestedFields
  if (field.type === 'array') return field.nestedFields
  return undefined
}

function countAllFields(fields: SchemaField[]): number {
  let count = 0
  for (const field of fields) {
    count++
    const nested = getNestedFields(field)
    if (nested) {
      count += countAllFields(nested)
    }
  }
  return count
}
