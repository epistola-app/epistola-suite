/**
 * Types for the visual schema editor.
 * Supports common JSON Schema types with visual editing.
 * Uses Zod for schema definition and runtime validation.
 */
import { z } from "zod";

// =============================================================================
// Field Type Schemas
// =============================================================================

/** Supported field types in the visual editor */
export const SchemaFieldTypeSchema = z.enum([
  "string",
  "number",
  "integer",
  "boolean",
  "array",
  "object",
]);
export type SchemaFieldType = z.infer<typeof SchemaFieldTypeSchema>;

/** Primitive field types (non-container types) */
export const PrimitiveFieldTypeSchema = z.enum(["string", "number", "integer", "boolean"]);
export type PrimitiveFieldType = z.infer<typeof PrimitiveFieldTypeSchema>;

// =============================================================================
// Schema Field Schemas (Discriminated Union)
// =============================================================================

/** Base properties shared by all field types */
const BaseFieldSchema = z.object({
  id: z.string(),
  name: z.string(),
  required: z.boolean(),
  description: z.string().optional(),
});

/** A primitive field (string, number, integer, boolean) */
export const PrimitiveFieldSchema = BaseFieldSchema.extend({
  type: PrimitiveFieldTypeSchema,
});
export type PrimitiveField = z.infer<typeof PrimitiveFieldSchema>;

/** An array field with a required item type */
export const ArrayFieldSchema: z.ZodType<ArrayField> = BaseFieldSchema.extend({
  type: z.literal("array"),
  arrayItemType: SchemaFieldTypeSchema,
  nestedFields: z.lazy(() => SchemaFieldSchema.array()).optional(),
});
export type ArrayField = z.infer<typeof BaseFieldSchema> & {
  type: "array";
  arrayItemType: SchemaFieldType;
  nestedFields?: SchemaField[];
};

/** An object field with optional nested fields */
export const ObjectFieldSchema: z.ZodType<ObjectField> = BaseFieldSchema.extend({
  type: z.literal("object"),
  nestedFields: z.lazy(() => SchemaFieldSchema.array()).optional(),
});
export type ObjectField = z.infer<typeof BaseFieldSchema> & {
  type: "object";
  nestedFields?: SchemaField[];
};

/**
 * A field in the visual schema editor.
 * Uses discriminated union to enforce type-specific constraints.
 */
export const SchemaFieldSchema: z.ZodType<SchemaField> = z.discriminatedUnion("type", [
  PrimitiveFieldSchema,
  BaseFieldSchema.extend({
    type: z.literal("array"),
    arrayItemType: SchemaFieldTypeSchema,
    nestedFields: z.lazy(() => SchemaFieldSchema.array()).optional(),
  }),
  BaseFieldSchema.extend({
    type: z.literal("object"),
    nestedFields: z.lazy(() => SchemaFieldSchema.array()).optional(),
  }),
]);
export type SchemaField = PrimitiveField | ArrayField | ObjectField;

// =============================================================================
// Schema Field Update Schema
// =============================================================================

/**
 * Update payload for modifying a schema field.
 * Allows partial updates without enforcing discriminated union constraints.
 */
export const SchemaFieldUpdateSchema = z.object({
  name: z.string().optional(),
  type: SchemaFieldTypeSchema.optional(),
  required: z.boolean().optional(),
  description: z.string().optional(),
  arrayItemType: SchemaFieldTypeSchema.optional(),
  nestedFields: z.lazy(() => SchemaFieldSchema.array()).optional(),
});
export type SchemaFieldUpdate = z.infer<typeof SchemaFieldUpdateSchema>;

// =============================================================================
// Visual Schema
// =============================================================================

/**
 * Visual schema representation for the editor.
 * A simplified view of JSON Schema for user-friendly editing.
 */
export const VisualSchemaSchema = z.object({
  fields: SchemaFieldSchema.array(),
});
export type VisualSchema = z.infer<typeof VisualSchemaSchema>;

// =============================================================================
// JSON Schema Types (for storage/validation interop)
// =============================================================================

/**
 * JSON Schema property type.
 * Defined manually due to recursive nature - Zod schema provided for validation.
 */
export type JsonSchemaProperty = {
  type: SchemaFieldType | SchemaFieldType[];
  description?: string;
  items?: JsonSchemaProperty;
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
};

/**
 * JSON Schema property schema for runtime validation.
 */
export const JsonSchemaPropertySchema: z.ZodType<JsonSchemaProperty> = z.lazy(() =>
  z.object({
    type: z.union([SchemaFieldTypeSchema, SchemaFieldTypeSchema.array()]),
    description: z.string().optional(),
    items: JsonSchemaPropertySchema.optional(),
    properties: z.record(z.string(), JsonSchemaPropertySchema).optional(),
    required: z.string().array().optional(),
  }),
);

/**
 * JSON Schema (subset supported by the visual editor).
 */
export type JsonSchema = {
  $schema?: string;
  type: "object";
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
  additionalProperties?: boolean;
};

export const JsonSchemaSchema: z.ZodType<JsonSchema> = z.object({
  $schema: z.string().optional(),
  type: z.literal("object"),
  properties: z.record(z.string(), JsonSchemaPropertySchema).optional(),
  required: z.string().array().optional(),
  additionalProperties: z.boolean().optional(),
});

// =============================================================================
// Impact Analysis Types
// =============================================================================

/** Issue types for impact analysis */
export const SchemaIssueTypeSchema = z.enum(["missing", "removed", "type_mismatch"]);

/**
 * Issue found during impact analysis.
 */
export const SchemaIssueSchema = z.object({
  type: SchemaIssueTypeSchema,
  path: z.string(),
  message: z.string(),
});
export type SchemaIssue = z.infer<typeof SchemaIssueSchema>;
