/**
 * Types for the data contract editor.
 * Supports common JSON Schema types with visual editing.
 *
 * Ported from schema-manager — Zod removed, pure TypeScript types only.
 */

// =============================================================================
// JSON types
// =============================================================================

export type JsonValue = string | number | boolean | null | JsonObject | JsonArray;

export interface JsonObject {
  [key: string]: JsonValue;
}

export type JsonArray = JsonValue[];

// =============================================================================
// Data Example
// =============================================================================

/** Named example data set that adheres to the template's dataModel schema */
export interface DataExample {
  id: string;
  name: string;
  data: JsonObject;
}

// =============================================================================
// Field Types
// =============================================================================

/** Supported field types in the visual editor */
export type SchemaFieldType =
  | 'string'
  | 'number'
  | 'integer'
  | 'boolean'
  | 'date'
  | 'array'
  | 'object';

/** Primitive field types (non-container types) */
export type PrimitiveFieldType = 'string' | 'number' | 'integer' | 'boolean' | 'date';

// =============================================================================
// Schema Fields (Discriminated Union)
// =============================================================================

/** Base properties shared by all field types */
interface BaseField {
  id: string;
  name: string;
  required: boolean;
  description?: string;
}

/** Supported string formats */
export type StringFormat = 'date' | 'date-time' | 'email' | 'uri';

/** A primitive field (string, number, integer, boolean) */
export interface PrimitiveField extends BaseField {
  type: PrimitiveFieldType;
  /** Format constraint (e.g. "email") — only for string/date types */
  format?: StringFormat;
  /** Minimum value — only for number/integer types */
  minimum?: number;
  /** Maximum value — only for number/integer types */
  maximum?: number;
}

/** An array field with a required item type */
export interface ArrayField extends BaseField {
  type: 'array';
  arrayItemType: SchemaFieldType;
  nestedFields?: SchemaField[];
  /** Minimum number of items in the array */
  minItems?: number;
}

/** An object field with optional nested fields */
export interface ObjectField extends BaseField {
  type: 'object';
  nestedFields?: SchemaField[];
}

/** A field in the visual schema editor (discriminated union on `type`) */
export type SchemaField = PrimitiveField | ArrayField | ObjectField;

// =============================================================================
// Schema Field Update
// =============================================================================

/** Partial update payload for modifying a schema field */
export interface SchemaFieldUpdate {
  name?: string;
  type?: SchemaFieldType;
  required?: boolean;
  description?: string;
  arrayItemType?: SchemaFieldType;
  nestedFields?: SchemaField[];
  format?: StringFormat | undefined;
  minimum?: number | undefined;
  maximum?: number | undefined;
  minItems?: number | undefined;
}

// =============================================================================
// Visual Schema
// =============================================================================

/** Visual schema representation for the editor */
export interface VisualSchema {
  fields: SchemaField[];
}

// =============================================================================
// JSON Schema Types (for storage/validation interop)
// =============================================================================

/** JSON Schema property type */
export interface JsonSchemaProperty {
  type: SchemaFieldType | SchemaFieldType[];
  format?: string;
  description?: string;
  items?: JsonSchemaProperty;
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
  minimum?: number;
  maximum?: number;
  minItems?: number;
}

/** JSON Schema (subset supported by the visual editor) */
export interface JsonSchema {
  $schema?: string;
  type: 'object';
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
  additionalProperties?: boolean;
}

// =============================================================================
// Schema Edit Mode
// =============================================================================

/** Whether the visual editor can represent the current schema */
export type SchemaEditMode = 'visual' | 'json-only';

// =============================================================================
// Validation & Save types
// =============================================================================

export interface ValidationError {
  path: string;
  message: string;
}

export interface UpdateDataExampleResult {
  success: boolean;
  example?: DataExample;
  warnings?: Record<string, ValidationError[]>;
  errors?: Record<string, ValidationError[]>;
}

export interface SaveSchemaResult {
  success: boolean;
  warnings?: Record<string, ValidationError[]>;
  error?: string;
}

export interface SaveExamplesResult {
  success: boolean;
  warnings?: Record<string, ValidationError[]>;
  error?: string;
}

export interface SaveCallbacks {
  onSaveSchema?: (
    schema: JsonSchema | null,
    forceUpdate?: boolean,
  ) => Promise<{ success: boolean; warnings?: Record<string, ValidationError[]>; error?: string }>;
  onSaveDataExamples?: (
    examples: DataExample[],
  ) => Promise<{ success: boolean; warnings?: Record<string, ValidationError[]>; error?: string }>;
  onUpdateDataExample?: (
    exampleId: string,
    updates: { name?: string; data?: JsonObject },
    forceUpdate?: boolean,
  ) => Promise<UpdateDataExampleResult>;
  onDeleteDataExample?: (exampleId: string) => Promise<{ success: boolean }>;
}

/**
 * Check if a parsed value is a JSON object (not array or primitive).
 * Replaces the Zod `JsonObjectSchema.safeParse()` call.
 */
export function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
