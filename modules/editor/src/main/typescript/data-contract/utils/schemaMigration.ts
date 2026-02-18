import type {
  DataExample,
  JsonObject,
  JsonSchema,
  JsonSchemaProperty,
  JsonValue,
} from '../types.js'

/**
 * Type of validation issue that can be auto-migrated.
 */
export type MigrationIssueType = 'TYPE_MISMATCH' | 'MISSING_REQUIRED' | 'UNKNOWN_FIELD'

/**
 * A migration suggestion for a single field in an example.
 */
export interface MigrationSuggestion {
  exampleId: string
  exampleName: string
  path: string
  issue: MigrationIssueType
  currentValue: JsonValue
  expectedType: string
  suggestedValue: JsonValue | null
  autoMigratable: boolean
}

/**
 * Result of detecting migrations between a schema and examples.
 */
export interface MigrationDetectionResult {
  compatible: boolean
  migrations: MigrationSuggestion[]
}

/**
 * Detect migrations needed for examples to conform to a new schema.
 * This is a client-side version that complements the backend's validation.
 */
export function detectMigrations(
  schema: JsonSchema | null,
  examples: DataExample[],
): MigrationDetectionResult {
  if (!schema || examples.length === 0) {
    return { compatible: true, migrations: [] }
  }

  const migrations: MigrationSuggestion[] = []

  for (const example of examples) {
    const exampleMigrations = detectExampleMigrations(
      example.id,
      example.name,
      example.data as JsonObject,
      schema,
    )
    migrations.push(...exampleMigrations)
  }

  return {
    compatible: migrations.length === 0,
    migrations,
  }
}

/**
 * Detect migrations needed for a single example.
 */
function detectExampleMigrations(
  exampleId: string,
  exampleName: string,
  data: JsonObject,
  schema: JsonSchema,
  basePath = '$',
): MigrationSuggestion[] {
  const migrations: MigrationSuggestion[] = []

  if (schema.type !== 'object' || !schema.properties) {
    return migrations
  }

  // Check each property in the schema
  for (const [propName, propSchema] of Object.entries(schema.properties)) {
    const path = `${basePath}.${propName}`
    const value = data[propName]

    if (value === undefined) {
      // Missing field - check if required
      if (schema.required?.includes(propName)) {
        migrations.push({
          exampleId,
          exampleName,
          path,
          issue: 'MISSING_REQUIRED',
          currentValue: undefined as unknown as JsonValue,
          expectedType: propSchema.type as string,
          suggestedValue: null,
          autoMigratable: false,
        })
      }
      continue
    }

    // Check type mismatch
    const typeMigration = detectTypeMismatch(exampleId, exampleName, path, value, propSchema)
    if (typeMigration) {
      migrations.push(typeMigration)
    }

    // Recursively check nested objects
    if (
      propSchema.type === 'object' &&
      propSchema.properties &&
      typeof value === 'object' &&
      !Array.isArray(value)
    ) {
      const nested = detectExampleMigrations(
        exampleId,
        exampleName,
        value as JsonObject,
        propSchema as JsonSchema,
        path,
      )
      migrations.push(...nested)
    }

    // Check array items
    if (propSchema.type === 'array' && propSchema.items && Array.isArray(value)) {
      for (let i = 0; i < value.length; i++) {
        const itemMigration = detectTypeMismatch(
          exampleId,
          exampleName,
          `${path}[${i}]`,
          value[i],
          propSchema.items,
        )
        if (itemMigration) {
          migrations.push(itemMigration)
        }
      }
    }
  }

  return migrations
}

/**
 * Detect if a value needs type migration and suggest a fix if possible.
 */
function detectTypeMismatch(
  exampleId: string,
  exampleName: string,
  path: string,
  value: JsonValue,
  propSchema: JsonSchemaProperty,
): MigrationSuggestion | null {
  const expectedType = propSchema.type as string
  const actualType = getValueType(value)

  if (typeMatches(actualType, expectedType)) {
    return null
  }

  const { suggestedValue, autoMigratable } = tryConvertValue(value, expectedType)

  return {
    exampleId,
    exampleName,
    path,
    issue: 'TYPE_MISMATCH',
    currentValue: value,
    expectedType,
    suggestedValue,
    autoMigratable,
  }
}

/**
 * Get the JSON Schema type of a value.
 */
function getValueType(value: JsonValue): string {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'number') {
    return Number.isInteger(value) ? 'integer' : 'number'
  }
  if (typeof value === 'string') return 'string'
  if (typeof value === 'object') return 'object'
  return 'unknown'
}

/**
 * Check if actual type matches expected type.
 */
function typeMatches(actual: string, expected: string): boolean {
  if (actual === expected) return true
  if (expected === 'number' && actual === 'integer') return true
  return false
}

/**
 * Try to convert a value to the expected type.
 */
function tryConvertValue(
  value: JsonValue,
  expectedType: string,
): { suggestedValue: JsonValue | null; autoMigratable: boolean } {
  switch (expectedType) {
    case 'string':
      return tryConvertToString(value)
    case 'number':
    case 'integer':
      return tryConvertToNumber(value, expectedType)
    case 'boolean':
      return tryConvertToBoolean(value)
    default:
      return { suggestedValue: null, autoMigratable: false }
  }
}

function tryConvertToString(value: JsonValue): {
  suggestedValue: JsonValue | null
  autoMigratable: boolean
} {
  if (typeof value === 'string') {
    return { suggestedValue: value, autoMigratable: true }
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return { suggestedValue: String(value), autoMigratable: true }
  }
  // Objects/arrays can't be auto-converted to string
  return { suggestedValue: null, autoMigratable: false }
}

function tryConvertToNumber(
  value: JsonValue,
  expectedType: string,
): { suggestedValue: JsonValue | null; autoMigratable: boolean } {
  if (typeof value === 'number') {
    return { suggestedValue: value, autoMigratable: true }
  }
  if (typeof value === 'string') {
    const parsed = expectedType === 'integer' ? parseInt(value, 10) : parseFloat(value)
    if (!isNaN(parsed)) {
      return { suggestedValue: parsed, autoMigratable: true }
    }
  }
  return { suggestedValue: null, autoMigratable: false }
}

function tryConvertToBoolean(value: JsonValue): {
  suggestedValue: JsonValue | null
  autoMigratable: boolean
} {
  if (typeof value === 'boolean') {
    return { suggestedValue: value, autoMigratable: true }
  }
  if (typeof value === 'string') {
    const lower = value.toLowerCase()
    if (lower === 'true' || lower === '1' || lower === 'yes') {
      return { suggestedValue: true, autoMigratable: true }
    }
    if (lower === 'false' || lower === '0' || lower === 'no') {
      return { suggestedValue: false, autoMigratable: true }
    }
  }
  if (typeof value === 'number') {
    return { suggestedValue: value !== 0, autoMigratable: true }
  }
  return { suggestedValue: null, autoMigratable: false }
}

/**
 * Apply a migration to an example's data, returning the updated data.
 * Only applies auto-migratable migrations.
 */
export function applyMigration(data: JsonObject, migration: MigrationSuggestion): JsonObject {
  if (!migration.autoMigratable || migration.suggestedValue === null) {
    return data
  }

  // Parse path and set value
  const path = migration.path
  const segments = path
    .replace(/^\$\./, '')
    .replace(/\[(\d+)\]/g, '.$1')
    .split('.')

  // Deep clone the data
  const newData = JSON.parse(JSON.stringify(data))

  // Navigate to parent and set value
  let current: Record<string, unknown> = newData
  for (let i = 0; i < segments.length - 1; i++) {
    const segment = segments[i]
    const nextSegment = segments[i + 1]

    if (!(segment in current)) {
      // Create parent objects as needed
      current[segment] = /^\d+$/.test(nextSegment) ? [] : {}
    }
    current = current[segment] as Record<string, unknown>
  }

  const lastSegment = segments[segments.length - 1]
  current[lastSegment] = migration.suggestedValue

  return newData
}

/**
 * Apply all auto-migratable migrations to an example's data.
 */
export function applyAllMigrations(
  data: JsonObject,
  migrations: MigrationSuggestion[],
): JsonObject {
  let result = data
  for (const migration of migrations) {
    if (migration.autoMigratable) {
      result = applyMigration(result, migration)
    }
  }
  return result
}
