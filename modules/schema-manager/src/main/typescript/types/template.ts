import { z } from "zod";

// JSON types (matches ObjectNode on backend)
export const JsonValueSchema: z.ZodType<JsonValue> = z.lazy(() =>
  z.union([
    z.string(),
    z.number(),
    z.boolean(),
    z.null(),
    z.array(JsonValueSchema),
    z.record(z.string(), JsonValueSchema),
  ]),
);
export type JsonValue = string | number | boolean | null | JsonObject | JsonArray;

export const JsonObjectSchema: z.ZodType<JsonObject> = z.record(z.string(), JsonValueSchema);
export interface JsonObject {
  [key: string]: JsonValue;
}

export const JsonArraySchema: z.ZodType<JsonArray> = z.array(JsonValueSchema);
export type JsonArray = JsonValue[];

// Named example data set that adheres to the template's dataModel schema
export interface DataExample {
  id: string;
  name: string;
  data: JsonObject;
}

// Zod schema for runtime validation
// Uses z.record with z.unknown() to avoid recursive type depth issues while still
// validating the structure is a valid object (not array/primitive)
export const DataExampleSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  data: z.record(z.string(), z.unknown()),
});

// JSON Schema type (defined in schema.ts but re-exported here for convenience)
export type JsonSchema = {
  $schema?: string;
  type: "object";
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
  additionalProperties?: boolean;
};

export type JsonSchemaProperty = {
  type: string | string[];
  description?: string;
  items?: JsonSchemaProperty;
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
};
