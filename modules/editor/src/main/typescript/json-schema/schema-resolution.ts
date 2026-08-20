// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Public JSON Schema projection surface shared by editor features.
 *
 * Resolution, value matching, and presentation live in separate cohesive
 * modules; consumers import this facade rather than depending on those details.
 */

export type { JsonSchemaNode, JsonSchemaValue } from './schema-node.js';
export {
  MAX_RESOLVED_SCHEMA_VARIANTS,
  resolveSchemaVariants,
  type ResolvedSchemaVariant,
} from './schema-variants.js';
export { resolveSchemaForValue } from './schema-value-resolution.js';
