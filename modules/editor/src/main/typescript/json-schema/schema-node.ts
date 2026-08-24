// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

export type JsonSchemaValue =
  | string
  | number
  | boolean
  | null
  | JsonSchemaValue[]
  | { [key: string]: JsonSchemaValue };

/** Object-form JSON Schema keywords used by the editor's schema projection. */
export interface JsonSchemaNode {
  $ref?: string;
  type?: string | string[];
  format?: string;
  title?: string;
  description?: string;
  properties?: Record<string, JsonSchemaNode>;
  items?: JsonSchemaNode;
  required?: string[];
  allOf?: JsonSchemaNode[];
  oneOf?: JsonSchemaNode[];
  anyOf?: JsonSchemaNode[];
  enum?: JsonSchemaValue[];
  const?: JsonSchemaValue;
  default?: JsonSchemaValue;
  minimum?: number;
  maximum?: number;
  minItems?: number;
  maxItems?: number;
  minLength?: number;
  maxLength?: number;
  multipleOf?: number;
  exclusiveMinimum?: number;
  exclusiveMaximum?: number;
  additionalProperties?: boolean | JsonSchemaNode;
  $defs?: Record<string, JsonSchemaNode>;
  definitions?: Record<string, JsonSchemaNode>;
}

export function schemaTypes(schema: JsonSchemaNode): string[] {
  if (typeof schema.type === 'string') return [schema.type];
  return stringArray(schema.type);
}

export function schemaProperties(schema: JsonSchemaNode): Record<string, JsonSchemaNode> {
  if (!isObject(schema.properties)) return {};
  return Object.fromEntries(
    Object.entries(schema.properties).filter((entry): entry is [string, JsonSchemaNode] =>
      isSchemaNode(entry[1]),
    ),
  );
}

export function schemaArray(value: unknown): JsonSchemaNode[] {
  return Array.isArray(value) ? value.filter(isSchemaNode) : [];
}

export function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : [];
}

export function sameJson(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isSchemaNode(value: unknown): value is JsonSchemaNode {
  return isObject(value);
}
