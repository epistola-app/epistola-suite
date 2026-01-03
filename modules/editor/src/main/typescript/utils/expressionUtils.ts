import type { JSONContent } from "@tiptap/react";
import type { Block, TextBlock, ConditionalBlock, LoopBlock, ColumnsBlock, TableBlock } from "../types/template";

/**
 * Extract all expression paths used in template blocks.
 * Traverses all block types and their nested content.
 */
export function extractExpressions(blocks: Block[]): Set<string> {
  const expressions = new Set<string>();

  function processBlocks(blockList: Block[]) {
    for (const block of blockList) {
      processBlock(block);
    }
  }

  function processBlock(block: Block) {
    switch (block.type) {
      case "text":
        extractFromTipTapContent((block as TextBlock).content, expressions);
        break;

      case "conditional": {
        const cond = block as ConditionalBlock;
        // Extract from condition expression
        if (cond.condition?.raw) {
          extractPathsFromExpression(cond.condition.raw, expressions);
        }
        // Process children
        if (cond.children) {
          processBlocks(cond.children);
        }
        break;
      }

      case "loop": {
        const loop = block as LoopBlock;
        // Extract from loop expression
        if (loop.expression?.raw) {
          extractPathsFromExpression(loop.expression.raw, expressions);
        }
        // Process children (they may use item alias, but we extract raw paths)
        if (loop.children) {
          processBlocks(loop.children);
        }
        break;
      }

      case "container":
        if ("children" in block && Array.isArray(block.children)) {
          processBlocks(block.children);
        }
        break;

      case "columns": {
        const cols = block as ColumnsBlock;
        for (const column of cols.columns) {
          if (column.children) {
            processBlocks(column.children);
          }
        }
        break;
      }

      case "table": {
        const table = block as TableBlock;
        for (const row of table.rows) {
          for (const cell of row.cells) {
            if (cell.children) {
              processBlocks(cell.children);
            }
          }
        }
        break;
      }
      default:
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        console.warn(`Unknown block type: ${(block as any).type}`);
        break;
    }
  }

  processBlocks(blocks);
  return expressions;
}

/**
 * Extract expression paths from TipTap JSON content.
 * Looks for expression nodes with data-expression attribute.
 */
function extractFromTipTapContent(content: JSONContent | null | undefined, expressions: Set<string>) {
  if (!content) return;

  // Check if this is an expression node
  if (content.type === "expression" && content.attrs?.expression) {
    extractPathsFromExpression(content.attrs.expression, expressions);
  }

  // Recursively process content array
  if (content.content && Array.isArray(content.content)) {
    for (const child of content.content) {
      extractFromTipTapContent(child, expressions);
    }
  }
}

/**
 * Extract data paths from an expression string.
 * Handles JSONata syntax (e.g., customer.name, items.price, items[0].name).
 */
function extractPathsFromExpression(expression: string, expressions: Set<string>) {
  if (!expression || typeof expression !== "string") return;

  const trimmed = expression.trim();
  if (!trimmed) return;

  // Extract path-like patterns from the expression
  // Match potential path-like patterns: word(.word)+ or word[number](.word)+
  const pathPattern =
    /\b([a-zA-Z_][a-zA-Z0-9_]*(?:\s*\[\s*\d*\s*\])?(?:\s*\.\s*[a-zA-Z_][a-zA-Z0-9_]*(?:\s*\[\s*\d*\s*\])?)*)/g;

  let match = pathPattern.exec(trimmed);
  while (match !== null) {
    const path = match[1].replace(/\s+/g, ""); // Remove whitespace
    if (path) {
      expressions.add(normalizeArrayPath(path));
    }
    match = pathPattern.exec(trimmed);
  }
}

/**
 * Normalize array access patterns in paths.
 * items[0].price -> items[].price
 * items[123].name -> items[].name
 */
export function normalizeArrayPath(path: string): string {
  // Replace [number] with [] to normalize array access
  return path.replace(/\[\s*\d+\s*\]/g, "[]");
}

/**
 * Get all unique root-level paths from expressions.
 * E.g., customer.name -> customer
 */
export function getRootPaths(expressions: Set<string>): Set<string> {
  const roots = new Set<string>();
  for (const expr of expressions) {
    const parts = expr.split(/[.\[]/);
    if (parts[0]) {
      roots.add(parts[0]);
    }
  }
  return roots;
}

/**
 * Check if a path matches a schema path (considering array normalization).
 * Expression: items[].price matches schema: items[].price
 * Expression: items.price matches schema: items[].price (items is an array)
 */
export function pathMatchesSchema(expressionPath: string, schemaPaths: Set<string>): boolean {
  // Exact match
  if (schemaPaths.has(expressionPath)) {
    return true;
  }

  // Check if any schema path is a prefix (for nested paths)
  const expressionParts = expressionPath.split(".");
  for (let i = expressionParts.length; i >= 1; i--) {
    const partial = expressionParts.slice(0, i).join(".");
    if (schemaPaths.has(partial)) {
      return true;
    }
    // Also check with array notation
    const partialWithArray = partial.replace(/\[\]$/, "") + "[]";
    if (schemaPaths.has(partialWithArray)) {
      return true;
    }
  }

  return false;
}
