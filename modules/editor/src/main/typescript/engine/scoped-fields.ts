/**
 * Scoped field resolution for loop/datatable iteration variables.
 *
 * When an expression chip is inside a loop or datatable, the builder should
 * show the scoped variables (e.g., `item.name`, `item_index`) in the field
 * dropdown. This module resolves those scoped fields by walking the document
 * tree to find ancestor loop/datatable nodes.
 */

import type { FieldPath } from './schema-paths.js';
import type { DocumentIndexes } from './indexes.js';
import type { NodeId, TemplateDocument } from '../types/index.js';

const LOOP_TYPES = new Set(['loop', 'datatable']);

/** Describes one loop/datatable scope and its available field paths. */
export interface ScopedFieldContext {
  /** The loop/datatable node ID that introduces this scope. */
  sourceNodeId: NodeId;
  /** The item alias (e.g., 'item'). */
  itemAlias: string;
  /** The index alias if configured (e.g., 'idx'). */
  indexAlias?: string;
  /** The raw array expression (e.g., 'items', 'order.lineItems'). */
  arrayExpression: string;
  /** Scoped FieldPath entries (item properties + metadata). */
  fieldPaths: FieldPath[];
}

/**
 * Resolve scoped field paths for a node by walking its ancestors.
 *
 * For each ancestor loop/datatable, maps the array item schema paths
 * (e.g., `items[].name`) to aliased paths (e.g., `item.name`) and adds
 * loop metadata fields (`item_index`, `item_first`, `item_last`).
 *
 * Returns contexts ordered outer-to-inner.
 */
export function resolveScopedFieldPaths(
  nodeId: NodeId,
  doc: TemplateDocument,
  indexes: DocumentIndexes,
  schemaFieldPaths: FieldPath[],
): ScopedFieldContext[] {
  const contexts: ScopedFieldContext[] = [];

  // Walk up from nodeId to root
  let current: NodeId | undefined = indexes.parentNodeByNodeId.get(nodeId);
  const ancestors: NodeId[] = [];
  while (current !== undefined) {
    ancestors.unshift(current); // collect in root-to-node order
    current = indexes.parentNodeByNodeId.get(current);
  }

  for (const ancestorId of ancestors) {
    const node = doc.nodes[ancestorId];
    if (!node || !LOOP_TYPES.has(node.type)) continue;

    const props = node.props;
    if (!props) continue;

    const expression = props.expression as { raw?: string } | undefined;
    const arrayExpression = expression?.raw ?? '';
    const itemAlias = (props.itemAlias as string) || 'item';
    const indexAlias = props.indexAlias as string | undefined;

    const fieldPaths = buildScopedFieldPaths(
      arrayExpression,
      itemAlias,
      indexAlias,
      schemaFieldPaths,
    );

    contexts.push({
      sourceNodeId: ancestorId,
      itemAlias,
      indexAlias: indexAlias || undefined,
      arrayExpression,
      fieldPaths,
    });
  }

  return contexts;
}

/**
 * Build scoped FieldPath entries for a single loop/datatable.
 *
 * Maps array item sub-paths to aliased paths and adds metadata fields.
 */
function buildScopedFieldPaths(
  arrayExpression: string,
  itemAlias: string,
  indexAlias: string | undefined,
  schemaFieldPaths: FieldPath[],
): FieldPath[] {
  const paths: FieldPath[] = [];
  const arrayPathPrefix = `${arrayExpression}[].`;

  // Map array item sub-properties to aliased paths
  for (const fp of schemaFieldPaths) {
    if (fp.path.startsWith(arrayPathPrefix)) {
      const subPath = fp.path.slice(arrayPathPrefix.length);
      paths.push({
        path: `${itemAlias}.${subPath}`,
        type: fp.type,
        scope: itemAlias,
      });
    }
  }

  // Add loop metadata fields
  paths.push(
    {
      path: `${itemAlias}_index`,
      type: 'integer',
      scope: itemAlias,
      description: 'Zero-based iteration index',
    },
    {
      path: `${itemAlias}_first`,
      type: 'boolean',
      scope: itemAlias,
      description: 'True for the first item',
    },
    {
      path: `${itemAlias}_last`,
      type: 'boolean',
      scope: itemAlias,
      description: 'True for the last item',
    },
  );

  if (indexAlias) {
    paths.push({
      path: indexAlias,
      type: 'integer',
      scope: itemAlias,
      description: 'Iteration index (custom alias)',
    });
  }

  return paths;
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

/**
 * Augment example data with synthetic loop context for live preview.
 *
 * For each scoped context, resolves the array expression using simple path
 * resolution, picks the first item, and injects the loop variables into the
 * data object. For complex expressions that can't be resolved via simple path,
 * injects metadata fields with default values but skips item properties.
 */
export function augmentWithLoopContext(
  data: Record<string, unknown>,
  scopedContexts: ScopedFieldContext[],
): Record<string, unknown> {
  const augmented = { ...data };

  for (const ctx of scopedContexts) {
    const array = resolveSimplePath(augmented, ctx.arrayExpression);
    const items = Array.isArray(array) ? array : [];
    const firstItem = items[0] ?? undefined;

    if (firstItem !== undefined) {
      augmented[ctx.itemAlias] = firstItem;
    }

    augmented[`${ctx.itemAlias}_index`] = 0;
    augmented[`${ctx.itemAlias}_first`] = true;
    augmented[`${ctx.itemAlias}_last`] = items.length <= 1;

    if (ctx.indexAlias) {
      augmented[ctx.indexAlias] = 0;
    }
  }

  return augmented;
}
