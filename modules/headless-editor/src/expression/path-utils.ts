import type { PathInfo, InferredType, ScopeVariable, MethodSuggestion } from "./types.js";

export function extractPaths(obj: unknown, prefix = ""): PathInfo[] {
  const paths: PathInfo[] = [];

  if (obj === null || obj === undefined) {
    return paths;
  }

  if (Array.isArray(obj)) {
    paths.push({ path: prefix, isArray: true, type: `array[${obj.length}]` });
    if (obj.length > 0) {
      const itemPaths = extractPaths(obj[0], `${prefix}[0]`);
      paths.push(...itemPaths);
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

export function isMethodCall(segment: string): boolean {
  return segment.endsWith(")") && segment.includes("(");
}

export function getMethodName(segment: string): string {
  const parenIndex = segment.indexOf("(");
  return parenIndex >= 0 ? segment.slice(0, parenIndex) : segment;
}

export const STRING_METHODS: MethodSuggestion[] = [
  { label: "toUpperCase", type: "method", signature: "(): string", detail: "Converts to uppercase" },
  { label: "toLowerCase", type: "method", signature: "(): string", detail: "Converts to lowercase" },
  { label: "trim", type: "method", signature: "(): string", detail: "Removes whitespace" },
  { label: "toLowerCase", type: "method", signature: "(): string", detail: "Converts to lowercase" },
  { label: "split", type: "method", signature: "(sep): string[]", detail: "Splits into array" },
  { label: "replace", type: "method", signature: "(search, replace): string", detail: "Replaces first match" },
  { label: "replaceAll", type: "method", signature: "(search, replace): string", detail: "Replaces all matches" },
  { label: "includes", type: "method", signature: "(search): boolean", detail: "Checks if contains" },
  { label: "startsWith", type: "method", signature: "(search): boolean", detail: "Checks start" },
  { label: "endsWith", type: "method", signature: "(search): boolean", detail: "Checks end" },
  { label: "length", type: "property", signature: ": number", detail: "String length" },
];

export const ARRAY_METHODS: MethodSuggestion[] = [
  { label: "map", type: "method", signature: "(fn): array", detail: "Transform elements" },
  { label: "filter", type: "method", signature: "(fn): array", detail: "Filter elements" },
  { label: "find", type: "method", signature: "(fn): element", detail: "Find first match" },
  { label: "length", type: "property", signature: ": number", detail: "Array length" },
  { label: "join", type: "method", signature: "(separator): string", detail: "Join to string" },
  { label: "indexOf", type: "method", signature: "(value): number", detail: "Find first index" },
  { label: "includes", type: "method", signature: "(value): boolean", detail: "Check if contains" },
  { label: "slice", type: "method", signature: "(start, end?): array", detail: "Extract section" },
  { label: "reduce", type: "method", signature: "(fn, init): any", detail: "Reduce to value" },
];

export const NUMBER_METHODS: MethodSuggestion[] = [
  { label: "toFixed", type: "method", signature: "(digits): string", detail: "Format decimals" },
  { label: "toString", type: "method", signature: "(radix?): string", detail: "Convert to string" },
];

export const BOOLEAN_METHODS: MethodSuggestion[] = [
  { label: "toString", type: "method", signature: "(): string", detail: "Convert to string" },
];

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

function resolveNestedPath(pathSegments: string[], value: unknown): unknown {
  let current = value;

  for (const segment of pathSegments) {
    if (current === null || current === undefined) return undefined;

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
    return 0;
  }

  if (scopeVar.type === "loop-item") {
    const arrayPathSegments = parsePath(scopeVar.arrayPath);
    const arrayValue = resolveNestedPath(arrayPathSegments, data);
    if (Array.isArray(arrayValue) && arrayValue.length > 0) {
      return arrayValue[0];
    }
  }

  return undefined;
}

export function resolvePathValue(
  pathSegments: string[],
  data: Record<string, unknown>,
  scopeVars: ScopeVariable[],
): unknown {
  if (pathSegments.length === 0) return data;

  const firstSegment = pathSegments[0];

  const scopeVar = scopeVars.find((v) => v.name === firstSegment);
  if (scopeVar) {
    const resolved = resolveScopeVariableValue(scopeVar, data);
    if (pathSegments.length === 1) return resolved;
    return resolveNestedPath(pathSegments.slice(1), resolved);
  }

  return resolveNestedPath(pathSegments, data);
}

export function resolvePathType(
  pathSegments: string[],
  data: Record<string, unknown>,
  scopeVars: ScopeVariable[],
): InferredType {
  if (pathSegments.length === 0) {
    return { kind: "object", properties: {} };
  }

  let currentType: InferredType;
  const firstSegment = pathSegments[0];

  const scopeVar = scopeVars.find((v) => v.name === firstSegment);
  if (scopeVar) {
    const resolved = resolveScopeVariableValue(scopeVar, data);
    currentType = inferType(resolved);
  } else if (firstSegment in data) {
    currentType = inferType(data[firstSegment]);
  } else {
    return { kind: "unknown" };
  }

  for (let i = 1; i < pathSegments.length; i++) {
    const segment = pathSegments[i];

    if (isMethodCall(segment)) {
      const methodName = getMethodName(segment);
      currentType = inferMethodReturnType(methodName, currentType);
    } else if (segment.startsWith("[") && segment.endsWith("]")) {
      if (currentType.kind === "array") {
        currentType = currentType.elementType;
      } else {
        return { kind: "unknown" };
      }
    } else {
      if (currentType.kind === "object" && segment in currentType.properties) {
        currentType = currentType.properties[segment];
      } else {
        const partialPath = pathSegments.slice(0, i + 1);
        const value = resolvePathValue(partialPath, data, scopeVars);
        currentType = inferType(value);
      }
    }
  }

  return currentType;
}

function inferMethodReturnType(methodName: string, calledOnType: InferredType): InferredType {
  const primitive = (type: "string" | "number" | "boolean"): InferredType => ({
    kind: "primitive",
    type,
  });

  if (calledOnType.kind === "primitive" && calledOnType.type === "string") {
    const stringMethods: Record<string, InferredType> = {
      toUpperCase: primitive("string"),
      toLowerCase: primitive("string"),
      trim: primitive("string"),
      slice: primitive("string"),
      substring: primitive("string"),
      replace: primitive("string"),
      replaceAll: primitive("string"),
      charAt: primitive("string"),
      concat: primitive("string"),
      toString: primitive("string"),
      indexOf: primitive("number"),
      lastIndexOf: primitive("number"),
      charCodeAt: primitive("number"),
      search: primitive("number"),
      includes: primitive("boolean"),
      startsWith: primitive("boolean"),
      endsWith: primitive("boolean"),
      split: { kind: "array", elementType: primitive("string") },
      match: { kind: "array", elementType: primitive("string") },
    };
    return stringMethods[methodName] ?? { kind: "unknown" };
  }

  if (calledOnType.kind === "array") {
    const elementType = calledOnType.elementType;
    const arrayMethods: Record<string, InferredType> = {
      filter: calledOnType,
      slice: calledOnType,
      concat: calledOnType,
      find: elementType,
      at: elementType,
      indexOf: primitive("number"),
      includes: primitive("boolean"),
      some: primitive("boolean"),
      every: primitive("boolean"),
      join: primitive("string"),
      toString: primitive("string"),
      map: { kind: "array", elementType: { kind: "unknown" } },
      flatMap: { kind: "array", elementType: { kind: "unknown" } },
      reduce: { kind: "unknown" },
    };
    return arrayMethods[methodName] ?? { kind: "unknown" };
  }

  if (calledOnType.kind === "primitive" && calledOnType.type === "number") {
    const numberMethods: Record<string, InferredType> = {
      toFixed: primitive("string"),
      toPrecision: primitive("string"),
      toString: primitive("string"),
    };
    return numberMethods[methodName] ?? { kind: "unknown" };
  }

  if (calledOnType.kind === "primitive" && calledOnType.type === "boolean") {
    if (methodName === "toString") {
      return primitive("string");
    }
  }

  return { kind: "unknown" };
}
