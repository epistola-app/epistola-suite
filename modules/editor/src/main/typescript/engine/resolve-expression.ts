/**
 * JSONata expression evaluation and display formatting for expression chips.
 *
 * Used by ExpressionNodeView to resolve expression values from the currently
 * selected data example and format them for inline display.
 */

import jsonata from 'jsonata';
import { DEFAULT_LOCALE } from './locale.js';

// ---------------------------------------------------------------------------
// Custom JSONata functions
// ---------------------------------------------------------------------------

/**
 * Cache of `{ locale -> {full: [...], short: [...]} }` localized month names,
 * built lazily via `Intl.DateTimeFormat`. Computing month names is not cheap
 * (the formatter is instantiated under the hood) and `formatDateValue` is
 * called once per ExpressionNodeView on every preview render, so caching by
 * locale is worth it.
 */
const monthNameCache = new Map<string, { full: string[]; short: string[] }>();

function localizedMonthNames(locale: string): { full: string[]; short: string[] } {
  const cached = monthNameCache.get(locale);
  if (cached) return cached;
  const full = new Intl.DateTimeFormat(locale, { month: 'long' });
  const short = new Intl.DateTimeFormat(locale, { month: 'short' });
  const fullNames: string[] = [];
  const shortNames: string[] = [];
  for (let m = 0; m < 12; m++) {
    // Use day 15 to dodge DST/calendar edge cases at month boundaries.
    const d = new Date(Date.UTC(2024, m, 15));
    fullNames.push(full.format(d));
    shortNames.push(short.format(d));
  }
  const entry = { full: fullNames, short: shortNames };
  monthNameCache.set(locale, entry);
  return entry;
}

/**
 * Format an ISO date or datetime string using a pattern, localized.
 *
 * Supported date tokens: `yyyy`, `MMMM`, `MMM`, `MM`, `dd`, `d`.
 * Supported time tokens: `HH`, `mm`, `ss`.
 *
 * `locale` is a BCP-47 tag (e.g. `"nl-NL"`, `"en-US"`, `"de-DE"`); it controls
 * the spelling of `MMMM`/`MMM` (month name). Numeric tokens are locale-agnostic
 * by design — the Java `DateTimeFormatter` on the renderer side behaves the
 * same way for these tokens. Editor preview now matches the PDF render exactly
 * for the locale chain (variant attribute → tenant default → app default).
 *
 * Accepts plain dates (`2024-01-15`), local datetimes (`2024-01-15T14:30:00`),
 * UTC datetimes (`2024-01-15T14:30:00Z`), and offset datetimes
 * (`2024-01-15T14:30:00+02:00`).
 *
 * For the editor preview, datetimes are displayed in UTC. The Kotlin renderer
 * (PDF generation) uses the configured timezone (default: Europe/Amsterdam)
 * and supports the full Java `DateTimeFormatter` pattern spec. Custom patterns
 * using unsupported tokens will render correctly in the PDF but may show
 * unresolved tokens in the editor preview.
 *
 * Returns the original value if it cannot be parsed.
 */
export function formatDateValue(
  value: string,
  pattern: string,
  locale: string = DEFAULT_LOCALE,
): string {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?/);
  if (!match) return value;
  const [, yyyy, mm, dd, HH = '00', min = '00', ss = '00'] = match;
  const month = parseInt(mm, 10);
  const day = parseInt(dd, 10);
  const { full, short } = localizedMonthNames(locale);

  return pattern
    .replace('yyyy', yyyy)
    .replace('MMMM', full[month - 1] ?? '')
    .replace('MMM', short[month - 1] ?? '')
    .replace('MM', mm)
    .replace('dd', dd)
    .replace('HH', HH)
    .replace(/(?<![a-zA-Z])mm(?![a-zA-Z])/, min)
    .replace('ss', ss)
    .replace(/(?<![a-zA-Z])d(?![a-zA-Z])/, String(day));
}

/**
 * Register custom functions on a JSONata expression instance.
 * Must be called before `expr.evaluate()`.
 */
function registerCustomFunctions(expr: jsonata.Expression, locale: string): void {
  expr.registerFunction('formatDate', (value: unknown, pattern: unknown) => {
    if (typeof value !== 'string' || typeof pattern !== 'string') return value;
    return formatDateValue(value, pattern, locale);
  });
}

/**
 * Evaluate a JSONata expression against the given data.
 * Returns `undefined` on empty expression, evaluation error, or missing path.
 *
 * [locale] (BCP-47, default `"en-US"`) is the locale used to spell `MMMM`/`MMM`
 * tokens in `$formatDate`. The host (editor mount) should pass the value
 * resolved by the locale chain (variant attribute → tenant default → app default)
 * so the inline preview matches the PDF.
 */
export async function evaluateExpression(
  expression: string,
  data: Record<string, unknown>,
  locale: string = DEFAULT_LOCALE,
): Promise<unknown> {
  const trimmed = expression.trim();
  if (!trimmed) return undefined;

  try {
    const expr = jsonata(trimmed);
    registerCustomFunctions(expr, locale);
    return await expr.evaluate(data);
  } catch {
    return undefined;
  }
}

/**
 * Format a resolved value for inline display in an expression chip.
 *
 * Returns `undefined` (= fall back to raw expression) for values that
 * aren't displayable inline: undefined, null, empty strings, objects, arrays.
 */
export function formatResolvedValue(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'string') return value.length > 0 ? value : undefined;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  // Objects and arrays aren't displayable inline
  return undefined;
}

// ---------------------------------------------------------------------------
// Dialog-oriented helpers
// ---------------------------------------------------------------------------

/** Discriminated result type for expression evaluation. */
export type ExpressionResult = { ok: true; value: unknown } | { ok: false; error: string };

/**
 * Evaluate a JSONata expression and return a discriminated result.
 * Unlike `evaluateExpression`, distinguishes parse errors from missing paths
 * so the dialog can show meaningful feedback.
 */
export async function tryEvaluateExpression(
  expression: string,
  data: Record<string, unknown>,
  locale: string = DEFAULT_LOCALE,
): Promise<ExpressionResult> {
  const trimmed = expression.trim();
  if (!trimmed) return { ok: false, error: 'Expression is empty' };

  try {
    const expr = jsonata(trimmed);
    registerCustomFunctions(expr, locale);
    const value = await expr.evaluate(data);
    return { ok: true, value };
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : String(e);
    return { ok: false, error: message };
  }
}

const FORMAT_PREVIEW_MAX_LENGTH = 120;

/**
 * Format a value for the dialog preview panel.
 *
 * Unlike `formatResolvedValue` (which returns `undefined` for non-inline types),
 * this always returns a human-readable string — including for objects, arrays,
 * undefined, null, and empty strings.
 */
export function formatForPreview(value: unknown): string {
  if (value === undefined) return 'undefined';
  if (value === null) return 'null';
  if (typeof value === 'string') return value.length === 0 ? '(empty string)' : value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);

  // Objects and arrays — show truncated JSON
  try {
    const json = JSON.stringify(value);
    if (json.length > FORMAT_PREVIEW_MAX_LENGTH) {
      return json.slice(0, FORMAT_PREVIEW_MAX_LENGTH) + '…';
    }
    return json;
  } catch {
    return String(value);
  }
}

/**
 * Validates that an evaluated expression result is an array.
 * Intended as a `resultValidator` for loop expression dialogs.
 *
 * Returns an error message if the value is not an array, null if valid.
 * `undefined` results (missing path) are not flagged since the expression
 * may be valid with different data.
 *
 * Note: JSONata unwraps single-result filters (e.g., `items[x=1]` returns
 * the object directly when only one item matches). We still flag this since
 * the loop expression should consistently return an array.
 */
export function validateArrayResult(value: unknown): string | null {
  if (value === undefined) return null;
  if (Array.isArray(value)) return null;
  const type = value === null ? 'null' : typeof value;
  return `Loop expression must evaluate to an array, got ${type}: ${formatForPreview(value)}`;
}

/**
 * Validates that an evaluated expression result is a boolean.
 * Intended as a `resultValidator` for conditional expression dialogs.
 *
 * Returns an error message if the value is not a boolean, null if valid.
 * `undefined` results (missing path) are not flagged since the expression
 * may be valid with different data.
 */
export function validateBooleanResult(value: unknown): string | null {
  if (value === undefined) return null;
  if (typeof value === 'boolean') return null;
  const type = value === null ? 'null' : typeof value;
  return `Condition must evaluate to a boolean, got ${type}: ${formatForPreview(value)}`;
}

/**
 * Synchronous parse-only check. Returns `true` if the expression is
 * syntactically valid JSONata (does NOT evaluate it).
 *
 * `jsonata(expr)` is synchronous and only parses — `.evaluate()` is the
 * async part. This gives instant red/green feedback without waiting for
 * data evaluation.
 */
export function isValidExpression(expression: string): boolean {
  const trimmed = expression.trim();
  if (!trimmed) return false;
  try {
    jsonata(trimmed);
    return true;
  } catch {
    return false;
  }
}
