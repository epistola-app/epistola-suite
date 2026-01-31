import type {JsonObject, JsonValue} from "../types/template";
import type {JsonSchema, JsonSchemaProperty} from "../types/schema";

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

  const jsonSchema = schema as JsonSchema;
  if (jsonSchema.type !== "object") {
    return { valid: true, errors: [] };
  }

  // Validate required fields
  if (jsonSchema.required) {
    for (const field of jsonSchema.required) {
      if (!(field in data) || data[field] === undefined) {
        errors.push({
          path: `$.${field}`,
          message: `is required`,
        });
      }
    }
  }

  // Validate properties
  if (jsonSchema.properties) {
    for (const [name, propSchema] of Object.entries(jsonSchema.properties)) {
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
 * Validate a single property against its schema.
 */
function validateProperty(
  value: JsonValue,
  schema: JsonSchemaProperty,
  path: string,
): SchemaValidationError[] {
  const errors: SchemaValidationError[] = [];

  if (value === null || value === undefined) {
    // Null/undefined values are allowed (required check is separate)
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
      message: `must be ${expectedTypes.join(" or ")}, got ${actualType}`,
    });
    return errors; // Don't continue validating if type is wrong
  }

  // Use the first matching type for further validation
  const expectedType = expectedTypes.find((t) => typeMatches(actualType, t)) || expectedTypes[0];

  // Validate array items
  if (expectedType === "array" && Array.isArray(value) && schema.items) {
    for (let i = 0; i < value.length; i++) {
      const itemErrors = validateProperty(value[i], schema.items, `${path}[${i}]`);
      errors.push(...itemErrors);
    }
  }

  // Validate object properties
  if (expectedType === "object" && typeof value === "object" && !Array.isArray(value)) {
    const objValue = value as JsonObject;

    // Check required nested fields
    if (schema.required) {
      for (const field of schema.required) {
        if (!(field in objValue) || objValue[field] === undefined) {
          errors.push({
            path: `${path}.${field}`,
            message: `is required`,
          });
        }
      }
    }

    // Validate nested properties
    if (schema.properties) {
      for (const [name, propSchema] of Object.entries(schema.properties)) {
        if (name in objValue) {
          const propErrors = validateProperty(objValue[name], propSchema, `${path}.${name}`);
          errors.push(...propErrors);
        }
      }
    }
  }

  return errors;
}

/**
 * Get the JSON Schema type of a value.
 */
function getValueType(value: JsonValue): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (typeof value === "boolean") return "boolean";
  if (typeof value === "number") {
    return Number.isInteger(value) ? "integer" : "number";
  }
  if (typeof value === "string") return "string";
  if (typeof value === "object") return "object";
  return "unknown";
}

/**
 * Check if actual type matches expected type.
 * Allows integer to match number.
 */
function typeMatches(actual: string, expected: string): boolean {
  if (actual === expected) return true;
  // Integer is also a valid number
  if (expected === "number" && actual === "integer") return true;
  return false;
}

/**
 * Format validation errors for display.
 */
export function formatValidationErrors(errors: SchemaValidationError[]): string[] {
  return errors.map((e) => `${e.path}: ${e.message}`);
}
