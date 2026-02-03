/**
 * Expression Evaluator Module
 *
 * Provides JSONata-based expression evaluation for template preview.
 */

export type { EvaluationResult, EvaluationContext, ScopeVariable } from "./types.js";

export {
  evaluateJsonata,
  evaluateJsonataBoolean,
  evaluateJsonataArray,
  evaluateJsonataString,
} from "./jsonata-evaluator.js";
