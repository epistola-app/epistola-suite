// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import type { ScopeDeclaration } from './registry.js';
import type { FieldPath } from './schema-paths.js';
import { projectScopedFieldPaths } from './schema-paths.js';
import {
  type SchemaBindings,
  type SchemaCursor,
  itemCursor,
  resolveSchemaExpression,
  schemaRootCursor,
} from './schema-navigator.js';

export interface SchemaScopeEnvironment {
  dataRoot?: SchemaCursor;
  bindings: SchemaBindings;
}

export interface MaterializedScope {
  variables: FieldPath[];
  environment: SchemaScopeEnvironment;
}

/** Resolve declarative schema bindings and turn them into picker fields. */
export function materializeScopeDeclaration(
  declaration: ScopeDeclaration,
  environment: SchemaScopeEnvironment,
): MaterializedScope {
  const bindings: Record<string, SchemaCursor> = { ...environment.bindings };
  const schemaVariables: FieldPath[] = [];

  for (const binding of declaration.schemaBindings ?? []) {
    const expressionCursor =
      binding.source.kind === 'array-item-expression'
        ? resolveSchemaExpression(binding.source.expression, environment.dataRoot, bindings)
        : null;
    const cursor =
      binding.source.kind === 'schema-root'
        ? schemaRootCursor(binding.source.schema)
        : expressionCursor
          ? itemCursor(expressionCursor)
          : null;

    if (cursor) {
      bindings[binding.alias] = cursor;
      schemaVariables.push(
        ...projectScopedFieldPaths(cursor, {
          alias: binding.alias,
          scopeKind: binding.scopeKind,
          description: binding.description,
          includeAlias: binding.includeAlias,
        }),
      );
    } else if (binding.includeAlias) {
      schemaVariables.push({
        path: binding.alias,
        type: 'unknown',
        scope: binding.alias,
        scopeKind: binding.scopeKind,
        description: binding.description,
      });
    }
  }

  return {
    variables: [...schemaVariables, ...declaration.variables],
    environment: { ...environment, bindings },
  };
}
