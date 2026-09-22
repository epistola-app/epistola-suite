// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Client-side validation of the schema fields themselves — as opposed to
 * `validateDataAgainstSchema` (schema/validation.ts), which checks example
 * *data* against the schema. This checks the field constraints an author
 * enters in the Schema Definition form for internal consistency, live,
 * before any save round-trip.
 */

import type { JsonSchema, SchemaField, ValidationError } from '../types.js';
import { fieldToJsonSchemaProperty } from './conversion.js';
import { validateProperty } from './validation.js';

/**
 * Walk every field (including nested object/array-of-object fields) and
 * report constraint problems: a negative `minItems`/`maxItems` (the JSON
 * Schema meta-schema requires both non-negative — the `min="0"` on the input
 * is only a soft hint, not enforcement, and a pasted/imported schema can set
 * either directly), and an array field's `maxItems`, when set alongside
 * `minItems`, being less than it — the combination is otherwise silently
 * unsatisfiable (no array can ever match).
 *
 * Errors are keyed by `field.id` (not a name-based path) — this collection
 * never leaves the browser, and the field's id is what the detail-panel
 * renderer already has on hand to look an error up by.
 */
export function validateSchemaFields(fields: readonly SchemaField[]): ValidationError[] {
  const errors: ValidationError[] = [];

  for (const field of fields) {
    if (field.type === 'array') {
      if (field.minItems !== undefined && field.minItems < 0) {
        errors.push({
          path: field.id,
          message: `"Min items" (${field.minItems}) must not be negative`,
        });
      }
      if (field.maxItems !== undefined && field.maxItems < 0) {
        errors.push({
          path: field.id,
          message: `"Max items" (${field.maxItems}) must not be negative`,
        });
      }
      if (
        field.minItems !== undefined &&
        field.maxItems !== undefined &&
        field.maxItems < field.minItems
      ) {
        errors.push({
          path: field.id,
          message: `"Max items" (${field.maxItems}) must not be less than "Min items" (${field.minItems})`,
        });
      }
      if (field.arrayItemType === 'object' && field.nestedFields) {
        errors.push(...validateSchemaFields(field.nestedFields));
      }
    } else if (field.type === 'object' && field.nestedFields) {
      errors.push(...validateSchemaFields(field.nestedFields));
    }

    if (field.default !== undefined) {
      errors.push(...validateFieldDefault(field));
    }
  }

  return errors;
}

/** Check a field's default value against its own type/format/range constraints. */
function validateFieldDefault(field: SchemaField): ValidationError[] {
  const property = fieldToJsonSchemaProperty(field);
  const rootSchema: JsonSchema = { type: 'object', properties: { [field.name]: property } };
  return validateProperty(field.default!, property, field.id, rootSchema).map((error) => ({
    path: field.id,
    message: `"Default value" ${error.message}`,
  }));
}
