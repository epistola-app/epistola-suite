/**
 * JSONata expression evaluation and display formatting for expression chips.
 *
 * Used by ExpressionNodeView to resolve expression values from the currently
 * selected data example and format them for inline display.
 */

import jsonata from 'jsonata'

/**
 * Evaluate a JSONata expression against the given data.
 * Returns `undefined` on empty expression, evaluation error, or missing path.
 */
export async function evaluateExpression(
  expression: string,
  data: Record<string, unknown>,
): Promise<unknown> {
  const trimmed = expression.trim()
  if (!trimmed) return undefined

  try {
    const expr = jsonata(trimmed)
    return await expr.evaluate(data)
  } catch {
    return undefined
  }
}

/**
 * Format a resolved value for inline display in an expression chip.
 *
 * Returns `undefined` (= fall back to raw expression) for values that
 * aren't displayable inline: undefined, null, empty strings, objects, arrays.
 */
export function formatResolvedValue(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value === 'string') return value.length > 0 ? value : undefined
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  // Objects and arrays aren't displayable inline
  return undefined
}
