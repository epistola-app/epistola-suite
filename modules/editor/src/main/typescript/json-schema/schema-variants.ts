// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import {
  isObject,
  schemaArray,
  schemaProperties,
  stringArray,
  type JsonSchemaNode,
} from './schema-node.js';

/** Prevent hostile or accidental composition graphs from growing exponentially. */
export const MAX_RESOLVED_SCHEMA_VARIANTS = 256;

export interface ResolvedSchemaVariant {
  schema: JsonSchemaNode;
  /** References active on the route to this variant, used to stop recursive schemas. */
  resolvingRefs: ReadonlySet<string>;
}

/**
 * Resolve every effective variant at one schema location.
 *
 * `allOf` members are merged and `oneOf`/`anyOf` branches are returned
 * separately. Local references are expanded and composition growth is bounded.
 */
export function resolveSchemaVariants(
  schema: JsonSchemaNode,
  rootSchema: JsonSchemaNode,
  resolvingRefs: ReadonlySet<string> = new Set(),
): ResolvedSchemaVariant[] {
  return resolveVariants(schema, rootSchema, resolvingRefs);
}

function resolveVariants(
  schema: JsonSchemaNode,
  rootSchema: JsonSchemaNode,
  resolvingRefs: ReadonlySet<string>,
): ResolvedSchemaVariant[] {
  const reference = typeof schema.$ref === 'string' ? schema.$ref : undefined;
  if (reference?.startsWith('#/') && !resolvingRefs.has(reference)) {
    const target = resolveLocalReference(rootSchema, reference);
    if (target) {
      const nextRefs = new Set(resolvingRefs);
      nextRefs.add(reference);
      const { $ref: _ref, ...siblings } = schema;
      return resolveVariants(target, rootSchema, nextRefs).flatMap((variant) =>
        expandCompositions(
          mergeSchemas(variant.schema, siblings),
          rootSchema,
          variant.resolvingRefs,
        ),
      );
    }
  }

  return expandCompositions(schema, rootSchema, resolvingRefs);
}

function expandCompositions(
  schema: JsonSchemaNode,
  rootSchema: JsonSchemaNode,
  resolvingRefs: ReadonlySet<string>,
): ResolvedSchemaVariant[] {
  let variants: ResolvedSchemaVariant[] = [{ schema: withoutCompositions(schema), resolvingRefs }];

  for (const member of schemaArray(schema.allOf)) {
    variants = combineVariants(variants, resolveVariants(member, rootSchema, resolvingRefs));
  }

  for (const alternatives of [schemaArray(schema.oneOf), schemaArray(schema.anyOf)]) {
    if (alternatives.length > 0) {
      variants = combineVariants(
        variants,
        collectAlternativeVariants(alternatives, rootSchema, resolvingRefs),
      );
    }
  }

  return variants;
}

function combineVariants(
  bases: ResolvedSchemaVariant[],
  extensions: ResolvedSchemaVariant[],
): ResolvedSchemaVariant[] {
  const combined: ResolvedSchemaVariant[] = [];
  const seen = new Set<string>();
  for (const base of bases) {
    for (const extension of extensions) {
      const variant = {
        schema: mergeSchemas(base.schema, extension.schema),
        resolvingRefs: new Set([...base.resolvingRefs, ...extension.resolvingRefs]),
      };
      const key = variantKey(variant);
      if (seen.has(key)) continue;
      seen.add(key);
      combined.push(variant);
      if (combined.length >= MAX_RESOLVED_SCHEMA_VARIANTS) return combined;
    }
  }
  return combined;
}

function collectAlternativeVariants(
  alternatives: JsonSchemaNode[],
  rootSchema: JsonSchemaNode,
  resolvingRefs: ReadonlySet<string>,
): ResolvedSchemaVariant[] {
  const variants: ResolvedSchemaVariant[] = [];
  const seen = new Set<string>();
  for (const alternative of alternatives) {
    for (const variant of resolveVariants(alternative, rootSchema, resolvingRefs)) {
      const key = variantKey(variant);
      if (seen.has(key)) continue;
      seen.add(key);
      variants.push(variant);
      if (variants.length >= MAX_RESOLVED_SCHEMA_VARIANTS) return variants;
    }
  }
  return variants;
}

function variantKey(variant: ResolvedSchemaVariant): string {
  return `${JSON.stringify(variant.schema)}|${[...variant.resolvingRefs].toSorted().join(',')}`;
}

function mergeSchemas(base: JsonSchemaNode, extension: JsonSchemaNode): JsonSchemaNode {
  const baseProperties = schemaProperties(base);
  const extensionProperties = schemaProperties(extension);
  const hasProperties =
    Object.keys(baseProperties).length > 0 || Object.keys(extensionProperties).length > 0;
  const required = [...stringArray(base.required), ...stringArray(extension.required)];

  return {
    ...base,
    ...extension,
    ...(hasProperties ? { properties: { ...baseProperties, ...extensionProperties } } : {}),
    ...(required.length > 0 ? { required: [...new Set(required)] } : {}),
  };
}

function withoutCompositions(schema: JsonSchemaNode): JsonSchemaNode {
  const { allOf: _allOf, anyOf: _anyOf, oneOf: _oneOf, ...rest } = schema;
  return rest;
}

function resolveLocalReference(
  rootSchema: JsonSchemaNode,
  reference: string,
): JsonSchemaNode | null {
  let current: unknown = rootSchema;
  for (const encodedSegment of reference.slice(2).split('/')) {
    if (!isObject(current)) return null;
    const segment = encodedSegment.replace(/~1/g, '/').replace(/~0/g, '~');
    current = current[segment];
  }
  return isObject(current) ? current : null;
}
