import type { JsonSchema, SchemaEditMode } from '../types.js';

export interface ActiveSchemaState {
  schemaEditMode: SchemaEditMode;
  rawJsonSchema: object | null;
  schema: JsonSchema | null;
}

export function isRootJsonSchema(value: unknown): value is JsonSchema {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    Reflect.get(value, 'type') === 'object'
  );
}

export function toJsonSchemaOrNull(schema: object | null): JsonSchema | null {
  return isRootJsonSchema(schema) ? schema : null;
}

export function getActiveSchema(state: ActiveSchemaState): JsonSchema | null {
  if (state.schemaEditMode === 'json-only') {
    return toJsonSchemaOrNull(state.rawJsonSchema);
  }

  return state.schema;
}
