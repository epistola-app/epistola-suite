export interface ScopeVariable {
  name: string;
  type: "loop-item" | "loop-index";
  arrayPath: string;
}

export type InferredType =
  | { kind: "primitive"; type: "string" | "number" | "boolean" | "null" | "undefined" }
  | { kind: "array"; elementType: InferredType }
  | { kind: "object"; properties: Record<string, InferredType> }
  | { kind: "unknown" };

export interface PathInfo {
  path: string;
  isArray: boolean;
  type: string;
}

export interface MethodSuggestion {
  label: string;
  type: "method" | "property";
  signature: string;
  detail: string;
}
