import type {ScopeVariable} from "../context/ScopeContext";

export interface PathInfo {
  path: string;
  isArray: boolean;
  type: string;
}

/**
 * Recursively extracts all possible paths from an object with type info.
 * Used for autocomplete suggestions in expression editor.
 */
export function extractPaths(obj: unknown, prefix = ""): PathInfo[] {
  const paths: PathInfo[] = [];

  if (obj === null || obj === undefined) {
    return paths;
  }

  if (Array.isArray(obj)) {
    // For arrays, show the array path and item paths with [0]
    paths.push({ path: prefix, isArray: true, type: `array[${obj.length}]` });
    if (obj.length > 0) {
      const itemPaths = extractPaths(obj[0], `${prefix}[0]`);
      paths.push(...itemPaths);
      // Also suggest .length, .map, .filter, etc.
      paths.push({ path: `${prefix}.length`, isArray: false, type: "number" });
    }
  } else if (typeof obj === "object") {
    for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
      const path = prefix ? `${prefix}.${key}` : key;
      const isArray = Array.isArray(value);
      const type = isArray ? `array[${value.length}]` : typeof value;
      paths.push({ path, isArray, type });
      paths.push(...extractPaths(value, path));
    }
  }

  return paths;
}

/**
 * Resolves a scope variable (from loops, etc.) to its actual value from test data.
 */
export function resolveScopeVariable(
  varName: string,
  scopeVars: ScopeVariable[],
  testData: Record<string, unknown>,
): unknown | undefined {
  const scopeVar = scopeVars.find((v) => v.name === varName);
  if (!scopeVar) return undefined;

  if (scopeVar.type === "loop-index") {
    return 0; // Preview with index 0
  }

  if (scopeVar.type === "loop-item") {
    // Get the first item from the array
    const arrayPath = scopeVar.arrayPath.split(".");
    let arrayValue: unknown = testData;
    for (const part of arrayPath) {
      arrayValue = (arrayValue as Record<string, unknown>)?.[part];
    }
    if (Array.isArray(arrayValue) && arrayValue.length > 0) {
      return arrayValue[0];
    }
  }

  return undefined;
}

/**
 * Builds an evaluation context by merging test data with resolved scope variables.
 */
export function buildEvaluationContext(
  data: Record<string, unknown>,
  scopeVars: ScopeVariable[],
): Record<string, unknown> {
  const context: Record<string, unknown> = { ...data };
  for (const scopeVar of scopeVars) {
    const resolved = resolveScopeVariable(scopeVar.name, scopeVars, data);
    if (resolved !== undefined) {
      context[scopeVar.name] = resolved;
    }
  }
  return context;
}

/**
 * Formats a value for preview display, truncating long objects.
 */
export function formatPreviewValue(value: unknown): string {
  if (value === undefined) return "undefined";
  if (value === null) return "null";
  if (typeof value === "object") {
    const json = JSON.stringify(value);
    return json.length > 50 ? json.slice(0, 50) + "..." : json;
  }
  return String(value);
}
