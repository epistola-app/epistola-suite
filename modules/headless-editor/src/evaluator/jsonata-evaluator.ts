/**
 * JSONata Expression Evaluator
 *
 * Wraps the jsonata library for evaluating expressions against JSON data.
 * JSONata is a lightweight query and transformation language for JSON.
 *
 * @see https://jsonata.org
 */

import jsonata from "jsonata";
import type { EvaluationContext, EvaluationResult } from "./types.js";

/**
 * Evaluate a JSONata expression against a context
 *
 * @param expression - JSONata expression string
 * @param context - Data context for evaluation
 * @returns Promise resolving to evaluation result with success/error status
 */
export async function evaluateJsonata(
  expression: string,
  context: EvaluationContext
): Promise<EvaluationResult> {
  const trimmed = expression.trim();
  if (!trimmed) {
    return { success: true, value: undefined };
  }

  try {
    const expr = jsonata(trimmed);
    const result = await expr.evaluate(context);
    return { success: true, value: result };
  } catch (error) {
    return {
      success: false,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

/**
 * Evaluate expression and coerce to boolean
 * Empty arrays are treated as falsy (unlike JavaScript's default behavior)
 */
export async function evaluateJsonataBoolean(
  expression: string,
  context: EvaluationContext
): Promise<boolean> {
  const result = await evaluateJsonata(expression, context);
  if (!result.success) return false;
  // Treat empty arrays as falsy (more intuitive for templates)
  if (Array.isArray(result.value) && result.value.length === 0) return false;
  return Boolean(result.value);
}

/**
 * Evaluate expression and return array (or empty array if not an array)
 */
export async function evaluateJsonataArray(
  expression: string,
  context: EvaluationContext
): Promise<unknown[]> {
  const result = await evaluateJsonata(expression, context);
  if (!result.success) return [];
  if (!Array.isArray(result.value)) return [];
  return result.value;
}

/**
 * Evaluate expression and coerce to string
 */
export async function evaluateJsonataString(
  expression: string,
  context: EvaluationContext
): Promise<string> {
  const result = await evaluateJsonata(expression, context);
  if (!result.success) return "";
  if (result.value === undefined || result.value === null) return "";
  return String(result.value);
}
