/**
 * Result of an expression evaluation
 */
export interface EvaluationResult {
  success: boolean;
  value?: unknown;
  error?: string;
}

/**
 * Context passed to the evaluator containing variables available in expressions
 */
export type EvaluationContext = Record<string, unknown>;

/**
 * Scope variable for loops (item, index)
 */
export interface ScopeVariable {
  name: string;
  type: "loop-item" | "loop-index";
  arrayPath: string;
}
