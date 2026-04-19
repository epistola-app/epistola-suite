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

/* oxlint-disable eslint/no-use-before-define */

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
  'minimum',
  'maximum',
  'minItems',
]);

/** Supported single-value types */
const SUPPORTED_TYPES = new Set(['string', 'number', 'integer', 'boolean', 'object', 'array']);

const UNSUPPORTED_KEY_DESCRIPTIONS: readonly [string, string][] = [
  ['enum', '"enum" constraints are not supported'],
  ['const', '"const" constraints are not supported'],
  ['default', '"default" values are not supported'],
  ['$ref', 'Schema references ($ref) are not supported'],
  ['$defs', 'Schema definitions ($defs) are not supported'],
  ['definitions', 'Schema definitions are not supported'],
  ['allOf', '"allOf" composition is not supported'],
  ['anyOf', '"anyOf" composition is not supported'],
  ['oneOf', '"oneOf" composition is not supported'],
  ['not', '"not" composition is not supported'],
  ['if', 'Conditional schemas (if/then/else) are not supported'],
  ['then', 'Conditional schemas (if/then/else) are not supported'],
  ['else', 'Conditional schemas (if/then/else) are not supported'],
  ['pattern', '"pattern" validation is not supported'],
  ['minLength', '"minLength" validation is not supported'],
  ['maxLength', '"maxLength" validation is not supported'],
  ['minimum', '"minimum" validation is not supported'],
  ['maximum', '"maximum" validation is not supported'],
  ['exclusiveMinimum', '"exclusiveMinimum" validation is not supported'],
  ['exclusiveMaximum', '"exclusiveMaximum" validation is not supported'],
  ['multipleOf', '"multipleOf" validation is not supported'],
  ['minItems', '"minItems" validation is not supported'],
  ['maxItems', '"maxItems" validation is not supported'],
  ['uniqueItems', '"uniqueItems" validation is not supported'],
  ['minProperties', '"minProperties" validation is not supported'],
  ['maxProperties', '"maxProperties" validation is not supported'],
  ['patternProperties', '"patternProperties" is not supported'],
  ['title', '"title" is not supported (use "description" instead)'],
  ['examples', '"examples" in schema are not supported'],
  ['readOnly', '"readOnly" is not supported'],
  ['writeOnly', '"writeOnly" is not supported'],
  ['deprecated', '"deprecated" is not supported'],
  ['contentMediaType', '"contentMediaType" is not supported'],
  ['contentEncoding', '"contentEncoding" is not supported'],
];

// =============================================================================
// Public API
// =============================================================================

/**
 * Check whether a JSON Schema can be fully represented by the visual editor.
 * Returns all incompatible features found.
 */
export function checkSchemaCompatibility(schema: unknown): CompatibilityResult {
  const issues: CompatibilityIssue[] = [];

  if (!isRecord(schema) || Array.isArray(schema)) {
    issues.push({
      path: '$',
      feature: 'invalid-schema',
      description: 'Schema must be a JSON object',
    });
    return { compatible: false, issues };
  }

  const root = schema;

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
  if (isRecord(root.properties)) {
    checkProperties(root.properties, '$.properties', issues);
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
    if (!isRecord(prop) || Array.isArray(prop)) {
      continue;
    }

    checkProperty(prop, `${basePath}.${name}`, issues);
  }
}

const SUPPORTED_FORMATS = new Set(['date', 'date-time', 'email', 'uri']);

function checkProperty(
  prop: Record<string, unknown>,
  path: string,
  issues: CompatibilityIssue[],
): void {
  checkUnsupportedPropertyKeys(prop, path, issues);
  checkPropertyType(prop, path, issues);
  checkPropertyFormat(prop, path, issues);
  checkAdditionalProperties(prop, path, issues);
  checkNestedDefinitions(prop, path, issues);
}

function checkUnsupportedPropertyKeys(
  prop: Record<string, unknown>,
  path: string,
  issues: CompatibilityIssue[],
): void {
  for (const key of Object.keys(prop)) {
    if (!SUPPORTED_PROPERTY_KEYS.has(key)) {
      issues.push({
        path: `${path}.${key}`,
        feature: key,
        description: describeUnsupportedKey(key),
      });
    }
  }
}

function checkPropertyType(prop: Record<string, unknown>, path: string, issues: CompatibilityIssue[]): void {
  const rawType = prop.type;
  if (Array.isArray(rawType)) {
    issues.push({
      path: `${path}.type`,
      feature: 'type-union',
      description: 'Type unions (array of types) are not supported',
    });
    return;
  }

  if (typeof rawType === 'string' && !SUPPORTED_TYPES.has(rawType)) {
    issues.push({
      path: `${path}.type`,
      feature: `type-${rawType}`,
      description: `Type "${rawType}" is not supported`,
    });
  }
}

function checkPropertyFormat(
  prop: Record<string, unknown>,
  path: string,
  issues: CompatibilityIssue[],
): void {
  const rawFormat = prop.format;
  if (typeof rawFormat === 'undefined') {
    return;
  }

  const type = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  const formatValue = typeof rawFormat === 'string' ? rawFormat : JSON.stringify(rawFormat);
  const isSupported = SUPPORTED_FORMATS.has(formatValue) && type === 'string';

  if (!isSupported) {
    issues.push({
      path: `${path}.format`,
      feature: `format-${formatValue}`,
      description: `Format "${formatValue}" is not supported (only "date", "date-time", "email", and "uri" on string type)`,
    });
  }
}

function checkAdditionalProperties(
  prop: Record<string, unknown>,
  path: string,
  issues: CompatibilityIssue[],
): void {
  if (prop.additionalProperties === false) {
    issues.push({
      path: `${path}.additionalProperties`,
      feature: 'additionalProperties-false',
      description: '"additionalProperties: false" is not supported',
    });
  }
}

function checkNestedDefinitions(
  prop: Record<string, unknown>,
  path: string,
  issues: CompatibilityIssue[],
): void {
  if (isRecord(prop.properties)) {
    checkProperties(prop.properties, `${path}.properties`, issues);
  }

  if (isRecord(prop.items) && !Array.isArray(prop.items)) {
    checkProperty(prop.items, `${path}.items`, issues);
    return;
  }

  if (Array.isArray(prop.items)) {
    issues.push({
      path: `${path}.items`,
      feature: 'tuple-items',
      description: 'Tuple validation (array-form items) is not supported',
    });
  }
}

/** Map well-known unsupported keys to human-readable descriptions. */
function describeUnsupportedKey(key: string): string {
  for (const [candidate, description] of UNSUPPORTED_KEY_DESCRIPTIONS) {
    if (candidate === key) {
      return description;
    }
  }

  return `"${key}" is not supported`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
