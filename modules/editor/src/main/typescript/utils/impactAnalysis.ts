import type { SchemaIssue, VisualSchema } from "../types/schema";
import { getSchemaFieldPaths } from "./schemaUtils";
import { pathMatchesSchema } from "./expressionUtils";

/**
 * Analyze the impact of schema on expressions.
 * Returns issues for expressions that don't match the schema.
 */
export function analyzeSchemaImpact(
  schema: VisualSchema,
  expressions: Set<string>,
): SchemaIssue[] {
  const issues: SchemaIssue[] = [];
  const schemaPaths = getSchemaFieldPaths(schema);

  for (const expr of expressions) {
    if (!pathMatchesSchema(expr, schemaPaths)) {
      issues.push({
        type: "missing",
        path: expr,
        message: `Expression "${expr}" is not defined in the schema`,
      });
    }
  }

  return issues;
}

/**
 * Compare old and new schemas to detect removed paths.
 * Returns issues for paths that were in the old schema but removed in the new one.
 */
export function detectRemovedPaths(
  oldSchema: VisualSchema,
  newSchema: VisualSchema,
  expressions: Set<string>,
): SchemaIssue[] {
  const issues: SchemaIssue[] = [];
  const oldPaths = getSchemaFieldPaths(oldSchema);
  const newPaths = getSchemaFieldPaths(newSchema);

  // Find paths that were removed
  const removedPaths = new Set<string>();
  for (const path of oldPaths) {
    if (!newPaths.has(path)) {
      removedPaths.add(path);
    }
  }

  // Check if any expressions use removed paths
  for (const expr of expressions) {
    if (pathMatchesSchema(expr, removedPaths)) {
      issues.push({
        type: "removed",
        path: expr,
        message: `Expression "${expr}" uses a field that was removed from the schema`,
      });
    }
  }

  return issues;
}

/**
 * Get a summary of expression coverage in the schema.
 */
export function getExpressionCoverage(
  schema: VisualSchema,
  expressions: Set<string>,
): {
  total: number;
  valid: number;
  missing: number;
  coverage: number;
} {
  const schemaPaths = getSchemaFieldPaths(schema);
  let valid = 0;
  let missing = 0;

  for (const expr of expressions) {
    if (pathMatchesSchema(expr, schemaPaths)) {
      valid++;
    } else {
      missing++;
    }
  }

  const total = expressions.size;
  const coverage = total > 0 ? Math.round((valid / total) * 100) : 100;

  return { total, valid, missing, coverage };
}
