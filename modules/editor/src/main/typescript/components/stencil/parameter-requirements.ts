// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { JsonSchema } from '../../data-contract/types.js';

/** A required parameter with an explicit schema default does not need an instance binding. */
export function parameterHasDefault(schema: JsonSchema, name: string): boolean {
  const property = schema.properties?.[name];
  return property !== undefined && Object.prototype.hasOwnProperty.call(property, 'default');
}

/** Required parameters that have neither a non-blank binding nor a schema default. */
export function missingRequiredParameters(
  schema: JsonSchema,
  bindings: Record<string, string>,
): string[] {
  return (schema.required ?? []).filter(
    (name) => !(bindings[name] ?? '').trim() && !parameterHasDefault(schema, name),
  );
}
