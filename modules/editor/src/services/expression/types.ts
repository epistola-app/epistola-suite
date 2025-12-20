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
 * Interface for expression evaluators
 * Allows different strategies for evaluating JavaScript expressions safely
 * All evaluation is async for consistent API across evaluators
 */
export interface ExpressionEvaluator {
  /**
   * Unique identifier for this evaluator type
   */
  readonly type: string;

  /**
   * Human-readable name for UI
   */
  readonly name: string;

  /**
   * Whether this evaluator runs in a sandbox (safer but potentially slower)
   */
  readonly isSandboxed: boolean;

  /**
   * Initialize the evaluator (e.g., create iframe, load WASM)
   * Must be called before evaluate()
   */
  initialize(): Promise<void>;

  /**
   * Evaluate a JavaScript expression with the given context
   * @param expression - JavaScript expression to evaluate
   * @param context - Variables available in the expression
   * @returns Promise resolving to the evaluation result
   */
  evaluate(expression: string, context: EvaluationContext): Promise<EvaluationResult>;

  /**
   * Clean up resources (e.g., remove iframe)
   */
  dispose(): void;
}

/**
 * Available evaluator types
 */
export type EvaluatorType = 'direct' | 'iframe';
