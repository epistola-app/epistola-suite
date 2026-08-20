// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { JsonSchema } from '../types.js';
import { checkSchemaCompatibility, type CompatibilityIssue } from './schemaCompatibility.js';

type SchemaNode = Record<string, unknown>;

export interface SchemaNormalizationChange {
  path: string;
  feature: '$ref' | '$defs' | 'definitions' | 'allOf' | 'anyOf' | 'oneOf' | 'type-union';
  description: string;
}

export interface SchemaNormalizationResult {
  schema: JsonSchema | null;
  changes: SchemaNormalizationChange[];
  issues: CompatibilityIssue[];
}

interface NormalizationContext {
  root: SchemaNode;
  changes: SchemaNormalizationChange[];
  issues: CompatibilityIssue[];
}

/**
 * Convert losslessly representable JSON Schema constructs into the subset used
 * by the visual editor. Constructs that would require guessing or weakening the
 * schema are reported instead of being silently discarded.
 */
export function normalizeSchemaForVisualEditor(schema: unknown): SchemaNormalizationResult {
  if (!isSchemaNode(schema)) {
    return {
      schema: null,
      changes: [],
      issues: [
        {
          path: '$',
          feature: 'invalid-schema',
          description: 'Schema must be a JSON object',
        },
      ],
    };
  }

  const context: NormalizationContext = {
    root: schema,
    changes: [],
    issues: [],
  };
  const normalized = normalizeNode(schema, '$', context, new Set());
  const compatibility = checkSchemaCompatibility(normalized);
  const issues = [...context.issues, ...compatibility.issues];

  return {
    schema: issues.length === 0 && isJsonSchema(normalized) ? normalized : null,
    changes: context.changes,
    issues,
  };
}

function normalizeNode(
  node: SchemaNode,
  path: string,
  context: NormalizationContext,
  resolvingRefs: ReadonlySet<string>,
): SchemaNode {
  let normalized = structuredClone(node);
  let activeRefs = resolvingRefs;

  if (typeof normalized.$ref === 'string' && normalized.$ref.startsWith('#/')) {
    const reference = normalized.$ref;
    if (resolvingRefs.has(reference)) {
      context.issues.push({
        path: `${path}.$ref`,
        feature: 'recursive-ref',
        description: `Recursive reference "${reference}" cannot be represented by the visual editor`,
      });
      return normalized;
    }

    const target = resolveLocalReference(context.root, reference);
    if (!target) {
      context.issues.push({
        path: `${path}.$ref`,
        feature: 'unresolved-ref',
        description: `Local reference "${reference}" does not resolve to a schema object`,
      });
      return normalized;
    }

    const nextRefs = new Set(resolvingRefs);
    nextRefs.add(reference);
    activeRefs = nextRefs;
    const resolvedTarget = normalizeNode(target, referenceToPath(reference), context, nextRefs);
    const { $ref: _reference, ...siblings } = normalized;
    const normalizedSiblings = normalizeNode(siblings, path, context, nextRefs);
    normalized = mergeSchemas(resolvedTarget, normalizedSiblings, path, context);
    context.changes.push({
      path,
      feature: '$ref',
      description: `Inlined local reference "${reference}"`,
    });
  }

  if (Array.isArray(normalized.type) && normalized.type.length === 1) {
    normalized.type = normalized.type[0];
    context.changes.push({
      path: `${path}.type`,
      feature: 'type-union',
      description: 'Collapsed a single-value type union',
    });
  }

  if (Array.isArray(normalized.allOf)) {
    const { allOf, ...base } = normalized;
    normalized = normalizeNode(base, path, context, activeRefs);
    for (const [index, member] of allOf.entries()) {
      if (!isSchemaNode(member)) {
        context.issues.push({
          path: `${path}.allOf[${index}]`,
          feature: 'invalid-allOf-member',
          description: 'Composition member must be a schema object',
        });
        continue;
      }
      normalized = mergeSchemas(
        normalized,
        normalizeNode(member, `${path}.allOf[${index}]`, context, activeRefs),
        path,
        context,
      );
    }
    context.changes.push({
      path,
      feature: 'allOf',
      description: 'Flattened compatible allOf members',
    });
  }

  normalized = normalizeSingleMemberComposition(normalized, 'anyOf', path, context, activeRefs);
  normalized = normalizeSingleMemberComposition(normalized, 'oneOf', path, context, activeRefs);

  if (isSchemaNode(normalized.properties)) {
    normalized.properties = Object.fromEntries(
      Object.entries(normalized.properties).map(([name, property]) => [
        name,
        isSchemaNode(property)
          ? normalizeNode(property, `${path}.properties.${name}`, context, activeRefs)
          : property,
      ]),
    );
  }

  if (isSchemaNode(normalized.items)) {
    normalized.items = normalizeNode(normalized.items, `${path}.items`, context, activeRefs);
  }

  for (const definitionsKey of ['$defs', 'definitions'] as const) {
    if (definitionsKey in normalized) {
      delete normalized[definitionsKey];
      context.changes.push({
        path: `${path}.${definitionsKey}`,
        feature: definitionsKey,
        description: 'Removed definitions after inlining local references',
      });
    }
  }

  return normalized;
}

function normalizeSingleMemberComposition(
  node: SchemaNode,
  keyword: 'anyOf' | 'oneOf',
  path: string,
  context: NormalizationContext,
  resolvingRefs: ReadonlySet<string>,
): SchemaNode {
  const members = node[keyword];
  if (!Array.isArray(members) || members.length !== 1 || !isSchemaNode(members[0])) return node;

  const { [keyword]: _members, ...base } = node;
  context.changes.push({
    path,
    feature: keyword,
    description: `Collapsed a single-member ${keyword} composition`,
  });
  return mergeSchemas(
    normalizeNode(base, path, context, resolvingRefs),
    normalizeNode(members[0], `${path}.${keyword}[0]`, context, resolvingRefs),
    path,
    context,
  );
}

function mergeSchemas(
  base: SchemaNode,
  extension: SchemaNode,
  path: string,
  context: NormalizationContext,
): SchemaNode {
  const merged = structuredClone(base);

  for (const [key, extensionValue] of Object.entries(extension)) {
    if (!(key in merged)) {
      merged[key] = structuredClone(extensionValue);
      continue;
    }

    const baseValue = merged[key];
    if (sameJson(baseValue, extensionValue)) continue;

    if (key === 'description') {
      merged[key] = extensionValue;
      continue;
    }

    if (key === 'required' && Array.isArray(baseValue) && Array.isArray(extensionValue)) {
      merged[key] = [...new Set([...baseValue, ...extensionValue])];
      continue;
    }

    if (key === 'properties' && isSchemaNode(baseValue) && isSchemaNode(extensionValue)) {
      const properties = structuredClone(baseValue);
      for (const [name, property] of Object.entries(extensionValue)) {
        const existingProperty = properties[name];
        properties[name] =
          isSchemaNode(existingProperty) && isSchemaNode(property)
            ? mergeSchemas(existingProperty, property, `${path}.properties.${name}`, context)
            : structuredClone(property);
      }
      merged[key] = properties;
      continue;
    }

    if (key === 'minimum' && typeof baseValue === 'number' && typeof extensionValue === 'number') {
      merged[key] = Math.max(baseValue, extensionValue);
      continue;
    }

    if (
      (key === 'maximum' || key === 'minItems') &&
      typeof baseValue === 'number' &&
      typeof extensionValue === 'number'
    ) {
      merged[key] =
        key === 'maximum'
          ? Math.min(baseValue, extensionValue)
          : Math.max(baseValue, extensionValue);
      continue;
    }

    context.issues.push({
      path: `${path}.${key}`,
      feature: 'composition-conflict',
      description: `Composition assigns incompatible values to "${key}"`,
    });
  }

  return merged;
}

function resolveLocalReference(root: SchemaNode, reference: string): SchemaNode | null {
  let current: unknown = root;
  for (const encodedSegment of reference.slice(2).split('/')) {
    if (!isSchemaNode(current)) return null;
    const segment = encodedSegment.replace(/~1/g, '/').replace(/~0/g, '~');
    current = current[segment];
  }
  return isSchemaNode(current) ? current : null;
}

function referenceToPath(reference: string): string {
  return `$.${reference
    .slice(2)
    .split('/')
    .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
    .join('.')}`;
}

function sameJson(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function isSchemaNode(value: unknown): value is SchemaNode {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isJsonSchema(value: unknown): value is JsonSchema {
  return isSchemaNode(value) && value.type === 'object';
}
