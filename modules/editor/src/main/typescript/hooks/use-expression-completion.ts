import { useMemo } from "react";
import type { CompletionContext, CompletionResult, Completion } from "@codemirror/autocomplete";
import type { ScopeVariable } from "../context/ScopeContext";
import {
  inferType,
  getMethodsForType,
  parsePath,
  resolvePathType,
  formatTypeForDisplay,
  type InferredType,
} from "../lib/type-inference";

interface CompletionOptions {
  testData: Record<string, unknown>;
  scopeVars: ScopeVariable[];
}

/**
 * Extract the path being typed before the cursor.
 * e.g., "customer.orders[0]." -> ["customer", "orders", "[0]"]
 * e.g., "customer.name.toLowerCase()." -> ["customer", "name", "toLowerCase()"]
 * Returns { path, partial } where partial is the incomplete segment being typed.
 */
function extractPathAtCursor(text: string): { path: string[]; partial: string } {
  // Find the start of the current expression (skip whitespace, operators, etc.)
  let start = text.length - 1;
  while (start >= 0) {
    const char = text[start];
    // Valid identifier chars, dots, brackets, and parentheses for method calls
    if (/[\w.\[\]()]/.test(char)) {
      start--;
    } else {
      break;
    }
  }
  start++;

  const expr = text.slice(start);
  if (!expr) {
    return { path: [], partial: "" };
  }

  // Check if ends with dot (property access)
  if (expr.endsWith(".")) {
    const pathStr = expr.slice(0, -1);
    return { path: parsePath(pathStr), partial: "" };
  }

  // Otherwise, split into path and partial
  const segments = parsePath(expr);
  if (segments.length === 0) {
    return { path: [], partial: "" };
  }

  // Last segment is what's being typed
  const partial = segments[segments.length - 1];
  const path = segments.slice(0, -1);

  return { path, partial };
}

/**
 * Build completions for top-level identifiers (data keys + scope variables).
 */
function buildTopLevelCompletions(
  testData: Record<string, unknown>,
  scopeVars: ScopeVariable[],
  filter: string,
): Completion[] {
  const completions: Completion[] = [];

  // Add scope variables first (higher priority)
  for (const scopeVar of scopeVars) {
    if (!filter || scopeVar.name.toLowerCase().startsWith(filter.toLowerCase())) {
      completions.push({
        label: scopeVar.name,
        type: "variable",
        detail: scopeVar.type === "loop-index" ? "loop index" : `loop item from ${scopeVar.arrayPath}`,
        boost: 10,
      });
    }
  }

  // Add data properties
  for (const [key, value] of Object.entries(testData)) {
    if (!filter || key.toLowerCase().startsWith(filter.toLowerCase())) {
      const type = inferType(value);
      completions.push({
        label: key,
        type: type.kind === "array" ? "variable" : "property",
        detail: formatTypeForDisplay(type),
        boost: 5,
      });
    }
  }

  return completions;
}

/**
 * Build completions for a resolved type (object properties or built-in methods).
 */
function buildTypeCompletions(type: InferredType, filter: string): Completion[] {
  const completions: Completion[] = [];

  // Add built-in methods for the type
  const methods = getMethodsForType(type);
  for (const method of methods) {
    if (!filter || method.label.toLowerCase().startsWith(filter.toLowerCase())) {
      completions.push({
        label: method.label,
        type: method.type,
        detail: method.detail,
        info: method.signature,
        boost: method.type === "property" ? 8 : 5,
        // Auto-add () for methods
        apply: method.type === "method" ? `${method.label}()` : method.label,
      });
    }
  }

  // If it's an object, add its properties
  if (type.kind === "object") {
    for (const [key, propType] of Object.entries(type.properties)) {
      if (!filter || key.toLowerCase().startsWith(filter.toLowerCase())) {
        completions.push({
          label: key,
          type: "property",
          detail: formatTypeForDisplay(propType),
          boost: 10, // Properties get higher priority than methods
        });
      }
    }
  }

  // If it's an array, show element access suggestion
  if (type.kind === "array") {
    completions.push({
      label: "[0]",
      type: "property",
      detail: `access ${formatTypeForDisplay(type.elementType)}`,
      boost: 15,
    });
  }

  return completions;
}

/**
 * Create a CodeMirror completion source for expression editing.
 */
export function createExpressionCompletionSource(
  testData: Record<string, unknown>,
  scopeVars: ScopeVariable[],
): (context: CompletionContext) => CompletionResult | null {
  return (context: CompletionContext): CompletionResult | null => {
    // Get the text before the cursor
    const line = context.state.doc.lineAt(context.pos);
    const textBefore = line.text.slice(0, context.pos - line.from);

    // Extract the path and partial identifier
    const { path, partial } = extractPathAtCursor(textBefore);

    // Determine from position for replacement
    const from = context.pos - partial.length;

    if (path.length === 0) {
      // Top-level completion
      const options = buildTopLevelCompletions(testData, scopeVars, partial);
      if (options.length === 0 && !partial) return null;

      return {
        from,
        options,
        validFor: /^[\w]*$/,
      };
    }

    // Nested path - resolve the type and show appropriate completions
    const type = resolvePathType(path, testData, scopeVars);

    // If type is unknown and we have no filter, don't show completions
    if (type.kind === "unknown" && !partial) {
      return null;
    }

    const options = buildTypeCompletions(type, partial);
    if (options.length === 0) return null;

    return {
      from,
      options,
      validFor: /^[\w\[\]]*$/,
    };
  };
}

/**
 * Hook to create a memoized completion source.
 */
export function useExpressionCompletion(options: CompletionOptions) {
  const { testData, scopeVars } = options;

  return useMemo(
    () => createExpressionCompletionSource(testData, scopeVars),
    [testData, scopeVars],
  );
}
