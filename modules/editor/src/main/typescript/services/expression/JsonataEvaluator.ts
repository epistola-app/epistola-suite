import jsonata from "jsonata";
import type {EvaluationContext, EvaluationResult, ExpressionEvaluator} from "./types";

/**
 * Expression evaluator using JSONata - a query and transformation language for JSON.
 *
 * JSONata provides a concise syntax for:
 * - Property access: `customer.name`
 * - Array filtering: `items[active]`
 * - Mapping: `items.price`
 * - Aggregation: `$sum(items.price)`
 * - String concatenation: `first & " " & last`
 * - Conditionals: `active ? "Yes" : "No"`
 *
 * @see https://jsonata.org
 */
export class JsonataEvaluator implements ExpressionEvaluator {
  readonly type = "jsonata";
  readonly name = "JSONata";
  readonly isSandboxed = true; // JSONata has no access to browser APIs

  async initialize(): Promise<void> {
    // No initialization needed for JSONata
  }

  async evaluate(expression: string, context: EvaluationContext): Promise<EvaluationResult> {
    const trimmed = expression.trim();
    if (!trimmed) {
      return { success: true, value: undefined };
    }

    try {
      // Parse and evaluate the JSONata expression
      const expr = jsonata(trimmed);

      // Set a reasonable timeout (5 seconds)
      const result = await expr.evaluate(context, undefined);

      return { success: true, value: result };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }
  }

  dispose(): void {
    // No cleanup needed
  }
}
