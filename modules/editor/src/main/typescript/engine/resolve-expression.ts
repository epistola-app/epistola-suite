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

// ---------------------------------------------------------------------------
// Dialog-oriented helpers
// ---------------------------------------------------------------------------

/** Discriminated result type for expression evaluation. */
export type ExpressionResult =
  | { ok: true; value: unknown }
  | { ok: false; error: string }

/**
 * Evaluate a JSONata expression and return a discriminated result.
 * Unlike `evaluateExpression`, distinguishes parse errors from missing paths
 * so the dialog can show meaningful feedback.
 */
export async function tryEvaluateExpression(
  expression: string,
  data: Record<string, unknown>,
): Promise<ExpressionResult> {
  const trimmed = expression.trim()
  if (!trimmed) return { ok: false, error: 'Expression is empty' }

  try {
    const expr = jsonata(trimmed)
    const value = await expr.evaluate(data)
    return { ok: true, value }
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : String(e)
    return { ok: false, error: message }
  }
}

const FORMAT_PREVIEW_MAX_LENGTH = 120

/**
 * Format a value for the dialog preview panel.
 *
 * Unlike `formatResolvedValue` (which returns `undefined` for non-inline types),
 * this always returns a human-readable string — including for objects, arrays,
 * undefined, null, and empty strings.
 */
export function formatForPreview(value: unknown): string {
  if (value === undefined) return 'undefined'
  if (value === null) return 'null'
  if (typeof value === 'string') return value.length === 0 ? '(empty string)' : value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)

  // Objects and arrays — show truncated JSON
  try {
    const json = JSON.stringify(value)
    if (json.length > FORMAT_PREVIEW_MAX_LENGTH) {
      return json.slice(0, FORMAT_PREVIEW_MAX_LENGTH) + '…'
    }
    return json
  } catch {
    return String(value)
  }
}

/**
 * Validates that an evaluated expression result is an array.
 * Intended as a `resultValidator` for loop expression dialogs.
 *
 * Returns an error message if the value is not an array, null if valid.
 * `undefined` results (missing path) are not flagged since the expression
 * may be valid with different data.
 *
 * Note: JSONata unwraps single-result filters (e.g., `items[x=1]` returns
 * the object directly when only one item matches). We still flag this since
 * the loop expression should consistently return an array.
 */
export function validateArrayResult(value: unknown): string | null {
  if (value === undefined) return null
  if (Array.isArray(value)) return null
  const type = value === null ? 'null' : typeof value
  return `Loop expression must evaluate to an array, got ${type}: ${formatForPreview(value)}`
}

/**
 * Validates that an evaluated expression result is a boolean.
 * Intended as a `resultValidator` for conditional expression dialogs.
 *
 * Returns an error message if the value is not a boolean, null if valid.
 * `undefined` results (missing path) are not flagged since the expression
 * may be valid with different data.
 */
export function validateBooleanResult(value: unknown): string | null {
  if (value === undefined) return null
  if (typeof value === 'boolean') return null
  const type = value === null ? 'null' : typeof value
  return `Condition must evaluate to a boolean, got ${type}: ${formatForPreview(value)}`
}

/**
 * Synchronous parse-only check. Returns `true` if the expression is
 * syntactically valid JSONata (does NOT evaluate it).
 *
 * `jsonata(expr)` is synchronous and only parses — `.evaluate()` is the
 * async part. This gives instant red/green feedback without waiting for
 * data evaluation.
 */
export function isValidExpression(expression: string): boolean {
  const trimmed = expression.trim()
  if (!trimmed) return false
  try {
    jsonata(trimmed)
    return true
  } catch {
    return false
  }
}
