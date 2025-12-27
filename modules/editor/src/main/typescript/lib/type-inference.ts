import type { ScopeVariable } from "../context/ScopeContext";

/**
 * Inferred type from runtime value inspection.
 */
export type InferredType =
  | { kind: "primitive"; type: "string" | "number" | "boolean" | "null" | "undefined" }
  | { kind: "array"; elementType: InferredType }
  | { kind: "object"; properties: Record<string, InferredType> }
  | { kind: "unknown" };

/**
 * Method/property suggestion for autocomplete.
 */
export interface MethodSuggestion {
  label: string;
  type: "method" | "property";
  signature: string;
  detail: string;
}

export const STRING_METHODS: MethodSuggestion[] = [
  { label: "toUpperCase", type: "method", signature: "(): string", detail: "Converts to uppercase" },
  { label: "toLowerCase", type: "method", signature: "(): string", detail: "Converts to lowercase" },
  { label: "trim", type: "method", signature: "(): string", detail: "Removes whitespace" },
  { label: "trimStart", type: "method", signature: "(): string", detail: "Removes leading whitespace" },
  { label: "trimEnd", type: "method", signature: "(): string", detail: "Removes trailing whitespace" },
  { label: "split", type: "method", signature: "(sep): string[]", detail: "Splits into array" },
  { label: "slice", type: "method", signature: "(start, end?): string", detail: "Extracts section" },
  { label: "substring", type: "method", signature: "(start, end?): string", detail: "Extracts substring" },
  { label: "substr", type: "method", signature: "(start, length?): string", detail: "Extracts substring" },
  { label: "replace", type: "method", signature: "(search, replace): string", detail: "Replaces first match" },
  { label: "replaceAll", type: "method", signature: "(search, replace): string", detail: "Replaces all matches" },
  { label: "includes", type: "method", signature: "(search): boolean", detail: "Checks if contains" },
  { label: "startsWith", type: "method", signature: "(search): boolean", detail: "Checks start" },
  { label: "endsWith", type: "method", signature: "(search): boolean", detail: "Checks end" },
  { label: "indexOf", type: "method", signature: "(search): number", detail: "Finds first index" },
  { label: "lastIndexOf", type: "method", signature: "(search): number", detail: "Finds last index" },
  { label: "charAt", type: "method", signature: "(index): string", detail: "Gets character at index" },
  { label: "charCodeAt", type: "method", signature: "(index): number", detail: "Gets char code at index" },
  { label: "concat", type: "method", signature: "(...strings): string", detail: "Concatenates strings" },
  { label: "padStart", type: "method", signature: "(length, pad?): string", detail: "Pads start" },
  { label: "padEnd", type: "method", signature: "(length, pad?): string", detail: "Pads end" },
  { label: "repeat", type: "method", signature: "(count): string", detail: "Repeats string" },
  { label: "match", type: "method", signature: "(regex): string[]", detail: "Matches regex" },
  { label: "search", type: "method", signature: "(regex): number", detail: "Searches regex" },
  { label: "normalize", type: "method", signature: "(form?): string", detail: "Unicode normalize" },
  { label: "length", type: "property", signature: ": number", detail: "String length" },
];

export const ARRAY_METHODS: MethodSuggestion[] = [
  { label: "map", type: "method", signature: "(fn): array", detail: "Transform elements" },
  { label: "filter", type: "method", signature: "(fn): array", detail: "Filter elements" },
  { label: "find", type: "method", signature: "(fn): element", detail: "Find first match" },
  { label: "findIndex", type: "method", signature: "(fn): number", detail: "Find index of match" },
  { label: "findLast", type: "method", signature: "(fn): element", detail: "Find last match" },
  { label: "findLastIndex", type: "method", signature: "(fn): number", detail: "Find last index" },
  { label: "reduce", type: "method", signature: "(fn, init): any", detail: "Reduce to value" },
  { label: "reduceRight", type: "method", signature: "(fn, init): any", detail: "Reduce from right" },
  { label: "some", type: "method", signature: "(fn): boolean", detail: "Test if any matches" },
  { label: "every", type: "method", signature: "(fn): boolean", detail: "Test if all match" },
  { label: "includes", type: "method", signature: "(value): boolean", detail: "Check if contains" },
  { label: "indexOf", type: "method", signature: "(value): number", detail: "Find first index" },
  { label: "lastIndexOf", type: "method", signature: "(value): number", detail: "Find last index" },
  { label: "join", type: "method", signature: "(separator): string", detail: "Join to string" },
  { label: "slice", type: "method", signature: "(start, end?): array", detail: "Extract section" },
  { label: "concat", type: "method", signature: "(...arrays): array", detail: "Concatenate arrays" },
  { label: "flat", type: "method", signature: "(depth?): array", detail: "Flatten nested arrays" },
  { label: "flatMap", type: "method", signature: "(fn): array", detail: "Map then flatten" },
  { label: "at", type: "method", signature: "(index): element", detail: "Get element at index" },
  { label: "forEach", type: "method", signature: "(fn): void", detail: "Execute for each" },
  { label: "sort", type: "method", signature: "(fn?): array", detail: "Sort elements" },
  { label: "reverse", type: "method", signature: "(): array", detail: "Reverse order" },
  { label: "toReversed", type: "method", signature: "(): array", detail: "Copy and reverse" },
  { label: "toSorted", type: "method", signature: "(fn?): array", detail: "Copy and sort" },
  { label: "length", type: "property", signature: ": number", detail: "Array length" },
];

export const NUMBER_METHODS: MethodSuggestion[] = [
  { label: "toFixed", type: "method", signature: "(digits): string", detail: "Format decimals" },
  { label: "toPrecision", type: "method", signature: "(digits): string", detail: "Format precision" },
  { label: "toString", type: "method", signature: "(radix?): string", detail: "Convert to string" },
  { label: "toLocaleString", type: "method", signature: "(locale?): string", detail: "Locale format" },
  { label: "toExponential", type: "method", signature: "(digits?): string", detail: "Exponential format" },
];

export const BOOLEAN_METHODS: MethodSuggestion[] = [
  { label: "toString", type: "method", signature: "(): string", detail: "Convert to string" },
];

/**
 * Infer the type of a runtime value.
 */
export function inferType(value: unknown): InferredType {
  if (value === null) return { kind: "primitive", type: "null" };
  if (value === undefined) return { kind: "primitive", type: "undefined" };
  if (typeof value === "string") return { kind: "primitive", type: "string" };
  if (typeof value === "number") return { kind: "primitive", type: "number" };
  if (typeof value === "boolean") return { kind: "primitive", type: "boolean" };

  if (Array.isArray(value)) {
    return {
      kind: "array",
      elementType: value.length > 0 ? inferType(value[0]) : { kind: "unknown" },
    };
  }

  if (typeof value === "object") {
    const properties: Record<string, InferredType> = {};
    for (const [key, val] of Object.entries(value)) {
      properties[key] = inferType(val);
    }
    return { kind: "object", properties };
  }

  return { kind: "unknown" };
}

/**
 * Get method suggestions for a given type.
 */
export function getMethodsForType(type: InferredType): MethodSuggestion[] {
  if (type.kind === "primitive") {
    switch (type.type) {
      case "string":
        return STRING_METHODS;
      case "number":
        return NUMBER_METHODS;
      case "boolean":
        return BOOLEAN_METHODS;
      default:
        return [];
    }
  }

  if (type.kind === "array") {
    return ARRAY_METHODS;
  }

  return [];
}

/**
 * Parse a path like "customer.orders[0].name" or "customer.name.toLowerCase()" into segments.
 * Method calls are preserved with their () suffix.
 */
export function parsePath(pathStr: string): string[] {
  const segments: string[] = [];
  let current = "";
  let parenDepth = 0;

  for (let i = 0; i < pathStr.length; i++) {
    const char = pathStr[i];

    if (char === "(") {
      current += char;
      parenDepth++;
    } else if (char === ")") {
      current += char;
      parenDepth--;
    } else if (char === "." && parenDepth === 0) {
      if (current) {
        segments.push(current);
        current = "";
      }
    } else if (char === "[" && parenDepth === 0) {
      if (current) {
        segments.push(current);
        current = "";
      }
      // Read until ]
      let bracket = "";
      i++;
      while (i < pathStr.length && pathStr[i] !== "]") {
        bracket += pathStr[i];
        i++;
      }
      segments.push(`[${bracket}]`);
    } else {
      current += char;
    }
  }

  if (current) {
    segments.push(current);
  }

  return segments;
}

/**
 * Check if a segment is a method call (ends with "()" or "(...)")
 */
export function isMethodCall(segment: string): boolean {
  return segment.endsWith(")") && segment.includes("(");
}

/**
 * Get the method name from a method call segment.
 * e.g., "toLowerCase()" -> "toLowerCase"
 */
export function getMethodName(segment: string): string {
  const parenIndex = segment.indexOf("(");
  return parenIndex >= 0 ? segment.slice(0, parenIndex) : segment;
}

/**
 * Infer the return type of a method call on a given type.
 */
export function inferMethodReturnType(methodName: string, calledOnType: InferredType): InferredType {
  // String methods
  if (calledOnType.kind === "primitive" && calledOnType.type === "string") {
    const stringMethods: Record<string, InferredType> = {
      // Methods that return string
      toUpperCase: { kind: "primitive", type: "string" },
      toLowerCase: { kind: "primitive", type: "string" },
      trim: { kind: "primitive", type: "string" },
      trimStart: { kind: "primitive", type: "string" },
      trimEnd: { kind: "primitive", type: "string" },
      slice: { kind: "primitive", type: "string" },
      substring: { kind: "primitive", type: "string" },
      substr: { kind: "primitive", type: "string" },
      replace: { kind: "primitive", type: "string" },
      replaceAll: { kind: "primitive", type: "string" },
      charAt: { kind: "primitive", type: "string" },
      concat: { kind: "primitive", type: "string" },
      padStart: { kind: "primitive", type: "string" },
      padEnd: { kind: "primitive", type: "string" },
      repeat: { kind: "primitive", type: "string" },
      normalize: { kind: "primitive", type: "string" },
      toString: { kind: "primitive", type: "string" },
      // Methods that return number
      indexOf: { kind: "primitive", type: "number" },
      lastIndexOf: { kind: "primitive", type: "number" },
      charCodeAt: { kind: "primitive", type: "number" },
      search: { kind: "primitive", type: "number" },
      // Methods that return boolean
      includes: { kind: "primitive", type: "boolean" },
      startsWith: { kind: "primitive", type: "boolean" },
      endsWith: { kind: "primitive", type: "boolean" },
      // Methods that return array
      split: { kind: "array", elementType: { kind: "primitive", type: "string" } },
      match: { kind: "array", elementType: { kind: "primitive", type: "string" } },
    };
    return stringMethods[methodName] ?? { kind: "unknown" };
  }

  // Array methods
  if (calledOnType.kind === "array") {
    const elementType = calledOnType.elementType;
    const arrayMethods: Record<string, InferredType> = {
      // Methods that return the same array type
      filter: calledOnType,
      slice: calledOnType,
      concat: calledOnType,
      flat: calledOnType,
      toReversed: calledOnType,
      toSorted: calledOnType,
      reverse: calledOnType,
      sort: calledOnType,
      // Methods that return element type
      find: elementType,
      findLast: elementType,
      at: elementType,
      // Methods that return number
      indexOf: { kind: "primitive", type: "number" },
      lastIndexOf: { kind: "primitive", type: "number" },
      findIndex: { kind: "primitive", type: "number" },
      findLastIndex: { kind: "primitive", type: "number" },
      // Methods that return boolean
      includes: { kind: "primitive", type: "boolean" },
      some: { kind: "primitive", type: "boolean" },
      every: { kind: "primitive", type: "boolean" },
      // Methods that return string
      join: { kind: "primitive", type: "string" },
      toString: { kind: "primitive", type: "string" },
      // map, reduce, flatMap return unknown since we can't know the callback return type
      map: { kind: "array", elementType: { kind: "unknown" } },
      flatMap: { kind: "array", elementType: { kind: "unknown" } },
      reduce: { kind: "unknown" },
      reduceRight: { kind: "unknown" },
    };
    return arrayMethods[methodName] ?? { kind: "unknown" };
  }

  // Number methods
  if (calledOnType.kind === "primitive" && calledOnType.type === "number") {
    const numberMethods: Record<string, InferredType> = {
      toFixed: { kind: "primitive", type: "string" },
      toPrecision: { kind: "primitive", type: "string" },
      toString: { kind: "primitive", type: "string" },
      toLocaleString: { kind: "primitive", type: "string" },
      toExponential: { kind: "primitive", type: "string" },
    };
    return numberMethods[methodName] ?? { kind: "unknown" };
  }

  // Boolean methods
  if (calledOnType.kind === "primitive" && calledOnType.type === "boolean") {
    if (methodName === "toString") {
      return { kind: "primitive", type: "string" };
    }
  }

  return { kind: "unknown" };
}

/**
 * Resolve a path to get the value from data, accounting for scope variables.
 */
export function resolvePathValue(
  pathSegments: string[],
  data: Record<string, unknown>,
  scopeVars: ScopeVariable[],
): unknown {
  if (pathSegments.length === 0) return data;

  const firstSegment = pathSegments[0];

  // Check if first segment is a scope variable
  const scopeVar = scopeVars.find((v) => v.name === firstSegment);
  if (scopeVar) {
    const resolved = resolveScopeVariableValue(scopeVar, data);
    if (pathSegments.length === 1) return resolved;
    return resolveNestedPath(pathSegments.slice(1), resolved);
  }

  // Resolve from data
  return resolveNestedPath(pathSegments, data);
}

function resolveNestedPath(pathSegments: string[], value: unknown): unknown {
  let current = value;

  for (const segment of pathSegments) {
    if (current === null || current === undefined) return undefined;

    // Handle array index access [0]
    if (segment.startsWith("[") && segment.endsWith("]")) {
      const indexStr = segment.slice(1, -1);
      const index = parseInt(indexStr, 10);
      if (Array.isArray(current) && !isNaN(index)) {
        current = current[index];
      } else {
        return undefined;
      }
    } else if (typeof current === "object") {
      current = (current as Record<string, unknown>)[segment];
    } else {
      return undefined;
    }
  }

  return current;
}

function resolveScopeVariableValue(scopeVar: ScopeVariable, data: Record<string, unknown>): unknown {
  if (scopeVar.type === "loop-index") {
    return 0; // Preview with index 0
  }

  if (scopeVar.type === "loop-item") {
    // Get the first item from the array
    const arrayPathSegments = parsePath(scopeVar.arrayPath);
    const arrayValue = resolveNestedPath(arrayPathSegments, data);
    if (Array.isArray(arrayValue) && arrayValue.length > 0) {
      return arrayValue[0];
    }
  }

  return undefined;
}

/**
 * Resolve a path and return its inferred type.
 * Handles method calls by inferring return types.
 */
export function resolvePathType(
  pathSegments: string[],
  data: Record<string, unknown>,
  scopeVars: ScopeVariable[],
): InferredType {
  if (pathSegments.length === 0) {
    return { kind: "object", properties: {} };
  }

  // Start with the first segment
  let currentType: InferredType;
  const firstSegment = pathSegments[0];

  // Check if first segment is a scope variable
  const scopeVar = scopeVars.find((v) => v.name === firstSegment);
  if (scopeVar) {
    const resolved = resolveScopeVariableValue(scopeVar, data);
    currentType = inferType(resolved);
  } else if (firstSegment in data) {
    currentType = inferType(data[firstSegment]);
  } else {
    return { kind: "unknown" };
  }

  // Walk through remaining segments
  for (let i = 1; i < pathSegments.length; i++) {
    const segment = pathSegments[i];

    if (isMethodCall(segment)) {
      // It's a method call - infer return type
      const methodName = getMethodName(segment);
      currentType = inferMethodReturnType(methodName, currentType);
    } else if (segment.startsWith("[") && segment.endsWith("]")) {
      // Array index access
      if (currentType.kind === "array") {
        currentType = currentType.elementType;
      } else {
        return { kind: "unknown" };
      }
    } else {
      // Property access
      if (currentType.kind === "object" && segment in currentType.properties) {
        currentType = currentType.properties[segment];
      } else {
        // Try to resolve from actual data for better type inference
        const partialPath = pathSegments.slice(0, i + 1);
        const value = resolvePathValue(partialPath, data, scopeVars);
        currentType = inferType(value);
      }
    }
  }

  return currentType;
}

/**
 * Format a type for display in autocomplete.
 */
export function formatTypeForDisplay(type: InferredType): string {
  switch (type.kind) {
    case "primitive":
      return type.type;
    case "array":
      return `${formatTypeForDisplay(type.elementType)}[]`;
    case "object":
      return "object";
    case "unknown":
      return "unknown";
  }
}
