import type {ReactNode} from "react";
import {createContext, useCallback, useContext, useEffect, useMemo, useState} from "react";
import type {EvaluationContext, EvaluationResult, EvaluatorType, ExpressionEvaluator,} from "../services/expression";
import {DirectEvaluator, IframeEvaluator, JsonataEvaluator} from "../services/expression";
import type {ExpressionLanguage} from "../types/template";

interface EvaluatorContextValue {
  /** The current evaluator instance */
  evaluator: ExpressionEvaluator;
  /** Current evaluator type */
  type: EvaluatorType;
  /** Whether the evaluator is ready to use */
  isReady: boolean;
  /** Switch to a different evaluator type */
  setType: (type: EvaluatorType) => void;
  /** Evaluate an expression (async) */
  evaluate: (expression: string, context: EvaluationContext) => Promise<EvaluationResult>;
}

const EvaluatorContext = createContext<EvaluatorContextValue | null>(null);

interface EvaluatorProviderProps {
  children: ReactNode;
  /** Initial evaluator type (default: 'jsonata') */
  initialType?: EvaluatorType;
}

/**
 * Factory function to create evaluator instances
 */
function createEvaluator(type: EvaluatorType): ExpressionEvaluator {
  switch (type) {
    case "iframe":
      return new IframeEvaluator();
    case "jsonata":
      return new JsonataEvaluator();
    case "direct":
    default:
      return new DirectEvaluator();
  }
}

/**
 * Maps expression language to evaluator type
 */
export function languageToEvaluatorType(language?: ExpressionLanguage): EvaluatorType {
  switch (language) {
    case "javascript":
      return "direct"; // Use direct evaluator for JavaScript
    case "jsonata":
    default:
      return "jsonata"; // JSONata is the default
  }
}

export function EvaluatorProvider({ children, initialType = "jsonata" }: EvaluatorProviderProps) {
  const [type, setTypeState] = useState<EvaluatorType>(initialType);
  const [evaluator, setEvaluator] = useState<ExpressionEvaluator>(() =>
    createEvaluator(initialType),
  );
  const [isReady, setIsReady] = useState(false);

  // Initialize evaluator
  useEffect(() => {
    let disposed = false;

    async function init() {
      setIsReady(false);
      try {
        await evaluator.initialize();
        if (!disposed) {
          setIsReady(true);
        }
      } catch (error) {
        console.error("Failed to initialize evaluator:", error);
        // Fallback to JSONata evaluator if initialization fails (unless already JSONata)
        if (!disposed && evaluator.type !== "jsonata") {
          const fallback = new JsonataEvaluator();
          await fallback.initialize();
          setEvaluator(fallback);
          setTypeState("jsonata");
          setIsReady(true);
        }
      }
    }

    init();

    return () => {
      disposed = true;
      evaluator.dispose();
    };
  }, [evaluator]);

  // Switch evaluator type
  const setType = useCallback(
    (newType: EvaluatorType) => {
      if (newType === type) return;

      // Dispose old evaluator
      evaluator.dispose();

      // Create and set new evaluator
      const newEvaluator = createEvaluator(newType);
      setEvaluator(newEvaluator);
      setTypeState(newType);
    },
    [type, evaluator],
  );

  // Async evaluate
  const evaluate = useCallback(
    async (expression: string, context: EvaluationContext): Promise<EvaluationResult> => {
      if (!isReady) {
        return { success: false, error: "Evaluator not ready" };
      }
      return evaluator.evaluate(expression, context);
    },
    [evaluator, isReady],
  );

  const value = useMemo(
    () => ({
      evaluator,
      type,
      isReady,
      setType,
      evaluate,
    }),
    [evaluator, type, isReady, setType, evaluate],
  );

  return <EvaluatorContext.Provider value={value}>{children}</EvaluatorContext.Provider>;
}

/**
 * Hook to access the expression evaluator
 */
export function useEvaluator(): EvaluatorContextValue {
  const context = useContext(EvaluatorContext);
  if (!context) {
    throw new Error("useEvaluator must be used within an EvaluatorProvider");
  }
  return context;
}
