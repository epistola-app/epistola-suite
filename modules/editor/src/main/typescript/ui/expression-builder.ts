import type { FieldPath } from '../engine/schema-paths.js';

/** Regex to parse `$formatDate(fieldPath, 'pattern')` or `$formatDate(fieldPath, "pattern")` expressions. */
const FORMAT_DATE_REGEX = /^\$formatDate\(\s*([^,]+?)\s*,\s*["']([^"']+)["']\s*\)$/;

/** Extract field path and format pattern from a `$formatDate(...)` expression. */
export function parseFormatDateExpression(
  expr: string,
): { fieldPath: string; pattern: string } | null {
  const match = expr.match(FORMAT_DATE_REGEX);
  if (!match) return null;
  return { fieldPath: match[1], pattern: match[2] };
}

/** Wrap a field path with `$formatDate(...)`. */
export function wrapFormatDate(fieldPath: string, pattern: string): string {
  return `$formatDate(${fieldPath}, '${pattern}')`;
}

// ---------------------------------------------------------------------------
// Builder mode support
// ---------------------------------------------------------------------------

/** State representing a builder-representable expression. */
export interface BuilderState {
  fieldPath: string;
  fieldType: string;
  formatType: 'none' | 'date';
  formatPattern: string;
}

/**
 * Try to parse an expression into a BuilderState.
 * Returns null if the expression cannot be represented in builder mode.
 */
export function tryParseAsBuilderExpression(
  expr: string,
  fieldPaths: FieldPath[],
): BuilderState | null {
  const trimmed = expr.trim();
  if (!trimmed) return null;

  // Try $formatDate(field, 'pattern')
  const parsed = parseFormatDateExpression(trimmed);
  if (parsed) {
    const fp = fieldPaths.find((f) => f.path === parsed.fieldPath);
    if (fp) {
      return {
        fieldPath: parsed.fieldPath,
        fieldType: fp.type,
        formatType: 'date',
        formatPattern: parsed.pattern,
      };
    }
  }

  // Try bare field path
  const fp = fieldPaths.find((f) => f.path === trimmed);
  if (fp) {
    return { fieldPath: trimmed, fieldType: fp.type, formatType: 'none', formatPattern: '' };
  }

  return null;
}

/**
 * Check if an expression looks like a simple builder pattern (bare path or $formatDate)
 * but the field isn't in the available paths. This distinguishes "stale field reference"
 * from "complex JSONata expression".
 */
export function isStaleFieldReference(expr: string, fieldPaths: FieldPath[]): boolean {
  const trimmed = expr.trim();
  if (!trimmed) return false;

  // Check if it's a $formatDate with a field path that's not found
  const parsed = parseFormatDateExpression(trimmed);
  if (parsed) {
    return !fieldPaths.some((f) => f.path === parsed.fieldPath);
  }

  // Check if it looks like a simple dot-path (no operators, no function calls except $formatDate)
  const looksLikeSimplePath = /^[a-zA-Z_][a-zA-Z0-9_.]*$/.test(trimmed);
  if (looksLikeSimplePath) {
    return !fieldPaths.some((f) => f.path === trimmed);
  }

  return false;
}

/** Construct an expression string from builder state. */
export function buildExpression(state: BuilderState): string {
  if (state.formatType === 'date' && state.formatPattern) {
    return wrapFormatDate(state.fieldPath, state.formatPattern);
  }
  return state.fieldPath;
}
