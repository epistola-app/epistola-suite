/**
 * JSONata expression evaluation and display formatting for expression chips.
 *
 * Used by ExpressionNodeView to resolve expression values from the currently
 * selected data example and format them for inline display.
 */

import jsonata from 'jsonata';

// ---------------------------------------------------------------------------
// Custom JSONata functions
// ---------------------------------------------------------------------------

const MONTH_NAMES_FULL = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

const MONTH_NAMES_SHORT = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

/**
 * Format an ISO date or datetime string using a pattern.
 *
 * Supported date tokens: `yyyy`, `MMMM`, `MMM`, `MM`, `dd`, `d`.
 * Supported time tokens: `HH`, `mm`, `ss`.
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
export function formatDateValue(value: string, pattern: string): string {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?/);
  if (!match) return value;
  const [, yyyy, mm, dd, HH = '00', min = '00', ss = '00'] = match;
  const month = parseInt(mm, 10);
  const day = parseInt(dd, 10);

  return pattern
    .replace('yyyy', yyyy)
    .replace('MMMM', MONTH_NAMES_FULL[month - 1] ?? '')
    .replace('MMM', MONTH_NAMES_SHORT[month - 1] ?? '')
    .replace('MM', mm)
    .replace('dd', dd)
    .replace('HH', HH)
    .replace(/(?<![a-zA-Z])mm(?![a-zA-Z])/, min)
    .replace('ss', ss)
    .replace(/(?<![a-zA-Z])d(?![a-zA-Z])/, String(day));
}

/**
 * Map a language code to decimal/grouping separators.
 * English uses `.` decimal + `,` grouping; everything else defaults to
 * `,` decimal + `.` grouping (Dutch/European). When language is `undefined`
 * or `null`, the English/canonical defaults are used.
 */
function separatorsForLanguage(language: string | null | undefined): {
  decimal: string;
  grouping: string;
} {
  const lang = language?.toLowerCase();
  if (lang === 'en' || lang === 'en-us' || lang === 'en-gb' || lang == null) {
    return { decimal: '.', grouping: ',' };
  }
  return { decimal: ',', grouping: '.' };
}

/**
 * Format a numeric value using a canonical DecimalFormat-style pattern.
 * The optional `language` code selects locale-specific decimal/grouping separators.
 *
 * This is the implementation behind the `$formatLocalNumber` custom function —
 * distinct from JSONata's built-in `$formatNumber` (which interprets the
 * picture against the locale symbols rather than transforming output chars).
 */
export function formatLocalNumberValue(
  value: number,
  pattern: string,
  language?: string | null,
): string {
  const { decimal: decimalSep, grouping: groupingSep } = separatorsForLanguage(language);

  // Handle positive/negative subpatterns
  const semiIdx = pattern.indexOf(';');
  if (semiIdx !== -1) {
    const subPattern = value < 0 ? pattern.slice(semiIdx + 1) : pattern.slice(0, semiIdx);
    return formatLocalNumberValue(value < 0 ? -value : value, subPattern, language);
  }

  // Extract prefix and suffix (literal characters outside #0.,%)
  const numChars = new Set(['#', '0', ',', '.', '%']);
  let numStart = 0;
  while (numStart < pattern.length && !numChars.has(pattern[numStart])) numStart++;
  let numEnd = pattern.length;
  while (numEnd > numStart && !numChars.has(pattern[numEnd - 1])) numEnd--;

  const prefix = pattern.slice(0, numStart);
  const suffix = pattern.slice(numEnd);
  const numPattern = pattern.slice(numStart, numEnd);

  // Handle percentage
  let num = value;
  const isPercent = numPattern.includes('%');
  if (isPercent) num *= 100;

  const isNegative = num < 0;
  num = Math.abs(num);

  // Canonical pattern: `,` = grouping, `.` = decimal
  const cleanPattern = numPattern.replace(/%/g, '');
  const dotIdx = cleanPattern.indexOf('.');
  const intPattern = dotIdx !== -1 ? cleanPattern.slice(0, dotIdx) : cleanPattern;
  const decPattern = dotIdx !== -1 ? cleanPattern.slice(dotIdx + 1) : '';

  const useGrouping = intPattern.includes(',');
  const groupSize = 3;

  const minDecimals = (decPattern.match(/0/g) || []).length;
  const maxDecimals = minDecimals + (decPattern.match(/#/g) || []).length;

  const rounded = maxDecimals > 0 ? parseFloat(num.toFixed(maxDecimals)) : Math.round(num);
  const fixed = rounded.toFixed(maxDecimals);
  const [intStr, decStr = ''] = fixed.split('.');

  const minIntDigits = (intPattern.match(/0/g) || []).length || 1;
  let intPart = intStr.length < minIntDigits ? intStr.padStart(minIntDigits, '0') : intStr;

  if (useGrouping && intPart.length > groupSize) {
    const grouped: string[] = [];
    for (let i = intPart.length; i > 0; i -= groupSize) {
      grouped.unshift(intPart.slice(Math.max(0, i - groupSize), i));
    }
    intPart = grouped.join(groupingSep);
  }

  let decPart = decStr;
  if (maxDecimals > minDecimals) {
    while (decPart.length > minDecimals && decPart.endsWith('0')) {
      decPart = decPart.slice(0, -1);
    }
  }

  let result = intPart;
  if (decPart.length > 0) result += decimalSep + decPart;
  if (isPercent) result += '%';
  if (isNegative) result = '-' + result;

  return prefix + result + suffix;
}

/**
 * Register custom functions on a JSONata expression instance.
 * Must be called before `expr.evaluate()`.
 */
function registerCustomFunctions(expr: jsonata.Expression): void {
  expr.registerFunction('formatDate', (value: unknown, pattern: unknown) => {
    if (typeof value !== 'string' || typeof pattern !== 'string') return value;
    return formatDateValue(value, pattern);
  });
  expr.registerFunction(
    'formatLocalNumber',
    (value: unknown, pattern: unknown, language: unknown) => {
      if (typeof pattern !== 'string') return value;
      const num =
        typeof value === 'number' ? value : typeof value === 'string' ? parseFloat(value) : NaN;
      if (isNaN(num)) return value;
      const lang = typeof language === 'string' ? language : undefined;
      return formatLocalNumberValue(num, pattern, lang);
    },
  );
}

/**
 * Evaluate a JSONata expression against the given data.
 * Returns `undefined` on empty expression, evaluation error, or missing path.
 */
export async function evaluateExpression(
  expression: string,
  data: Record<string, unknown>,
): Promise<unknown> {
  const trimmed = expression.trim();
  if (!trimmed) return undefined;

  try {
    const expr = jsonata(trimmed);
    registerCustomFunctions(expr);
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
): Promise<ExpressionResult> {
  const trimmed = expression.trim();
  if (!trimmed) return { ok: false, error: 'Expression is empty' };

  try {
    const expr = jsonata(trimmed);
    registerCustomFunctions(expr);
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
