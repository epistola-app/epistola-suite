import type { ExpressionEvaluator, EvaluationContext, EvaluationResult } from './types';

/**
 * Direct evaluator using new Function()
 *
 * Fast but NOT sandboxed.
 * Expressions have access to the window object via indirect references.
 *
 * Use this for:
 * - Development/preview where speed matters
 * - Trusted internal users
 *
 * Do NOT use for:
 * - Untrusted user input
 * - Production PDF generation with external data
 */
export class DirectEvaluator implements ExpressionEvaluator {
  readonly type = 'direct';
  readonly name = 'Direct (Fast, Unsandboxed)';
  readonly isSandboxed = false;

  async initialize(): Promise<void> {
    // No initialization needed
  }

  async evaluate(expression: string, context: EvaluationContext): Promise<EvaluationResult> {
    const trimmed = expression.trim();

    if (!trimmed) {
      return { success: false, error: 'Expression cannot be empty' };
    }

    try {
      const keys = Object.keys(context);
      const values = Object.values(context);
      // eslint-disable-next-line @typescript-eslint/no-implied-eval
      const fn = new Function(...keys, `return ${trimmed}`);
      const result = fn(...values);
      return { success: true, value: result };
    } catch (e) {
      const error = e instanceof Error ? e.message : 'Unknown error';
      return { success: false, error };
    }
  }

  dispose(): void {
    // Nothing to clean up
  }
}
