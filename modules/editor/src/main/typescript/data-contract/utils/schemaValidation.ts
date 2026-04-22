import type { JsonObject, JsonSchema, JsonSchemaProperty, JsonValue } from '../types.js';

/** ISO date pattern: YYYY-MM-DD */
const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/** ISO date-time pattern: YYYY-MM-DDThh:mm:ss with optional fractional seconds and timezone */
const ISO_DATETIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?$/;

/** URI pattern: scheme://... */
const URI_RE = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/.+$/;

/**
 * Validation error for a specific path.
 */
export interface SchemaValidationError {
  path: string;
  message: string;
}

/**
 * Result of schema validation.
 */
export interface SchemaValidationResult {
  valid: boolean;
  errors: SchemaValidationError[];
}

function isJsonSchemaObject(schema: JsonSchema | JsonObject): schema is JsonSchema {
  return (
    typeof schema === 'object' && schema !== null && 'type' in schema && schema.type === 'object'
  );
}

/**
 * Check if a value is effectively empty (missing, null, or empty string).
 * Used by the required field check - these values mean "not provided" in the form.
 */
function isEmptyValue(value: JsonValue | null): boolean {
  return value === null || value === '';
}

/**
 * Get the JSON Schema type of a value.
 */
function getValueType(value: JsonValue): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'number') {
    return Number.isInteger(value) ? 'integer' : 'number';
  }
  if (typeof value === 'string') return 'string';
  if (typeof value === 'object') return 'object';
  return 'unknown';
}

/**
 * Check if actual type matches expected type.
 * Allows integer to match number.
 */
function typeMatches(actual: string, expected: string): boolean {
  if (actual === expected) return true;
  if (expected === 'number' && actual === 'integer') return true;
  return false;
}

function validateStringFormats(
  value: string,
  schema: JsonSchemaProperty,
  path: string,
  errors: SchemaValidationError[],
): void {
  if (!schema.format) return;

  if (schema.format === 'date' && !ISO_DATE_RE.test(value)) {
    errors.push({ path, message: 'must be a valid date (YYYY-MM-DD)' });
  }
  if (schema.format === 'date-time' && !ISO_DATETIME_RE.test(value)) {
    errors.push({ path, message: 'must be a valid date-time (ISO 8601)' });
  }
  if (schema.format === 'uri' && !URI_RE.test(value)) {
    errors.push({ path, message: 'must be a valid URI' });
  }
}

function validateArrayItems(
  value: JsonValue,
  schema: JsonSchemaProperty,
  path: string,
  errors: SchemaValidationError[],
  validateNested: (
    value: JsonValue,
    schema: JsonSchemaProperty,
    path: string,
  ) => SchemaValidationError[],
): void {
  if (!Array.isArray(value) || !schema.items) {
    return;
  }

  for (const [index, item] of value.entries()) {
    const itemErrors = validateNested(item, schema.items, `${path}[${index}]`);
    errors.push(...itemErrors);
  }
}

function validateObjectValue(
  value: JsonValue,
  schema: JsonSchemaProperty,
  path: string,
  errors: SchemaValidationError[],
  validateNested: (
    value: JsonValue,
    schema: JsonSchemaProperty,
    path: string,
  ) => SchemaValidationError[],
): void {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return;
  }

  if (schema.required) {
    for (const field of schema.required) {
      const nestedValue = field in value ? value[field] : null;
      if (!(field in value) || isEmptyValue(nestedValue)) {
        errors.push({
          path: `${path}.${field}`,
          message: `is required`,
        });
      }
    }
  }

  if (!schema.properties) {
    return;
  }

  for (const [name, propSchema] of Object.entries(schema.properties)) {
    if (!(name in value)) {
      continue;
    }
    const propErrors = validateNested(value[name], propSchema, `${path}.${name}`);
    errors.push(...propErrors);
  }
}

/**
 * Validate a single property against its schema.
 */
function validateProperty(
  value: JsonValue,
  schema: JsonSchemaProperty,
  path: string,
): SchemaValidationError[] {
  const errors: SchemaValidationError[] = [];

  if (value === null) {
    // Null skips type validation - required check handles these separately
    return errors;
  }

  const expectedTypes = Array.isArray(schema.type) ? schema.type : [schema.type];

  // Type checking
  const actualType = getValueType(value);
  const matchesAnyType = expectedTypes.some((expectedType) =>
    typeMatches(actualType, expectedType),
  );
  if (!matchesAnyType) {
    errors.push({
      path,
      message: `must be ${expectedTypes.join(' or ')}, got ${actualType}`,
    });
    return errors; // Don't continue validating if type is wrong
  }

  // Use the first matching type for further validation
  const expectedType = expectedTypes.find((t) => typeMatches(actualType, t)) || expectedTypes[0];

  // Validate string formats
  if (expectedType === 'string' && typeof value === 'string') {
    validateStringFormats(value, schema, path, errors);
  }

  if (expectedType === 'array') {
    validateArrayItems(value, schema, path, errors, validateProperty);
  }

  if (expectedType === 'object') {
    validateObjectValue(value, schema, path, errors, validateProperty);
  }

  return errors;
}

/**
 * Validate data against a JSON Schema.
 * This is a simplified validator that handles the subset of JSON Schema
 * supported by the visual editor.
 */
export function validateDataAgainstSchema(
  data: JsonObject,
  schema: JsonSchema | JsonObject | null,
): SchemaValidationResult {
  const errors: SchemaValidationError[] = [];

  if (!schema) {
    return { valid: true, errors: [] };
  }

  if (!isJsonSchemaObject(schema)) {
    return { valid: true, errors: [] };
  }

  if (schema.required) {
    for (const field of schema.required) {
      const value = field in data ? data[field] : null;
      if (!(field in data) || isEmptyValue(value)) {
        errors.push({
          path: `$.${field}`,
          message: `is required`,
        });
      }
    }
  }

  if (schema.properties) {
    for (const [name, propSchema] of Object.entries(schema.properties)) {
      if (name in data) {
        const propErrors = validateProperty(data[name], propSchema, `$.${name}`);
        errors.push(...propErrors);
      }
    }
  }

  return {
    valid: errors.length === 0,
    errors,
  };
}

/**
 * Format validation errors for display.
 */
export function formatValidationErrors(errors: SchemaValidationError[]): string[] {
  return errors.map((e) => `${e.path}: ${e.message}`);
}
