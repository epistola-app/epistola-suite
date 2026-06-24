import { nanoid } from 'nanoid';
import {
  type JsonObject,
  type JsonSchema,
  type JsonSchemaProperty,
  type JsonValue,
  type PrimitiveField,
  type PrimitiveFieldType,
  type SchemaField,
  type SchemaFieldType,
  type SchemaFieldUpdate,
  type StringFormat,
  type VisualSchema,
} from '../types.js';
import { classifyValue, findRefType, getRefTypeById, type RefTypeId } from '../ref-types.js';
import { isScalarFieldType, scalarFromJsonSchema, scalarToJsonSchema } from '../field-types.js';

// Re-exported so existing importers keep their `schemaUtils` import path; the
// labels themselves are defined once in the field-type registry.
export { FIELD_TYPE_LABELS } from '../field-types.js';

/** True when a SchemaFieldType is one of the registered ref-typed field types. */
function isRefFieldType(type: SchemaFieldType): type is RefTypeId {
  return type === 'richTextInline' || type === 'richTextBlock';
}

/**
 * Convert a visual schema to JSON Schema format.
 */
export function visualSchemaToJsonSchema(visual: VisualSchema): JsonSchema {
  const properties: Record<string, JsonSchemaProperty> = {};
  const required: string[] = [];

  for (const field of visual.fields) {
    properties[field.name] = fieldToJsonSchemaProperty(field);
    if (field.required) {
      required.push(field.name);
    }
  }

  return {
    $schema: 'http://json-schema.org/draft-07/schema#',
    type: 'object',
    properties,
    required: required.length > 0 ? required : undefined,
    additionalProperties: true,
  };
}

/**
 * Convert a single field to a JSON Schema property.
 */
function fieldToJsonSchemaProperty(field: SchemaField): JsonSchemaProperty {
  // Ref-based field types are stored as a `$ref` to their canonical schema URL
  // (no `type` key). The registry resolves the field type to its URL.
  if (isRefFieldType(field.type)) {
    const prop: JsonSchemaProperty = { $ref: getRefTypeById(field.type).url };
    if (field.description) prop.description = field.description;
    return prop;
  }

  // Scalars map to a JSON Schema { type, format? } per the field-type registry
  // (date → string/date, date-time → string/date-time, etc.).
  const scalar = isScalarFieldType(field.type) ? scalarToJsonSchema(field.type) : null;
  const prop: JsonSchemaProperty = { type: scalar ? scalar.type : field.type };
  if (scalar?.format) {
    prop.format = scalar.format;
  }

  // String format (e.g. "email"/"uri") — the date formats are covered above.
  if (field.type === 'string' && 'format' in field && field.format) {
    prop.format = field.format;
  }

  if (field.description) {
    prop.description = field.description;
  }

  // Numeric constraints
  if ((field.type === 'number' || field.type === 'integer') && 'minimum' in field) {
    if (field.minimum !== undefined) prop.minimum = field.minimum;
    if (field.maximum !== undefined) prop.maximum = field.maximum;
  }

  if (field.type === 'array' && field.arrayItemType) {
    if (field.arrayItemType === 'object' && field.nestedFields) {
      // Array of objects
      const nestedRequired = field.nestedFields.filter((f) => f.required).map((f) => f.name);
      prop.items = {
        type: 'object',
        properties: nestedFieldsToProperties(field.nestedFields),
      };
      if (nestedRequired.length > 0) {
        prop.items.required = nestedRequired;
      }
    } else if (isRefFieldType(field.arrayItemType)) {
      prop.items = { $ref: getRefTypeById(field.arrayItemType).url };
    } else if (isScalarFieldType(field.arrayItemType)) {
      // Scalars (incl. date / date-time) must map through the registry so a
      // list item serializes to a valid JSON Schema `{ type, format? }` —
      // `{ type: 'string', format: 'date-time' }`, not `{ type: 'datetime' }`.
      const itemScalar = scalarToJsonSchema(field.arrayItemType);
      prop.items = itemScalar.format
        ? { type: itemScalar.type, format: itemScalar.format }
        : { type: itemScalar.type };
    } else {
      prop.items = { type: field.arrayItemType };
    }
    if (field.minItems !== undefined) {
      prop.minItems = field.minItems;
    }
  }

  if (field.type === 'object' && field.nestedFields) {
    prop.properties = nestedFieldsToProperties(field.nestedFields);
    const nestedRequired = field.nestedFields.filter((f) => f.required).map((f) => f.name);
    if (nestedRequired.length > 0) {
      prop.required = nestedRequired;
    }
  }

  return prop;
}

/**
 * Convert nested fields to JSON Schema properties.
 */
function nestedFieldsToProperties(fields: SchemaField[]): Record<string, JsonSchemaProperty> {
  const properties: Record<string, JsonSchemaProperty> = {};
  for (const field of fields) {
    properties[field.name] = fieldToJsonSchemaProperty(field);
  }
  return properties;
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
    return { fields: [] };
  }

  const jsonSchema = schema as JsonSchema;
  if (jsonSchema.type !== 'object' || !jsonSchema.properties) {
    return { fields: [] };
  }

  const requiredFields = new Set(jsonSchema.required || []);
  const fields: SchemaField[] = [];

  for (const [name, prop] of Object.entries(jsonSchema.properties)) {
    fields.push(jsonSchemaPropertyToField(name, prop, requiredFields.has(name), name));
  }

  return { fields };
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
  // Ref-based property: detected by $ref to one of the registered schema URLs.
  const refType = findRefType(prop.$ref);
  if (refType !== null) {
    return {
      id: `field:${path}`,
      name,
      required,
      description: prop.description,
      type: refType.id,
    };
  }

  const rawType = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  // Resolve scalars (incl. date / date-time) via the registry; everything else
  // (array, object, or a string carrying an email/uri format) keeps rawType.
  const type = scalarFromJsonSchema(rawType, prop.format) ?? rawType;
  const baseField = {
    id: `field:${path}`,
    name,
    required,
    description: prop.description,
  };

  if (type === 'array') {
    const itemRefType = findRefType(prop.items?.$ref);
    const itemRawType = Array.isArray(prop.items?.type) ? prop.items?.type[0] : prop.items?.type;
    const itemType: SchemaFieldType =
      itemRefType !== null
        ? itemRefType.id
        : // Recover date / date-time list items from `{ type, format }`, mirroring
          // the top-level scalar resolution above.
          ((scalarFromJsonSchema(itemRawType, prop.items?.format) ??
            itemRawType ??
            'string') as SchemaFieldType);
    const nestedFields =
      itemType === 'object' && prop.items?.properties
        ? Object.entries(prop.items.properties).map(([n, p]) =>
            jsonSchemaPropertyToField(
              n,
              p,
              new Set(prop.items?.required || []).has(n),
              `${path}.${n}`,
            ),
          )
        : undefined;
    return {
      ...baseField,
      type: 'array' as const,
      arrayItemType: itemType,
      nestedFields,
      ...(prop.minItems !== undefined ? { minItems: prop.minItems } : {}),
    };
  }

  if (type === 'object') {
    const nestedRequired = new Set(prop.required || []);
    const nestedFields = prop.properties
      ? Object.entries(prop.properties).map(([n, p]) =>
          jsonSchemaPropertyToField(n, p, nestedRequired.has(n), `${path}.${n}`),
        )
      : undefined;
    return {
      ...baseField,
      type: 'object' as const,
      nestedFields,
    };
  }

  // Primitive types — carry over format, minimum, maximum
  const primitiveField: SchemaField = {
    ...baseField,
    type: type as PrimitiveFieldType,
  };

  // A `string` that survived scalar resolution carries a non-date format
  // constraint (email/uri); the date formats already became their own types.
  if (type === 'string' && prop.format) {
    (primitiveField as PrimitiveField).format = prop.format as StringFormat;
  }

  // Numeric constraints
  if (type === 'number' || type === 'integer') {
    if (prop.minimum !== undefined) (primitiveField as PrimitiveField).minimum = prop.minimum;
    if (prop.maximum !== undefined) (primitiveField as PrimitiveField).maximum = prop.maximum;
  }

  return primitiveField;
}

/**
 * Generate a draft schema from a data example.
 * Infers types from values and defaults all fields to optional.
 */
export function generateSchemaFromData(data: JsonObject): VisualSchema {
  const fields: SchemaField[] = [];

  for (const [name, value] of Object.entries(data)) {
    fields.push(inferFieldFromValue(name, value, name));
  }

  return { fields };
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
  };
  const type = inferType(value);

  if (type === 'array' && Array.isArray(value)) {
    const firstItem = value.length > 0 ? value[0] : null;
    const arrayItemType = inferType(firstItem);
    const nestedFields =
      typeof firstItem === 'object' && firstItem !== null && !Array.isArray(firstItem)
        ? Object.entries(firstItem).map(([n, v]) => inferFieldFromValue(n, v, `${path}.${n}`))
        : undefined;
    return {
      ...baseField,
      type: 'array' as const,
      arrayItemType,
      nestedFields,
    };
  }

  if (type === 'object' && typeof value === 'object' && value !== null && !Array.isArray(value)) {
    return {
      ...baseField,
      type: 'object' as const,
      nestedFields: Object.entries(value).map(([n, v]) =>
        inferFieldFromValue(n, v, `${path}.${n}`),
      ),
    };
  }

  // Primitive types
  return {
    ...baseField,
    type: type as PrimitiveFieldType,
  };
}

/**
 * Infer the JSON Schema type from a value.
 */
/** ISO date pattern: YYYY-MM-DD */
const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * ISO date-time pattern: YYYY-MM-DDThh:mm with optional seconds, optional
 * fractional seconds (only when seconds are present), and optional timezone.
 * Seconds are optional to match the `datetime-local` picker and the backend
 * validator, which both accept `…Thh:mm` — otherwise inference would fall back
 * to `string` for seconds-less but valid date-times.
 */
const ISO_DATETIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})?$/;

function inferType(value: JsonValue): SchemaFieldType {
  if (value === null) {
    return 'string'; // Default null to string
  }
  if (Array.isArray(value)) {
    return 'array';
  }
  if (typeof value === 'object') {
    // Ref-based shapes (rich-text doc, etc.) — first registered type whose
    // shallow shape check passes wins. Falls back to plain `object`.
    const refType = classifyValue(value);
    if (refType !== null) return refType.id;
    return 'object';
  }
  if (typeof value === 'boolean') {
    return 'boolean';
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? 'integer' : 'number';
  }
  if (typeof value === 'string' && ISO_DATE_RE.test(value)) {
    return 'date';
  }
  if (typeof value === 'string' && ISO_DATETIME_RE.test(value)) {
    return 'datetime';
  }
  return 'string';
}

/** Valid field name: starts with letter or underscore, contains only letters, digits, underscores. */
const VALID_FIELD_NAME_RE = /^[a-zA-Z_][a-zA-Z0-9_]*$/;

/**
 * Check whether a field name is a valid identifier.
 * Rejects dashes, dots, spaces, and other special characters that break JSONata expressions.
 */
export function isValidFieldName(name: string): boolean {
  return VALID_FIELD_NAME_RE.test(name);
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
  };
}

/**
 * Apply updates to a schema field, returning a properly typed SchemaField.
 * Handles type changes by constructing the appropriate discriminated union variant.
 */
export function applyFieldUpdate(field: SchemaField, updates: SchemaFieldUpdate): SchemaField {
  const type = updates.type ?? field.type;
  const baseField = {
    id: field.id,
    name: updates.name ?? field.name,
    required: updates.required ?? field.required,
    description: updates.description ?? field.description,
  };

  if (type === 'array') {
    const arrayItemType =
      updates.arrayItemType ?? (field.type === 'array' ? field.arrayItemType : 'string');
    const nestedFields =
      updates.nestedFields !== undefined
        ? updates.nestedFields
        : field.type === 'array'
          ? field.nestedFields
          : undefined;
    const minItems =
      'minItems' in updates
        ? updates.minItems
        : field.type === 'array'
          ? field.minItems
          : undefined;
    return {
      ...baseField,
      type: 'array' as const,
      arrayItemType,
      nestedFields,
      ...(minItems !== undefined ? { minItems } : {}),
    };
  }

  if (type === 'object') {
    const nestedFields =
      updates.nestedFields !== undefined
        ? updates.nestedFields
        : field.type === 'object'
          ? field.nestedFields
          : undefined;
    return {
      ...baseField,
      type: 'object' as const,
      nestedFields,
    };
  }

  // Primitive types — only carry over constraints that are relevant to the new type.
  // When the type changes, constraints from the old type are dropped.
  const sameType = type === field.type;
  const oldIsPrimitive = field.type !== 'array' && field.type !== 'object';
  const isNumeric = type === 'number' || type === 'integer';
  const isString = type === 'string' || type === 'date';

  // Format: only relevant for string types, carry over only if type didn't change
  const existingFormat =
    sameType && isString && oldIsPrimitive ? (field as PrimitiveField).format : undefined;
  const format = 'format' in updates ? updates.format : existingFormat;

  // Minimum/maximum: only relevant for numeric types, carry over only if type didn't change
  const existingMinimum =
    sameType && isNumeric && oldIsPrimitive ? (field as PrimitiveField).minimum : undefined;
  const existingMaximum =
    sameType && isNumeric && oldIsPrimitive ? (field as PrimitiveField).maximum : undefined;
  const minimum = 'minimum' in updates ? updates.minimum : existingMinimum;
  const maximum = 'maximum' in updates ? updates.maximum : existingMaximum;

  return {
    ...baseField,
    type: type as PrimitiveFieldType,
    ...(format !== undefined ? { format } : {}),
    ...(minimum !== undefined ? { minimum } : {}),
    ...(maximum !== undefined ? { maximum } : {}),
  };
}

/**
 * Get all paths from a schema (for expression matching).
 */
export function getSchemaFieldPaths(schema: VisualSchema): Set<string> {
  const paths = new Set<string>();

  function traverse(fields: SchemaField[], prefix = '') {
    for (const field of fields) {
      const path = prefix ? `${prefix}.${field.name}` : field.name;
      paths.add(path);

      if (field.type === 'object' && field.nestedFields) {
        traverse(field.nestedFields, path);
      }

      if (field.type === 'array') {
        const arrayPath = `${path}[]`;
        paths.add(arrayPath);

        if (field.arrayItemType === 'object' && field.nestedFields) {
          traverse(field.nestedFields, arrayPath);
        }
      }
    }
  }

  traverse(schema.fields);
  return paths;
}
