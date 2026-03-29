/**
 * Pure functions for rewriting alias references in expressions.
 *
 * When a loop/datatable's `itemAlias` changes (e.g., `item` → `row`),
 * expression chips inside the loop need their references updated.
 * These functions handle the string-level rewriting.
 */

/**
 * Rewrite alias references in a single expression string.
 *
 * Handles: `item.name` → `row.name`, `item_index` → `row_index`,
 * `$formatDate(item.date, ...)` → `$formatDate(row.date, ...)`,
 * bare `item` → `row`.
 *
 * Does NOT match `items` when alias is `item` (requires boundary after alias).
 */
export function rewriteAliasInExpression(
  expression: string,
  oldAlias: string,
  newAlias: string,
): string {
  if (!oldAlias || !newAlias || oldAlias === newAlias) return expression;

  // Match oldAlias when followed by '.', '_', ',', ')', whitespace, or end of string.
  // This prevents matching 'items' when alias is 'item'.
  const escaped = oldAlias.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(`${escaped}(?=[._,)\\s]|$)`, 'g');
  return expression.replace(pattern, newAlias);
}

/**
 * Walk ProseMirror JSON content and rewrite expression node attrs.
 *
 * Returns the same reference if no changes were made (for cheap equality check).
 */
export function rewriteExpressionsInContent(
  content: unknown,
  oldAlias: string,
  newAlias: string,
): unknown {
  if (!content || typeof content !== 'object') return content;
  return rewriteNode(content as Record<string, unknown>, oldAlias, newAlias);
}

function rewriteNode(
  node: Record<string, unknown>,
  oldAlias: string,
  newAlias: string,
): Record<string, unknown> {
  let changed = false;

  // Rewrite expression attrs
  if (node.type === 'expression' && node.attrs) {
    const attrs = node.attrs as Record<string, unknown>;
    const expr = attrs.expression;
    if (typeof expr === 'string') {
      const rewritten = rewriteAliasInExpression(expr, oldAlias, newAlias);
      if (rewritten !== expr) {
        return { ...node, attrs: { ...attrs, expression: rewritten } };
      }
    }
  }

  // Recurse into content array
  if (Array.isArray(node.content)) {
    const newContent: unknown[] = [];
    for (const child of node.content) {
      if (child && typeof child === 'object') {
        const rewritten = rewriteNode(child as Record<string, unknown>, oldAlias, newAlias);
        if (rewritten !== child) changed = true;
        newContent.push(rewritten);
      } else {
        newContent.push(child);
      }
    }
    if (changed) {
      return { ...node, content: newContent };
    }
  }

  return node;
}
