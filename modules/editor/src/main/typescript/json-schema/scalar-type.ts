// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/** Scalar labels used by editor surfaces when presenting JSON Schema fields. */
export type JsonSchemaScalarLabel =
  | 'string'
  | 'number'
  | 'integer'
  | 'boolean'
  | 'date'
  | 'datetime';

/** Map a JSON Schema primitive and format to the editor's scalar label. */
export function scalarFromJsonSchema(
  jsonType: string | undefined,
  format: string | undefined,
): JsonSchemaScalarLabel | null {
  if (jsonType === 'string' && format === 'date') return 'date';
  if (jsonType === 'string' && format === 'date-time') return 'datetime';
  if (jsonType === 'string' && format === undefined) return 'string';
  if (jsonType === 'number' && format === undefined) return 'number';
  if (jsonType === 'integer' && format === undefined) return 'integer';
  if (jsonType === 'boolean' && format === undefined) return 'boolean';
  return null;
}
