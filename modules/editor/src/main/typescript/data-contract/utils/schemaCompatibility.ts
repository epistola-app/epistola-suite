/**
 * Schema Compatibility Checker — determines whether a JSON Schema can be
 * fully represented by the visual editor.
 *
 * The visual editor supports a narrow subset of JSON Schema:
 * - type: string, number, integer, boolean, object, array (single type only)
 * - format: "date" (on strings only)
 * - properties, required, items, description, additionalProperties, $schema
 *
 * Any features outside this subset make the schema "incompatible" — the
 * visual editor is disabled and a read-only JSON view is shown instead.
 */

// =============================================================================
// Types
// =============================================================================

export interface CompatibilityIssue {
  /** JSON path to the issue, e.g. "$.properties.name" */
  path: string;
  /** The unsupported feature keyword, e.g. "enum", "allOf" */
  feature: string;
  /** Human-readable explanation */
  description: string;
}

export interface CompatibilityResult {
  compatible: boolean;
  issues: CompatibilityIssue[];
}

// =============================================================================
// Supported keys per context
// =============================================================================

/** Keys allowed at the root level of the schema */
const SUPPORTED_ROOT_KEYS = new Set([
  '$schema',
  'type',
  'properties',
  'required',
  'additionalProperties',
]);

/** Keys allowed on a property (or items) definition */
const SUPPORTED_PROPERTY_KEYS = new Set([
  'type',
  'format',
  'description',
  'properties',
  'required',
  'items',
  'additionalProperties',
]);

/** Supported single-value types */
const SUPPORTED_TYPES = new Set(['string', 'number', 'integer', 'boolean', 'object', 'array']);

// =============================================================================
// Public API
// =============================================================================

/**
 * Check whether a JSON Schema can be fully represented by the visual editor.
 * Returns all incompatible features found.
 */
export function checkSchemaCompatibility(schema: unknown): CompatibilityResult {
  const issues: CompatibilityIssue[] = [];

  if (typeof schema !== 'object' || schema === null || Array.isArray(schema)) {
    issues.push({
      path: '$',
      feature: 'invalid-schema',
      description: 'Schema must be a JSON object',
    });
    return { compatible: false, issues };
  }

  const root = schema as Record<string, unknown>;

  // Root type must be "object"
  if (root.type !== 'object') {
    issues.push({
      path: '$.type',
      feature: 'non-object-root',
      description: `Root type must be "object", found "${String(root.type)}"`,
    });
  }

  // Check for unsupported root-level keys
  for (const key of Object.keys(root)) {
    if (!SUPPORTED_ROOT_KEYS.has(key)) {
      issues.push({
        path: `$.${key}`,
        feature: key,
        description: describeUnsupportedKey(key),
      });
    }
  }

  // Check additionalProperties value
  if ('additionalProperties' in root && root.additionalProperties === false) {
    issues.push({
      path: '$.additionalProperties',
      feature: 'additionalProperties-false',
      description: '"additionalProperties: false" is not supported',
    });
  }

  // Recurse into properties
  if (root.properties && typeof root.properties === 'object') {
    checkProperties(root.properties as Record<string, unknown>, '$.properties', issues);
  }

  return { compatible: issues.length === 0, issues };
}

// =============================================================================
// Internal helpers
// =============================================================================

function checkProperties(
  properties: Record<string, unknown>,
  basePath: string,
  issues: CompatibilityIssue[],
): void {
  for (const [name, prop] of Object.entries(properties)) {
    if (typeof prop !== 'object' || prop === null || Array.isArray(prop)) continue;
    checkProperty(prop as Record<string, unknown>, `${basePath}.${name}`, issues);
  }
}

function checkProperty(
  prop: Record<string, unknown>,
  path: string,
  issues: CompatibilityIssue[],
): void {
  // Check for unsupported keys
  for (const key of Object.keys(prop)) {
    if (!SUPPORTED_PROPERTY_KEYS.has(key)) {
      issues.push({
        path: `${path}.${key}`,
        feature: key,
        description: describeUnsupportedKey(key),
      });
    }
  }

  // Check type
  if ('type' in prop) {
    if (Array.isArray(prop.type)) {
      issues.push({
        path: `${path}.type`,
        feature: 'type-union',
        description: 'Type unions (array of types) are not supported',
      });
    } else if (typeof prop.type === 'string' && !SUPPORTED_TYPES.has(prop.type)) {
      issues.push({
        path: `${path}.type`,
        feature: `type-${prop.type}`,
        description: `Type "${prop.type}" is not supported`,
      });
    }
  }

  // Check format (only "date" on strings is supported)
  if ('format' in prop && prop.format !== undefined) {
    const type = Array.isArray(prop.type) ? prop.type[0] : prop.type;
    if (prop.format !== 'date' || type !== 'string') {
      issues.push({
        path: `${path}.format`,
        feature: `format-${String(prop.format)}`,
        description: `Format "${String(prop.format)}" is not supported (only "date" on string type)`,
      });
    }
  }

  // Check additionalProperties value
  if ('additionalProperties' in prop && prop.additionalProperties === false) {
    issues.push({
      path: `${path}.additionalProperties`,
      feature: 'additionalProperties-false',
      description: '"additionalProperties: false" is not supported',
    });
  }

  // Recurse into nested properties (for object type)
  if (prop.properties && typeof prop.properties === 'object') {
    checkProperties(prop.properties as Record<string, unknown>, `${path}.properties`, issues);
  }

  // Recurse into items (for array type)
  if (prop.items && typeof prop.items === 'object' && !Array.isArray(prop.items)) {
    checkProperty(prop.items as Record<string, unknown>, `${path}.items`, issues);
  }

  // Array-form items (tuple validation) is not supported
  if (prop.items && Array.isArray(prop.items)) {
    issues.push({
      path: `${path}.items`,
      feature: 'tuple-items',
      description: 'Tuple validation (array-form items) is not supported',
    });
  }
}

/** Map well-known unsupported keys to human-readable descriptions. */
function describeUnsupportedKey(key: string): string {
  const descriptions: Record<string, string> = {
    enum: '"enum" constraints are not supported',
    const: '"const" constraints are not supported',
    default: '"default" values are not supported',
    $ref: 'Schema references ($ref) are not supported',
    $defs: 'Schema definitions ($defs) are not supported',
    definitions: 'Schema definitions are not supported',
    allOf: '"allOf" composition is not supported',
    anyOf: '"anyOf" composition is not supported',
    oneOf: '"oneOf" composition is not supported',
    not: '"not" composition is not supported',
    if: 'Conditional schemas (if/then/else) are not supported',
    then: 'Conditional schemas (if/then/else) are not supported',
    else: 'Conditional schemas (if/then/else) are not supported',
    pattern: '"pattern" validation is not supported',
    minLength: '"minLength" validation is not supported',
    maxLength: '"maxLength" validation is not supported',
    minimum: '"minimum" validation is not supported',
    maximum: '"maximum" validation is not supported',
    exclusiveMinimum: '"exclusiveMinimum" validation is not supported',
    exclusiveMaximum: '"exclusiveMaximum" validation is not supported',
    multipleOf: '"multipleOf" validation is not supported',
    minItems: '"minItems" validation is not supported',
    maxItems: '"maxItems" validation is not supported',
    uniqueItems: '"uniqueItems" validation is not supported',
    minProperties: '"minProperties" validation is not supported',
    maxProperties: '"maxProperties" validation is not supported',
    patternProperties: '"patternProperties" is not supported',
    title: '"title" is not supported (use "description" instead)',
    examples: '"examples" in schema are not supported',
    readOnly: '"readOnly" is not supported',
    writeOnly: '"writeOnly" is not supported',
    deprecated: '"deprecated" is not supported',
    contentMediaType: '"contentMediaType" is not supported',
    contentEncoding: '"contentEncoding" is not supported',
  };
  return descriptions[key] ?? `"${key}" is not supported`;
}
