// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Scope helpers for iteration variables.
 *
 * Provides `buildIterationScope` — a reusable scope provider implementation
 * for loop and datatable components. Components register this via the
 * `scopeProvider` hook on their ComponentDefinition.
 *
 * The engine collects scopes generically by walking ancestors and calling
 * each component's `scopeProvider` — no hard-coded component types here.
 */

import type { Node } from '../types/index.js';
import type { ScopeDeclaration, ScopeProviderContext } from './registry.js';

/**
 * Scope provider for iteration components (loop, datatable).
 *
 * Reads `itemAlias`, `indexAlias`, and `expression.raw` from node props.
 * Maps array item sub-paths to aliased paths and adds metadata fields.
 * Returns null if the expression is empty (no scope to provide).
 */
export function buildIterationScope(
  node: Node,
  ctx: ScopeProviderContext,
): ScopeDeclaration | null {
  const props = node.props;
  if (!props) return null;

  const expression = props.expression as { raw?: string } | undefined;
  const arrayExpression = expression?.raw ?? '';
  if (!arrayExpression) return null;

  const itemAlias = (props.itemAlias as string) || 'item';
  const indexAlias = props.indexAlias as string | undefined;

  const variables = buildIterationMetadata(itemAlias, indexAlias);
  const evaluationData = buildLoopEvaluationData(
    itemAlias,
    indexAlias,
    arrayExpression,
    ctx.evaluationContext,
  );

  return {
    variables,
    schemaBindings: [
      {
        alias: itemAlias,
        source: { kind: 'array-item-expression', expression: arrayExpression },
        scopeKind: 'iteration',
        description: 'Current iteration item',
        includeAlias: true,
      },
    ],
    evaluationData,
  };
}

/**
 * Build scoped FieldPath entries for an iteration component.
 *
 * Maps array item sub-paths to aliased paths and adds metadata fields.
 */
function buildIterationMetadata(
  itemAlias: string,
  indexAlias: string | undefined,
): ScopeDeclaration['variables'] {
  const paths: ScopeDeclaration['variables'] = [];

  // Add loop metadata fields
  paths.push(
    {
      path: `${itemAlias}_index`,
      type: 'integer',
      scope: itemAlias,
      scopeKind: 'iteration',
      description: 'Zero-based iteration index',
    },
    {
      path: `${itemAlias}_first`,
      type: 'boolean',
      scope: itemAlias,
      scopeKind: 'iteration',
      description: 'True for the first item',
    },
    {
      path: `${itemAlias}_last`,
      type: 'boolean',
      scope: itemAlias,
      scopeKind: 'iteration',
      description: 'True for the last item',
    },
  );

  if (indexAlias) {
    paths.push({
      path: indexAlias,
      type: 'integer',
      scope: itemAlias,
      scopeKind: 'iteration',
      description: 'Iteration index (custom alias)',
    });
  }

  return paths;
}

/**
 * Build evaluation data for an iteration component's scope.
 *
 * Resolves the array expression using simple path resolution, picks the first
 * item, and returns the loop variables as key-value pairs. For complex
 * expressions that can't be resolved, returns only metadata with defaults.
 */
function buildLoopEvaluationData(
  itemAlias: string,
  indexAlias: string | undefined,
  arrayExpression: string,
  evaluationContext?: Record<string, unknown>,
): Record<string, unknown> {
  const data: Record<string, unknown> = {};

  if (evaluationContext) {
    const array = resolveSimplePath(evaluationContext, arrayExpression);
    const items = Array.isArray(array) ? array : [];
    const firstItem = items[0] ?? undefined;

    if (firstItem !== undefined) {
      data[itemAlias] = firstItem;
    }

    data[`${itemAlias}_index`] = 0;
    data[`${itemAlias}_first`] = true;
    data[`${itemAlias}_last`] = items.length <= 1;

    if (indexAlias) {
      data[indexAlias] = 0;
    }
  }

  return data;
}

/**
 * Resolve a simple dot-notation path against a data object.
 * Returns undefined if any segment is missing.
 */
export function resolveSimplePath(data: Record<string, unknown>, dotPath: string): unknown {
  return dotPath.split('.').reduce<unknown>((obj, key) => {
    if (obj === null || obj === undefined || typeof obj !== 'object') return undefined;
    return (obj as Record<string, unknown>)[key];
  }, data);
}
