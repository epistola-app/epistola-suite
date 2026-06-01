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
  const longFormatter = new Intl.DateTimeFormat(locale, { month: 'long' });
  const shortFormatter = new Intl.DateTimeFormat(locale, { month: 'short' });
  const fullNames: string[] = [];
  const shortNames: string[] = [];
  for (let monthIndex = 0; monthIndex < 12; monthIndex++) {
    // Use day 15 to dodge DST/calendar edge cases at month boundaries.
    const date = new Date(Date.UTC(2024, monthIndex, 15));
    fullNames.push(longFormatter.format(date));
    shortNames.push(shortFormatter.format(date));
  }
  const entry = { full: fullNames, short: shortNames };
  monthNameCache.set(locale, entry);
  return entry;
}

/**
 * Cache of `{ locale -> {full: [...7], short: [...7]} }` localized weekday
 * names, indexed by `Date.getUTCDay()` (0 = Sunday). Same lazy/caching rationale
 * as [localizedMonthNames] — drives the `EEEE`/`EEE` tokens.
 */
const weekdayNameCache = new Map<string, { full: string[]; short: string[] }>();

function localizedWeekdayNames(locale: string): { full: string[]; short: string[] } {
  const cached = weekdayNameCache.get(locale);
  if (cached) return cached;
  const longFormatter = new Intl.DateTimeFormat(locale, { weekday: 'long' });
  const shortFormatter = new Intl.DateTimeFormat(locale, { weekday: 'short' });
  const fullNames: string[] = [];
  const shortNames: string[] = [];
  // 2024-01-07 is a Sunday; walk the seven following days so the array index
  // lines up with `Date.getUTCDay()` (0 = Sunday … 6 = Saturday).
  for (let dayOffset = 0; dayOffset < 7; dayOffset++) {
    const date = new Date(Date.UTC(2024, 0, 7 + dayOffset));
    fullNames.push(longFormatter.format(date));
    shortNames.push(shortFormatter.format(date));
  }
  const entry = { full: fullNames, short: shortNames };
  weekdayNameCache.set(locale, entry);
  return entry;
}

/**
 * Format an ISO date or datetime string using a pattern, localized.
 *
 * Supported date tokens: `EEEE`, `EEE`, `yyyy`, `MMMM`, `MMM`, `MM`, `dd`, `d`.
 * Supported time tokens: `HH`, `mm`, `ss`.
 *
 * `locale` is a BCP-47 tag (e.g. `"nl-NL"`, `"en-US"`, `"de-DE"`); it controls
 * the spelling of `EEEE`/`EEE` (weekday name) and `MMMM`/`MMM` (month name). The
 * numeric tokens are locale-agnostic by design — the Java `DateTimeFormatter` on
 * the renderer side behaves the same way for those — so editor preview matches
 * the PDF render for the locale chain (variant attribute → tenant default → app
 * default).
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
  const [, year, monthDigits, dayDigits, hours = '00', minutes = '00', seconds = '00'] = match;
  const month = parseInt(monthDigits, 10);
  const day = parseInt(dayDigits, 10);
  const { full, short } = localizedMonthNames(locale);

  // Weekday names are only needed (and only computed) when the pattern asks for
  // them — keeps the common numeric-pattern path from building the cache.
  const weekday = pattern.includes('E') ? localizedWeekdayNames(locale) : null;
  const weekdayIndex = weekday ? new Date(Date.UTC(Number(year), month - 1, day)).getUTCDay() : 0;

  return pattern
    .replace('yyyy', year)
    .replace('MMMM', full[month - 1] ?? '')
    .replace('MMM', short[month - 1] ?? '')
    .replace('EEEE', weekday?.full[weekdayIndex] ?? '')
    .replace('EEE', weekday?.short[weekdayIndex] ?? '')
    .replace('MM', monthDigits)
    .replace('dd', dayDigits)
    .replace('HH', hours)
    .replace(/(?<![a-zA-Z])mm(?![a-zA-Z])/, minutes)
    .replace('ss', seconds)
    .replace(/(?<![a-zA-Z])d(?![a-zA-Z])/, String(day));
}

/**
 * Format a number using a `DecimalFormat`-style picture with locale-aware
 * separators. Companion to [formatDateValue] — deliberately a *separate*
 * function from JSONata's W3C `$formatNumber` so existing call sites keep
 * the spec-defined behaviour while templates that want localized output
 * can opt in with `$formatLocaleNumber`.
 *
 * Supported picture grammar (the subset templates use day-to-day):
 *  - `0` / `#` digits (zero-padded / optional)
 *  - `,` grouping separator placeholder (renders as the locale's grouping char)
 *  - `.` decimal separator placeholder (renders as the locale's decimal char)
 *  - `;` separator between positive and negative subpatterns
 *  - `%` percent (auto-multiplies value by 100, appends locale's percent char)
 *  - `‰` per-mille (auto-multiplies value by 1000, appends locale's per-mille char)
 *  - any other character is treated as a literal and emitted as-is
 *
 * What's *not* supported (use `$formatNumber(value, picture, options)` for these):
 *  - scientific notation (`E0`)
 *  - explicit pattern options (`zero-digit`, `infinity`, `NaN`)
 *
 * Examples (locale `'nl-NL'`):
 *  - `formatLocaleNumberValue(1234.56, '#,##0.00', 'nl-NL')` → `'1.234,56'`
 *  - `formatLocaleNumberValue(0.21, '0.0%', 'nl-NL')` → `'21,0%'`
 *  - `formatLocaleNumberValue(-12.5, '#,##0.00;(#,##0.00)', 'nl-NL')` → `'(12,50)'`
 *
 * Returns the input as a string when [value] can't be parsed as a number,
 * matching the spirit of [formatDateValue].
 */
export function formatLocaleNumberValue(
  value: unknown,
  picture: string,
  locale: string = DEFAULT_LOCALE,
): string {
  const numericValue = typeof value === 'number' ? value : Number(value);
  if (!isFinite(numericValue)) return String(value);

  // Split positive;negative subpatterns. If only positive given, derive
  // negative by prepending the locale's minus sign (matches DecimalFormat).
  const [positivePattern, negativePatternRaw] = picture.split(';', 2);
  const isNegative = numericValue < 0;
  const pattern = isNegative && negativePatternRaw ? negativePatternRaw : positivePattern;
  const absoluteValue = Math.abs(numericValue);

  // Scale: % multiplies by 100, ‰ by 1000. Detected on the active pattern.
  const isPercent = pattern.includes('%');
  const isPermille = pattern.includes('‰');
  const scaled = isPercent
    ? absoluteValue * 100
    : isPermille
      ? absoluteValue * 1000
      : absoluteValue;

  // Strip the % / ‰ marker so they don't enter the digit-parsing logic.
  // We re-attach them at the end as the locale's localised symbol.
  const stripped = pattern.replace(/[%‰]/g, '');

  // Find the decimal point in the picture (if any) to drive fraction digits.
  const decimalIndex = stripped.indexOf('.');
  const integerPart = decimalIndex >= 0 ? stripped.slice(0, decimalIndex) : stripped;
  const fractionPart = decimalIndex >= 0 ? stripped.slice(decimalIndex + 1) : '';
  const minFractionDigits = (fractionPart.match(/0/g) ?? []).length;
  const maxFractionDigits = (fractionPart.match(/[0#]/g) ?? []).length;
  const useGrouping = integerPart.includes(',');
  const minIntegerDigits = (integerPart.match(/0/g) ?? []).length;

  const formatted = new Intl.NumberFormat(locale, {
    useGrouping,
    minimumIntegerDigits: Math.max(1, minIntegerDigits),
    minimumFractionDigits: minFractionDigits,
    maximumFractionDigits: Math.max(minFractionDigits, maxFractionDigits),
    // Match the PDF renderer: Java's DecimalFormat rounds HALF_EVEN (banker's
    // rounding) by default, whereas Intl.NumberFormat defaults to 'halfExpand'.
    // Without this the editor preview would round 8.5 → 9 while the generated
    // PDF rounds 8.5 → 8. See JsonataEvaluator.formatLocaleNumber (Kotlin).
    roundingMode: 'halfEven',
  }).format(scaled);

  // Locale-correct % / ‰ symbol via formatToParts. Falls back to the raw
  // character if Intl doesn't surface it for this locale.
  let suffix = '';
  if (isPercent) {
    const parts = new Intl.NumberFormat(locale, { style: 'percent' }).formatToParts(0);
    suffix = parts.find((part) => part.type === 'percentSign')?.value ?? '%';
  } else if (isPermille) {
    // Intl doesn't have a 'permille' style; the per-mille glyph is the same
    // in every locale CLDR ships (U+2030).
    suffix = '‰';
  }

  // Re-apply the negative subpattern wrapping. If the user supplied an
  // explicit negative subpattern, we already chose it above; the formatted
  // string is the unsigned magnitude. If they only supplied a positive one,
  // prepend the locale's minus sign manually.
  if (isNegative) {
    if (negativePatternRaw) {
      // Negative subpattern owns the wrapping; format the digits then
      // splice into the literal text of the negative pattern.
      return composeFromPattern(negativePatternRaw, formatted + suffix);
    }
    const minusSign =
      new Intl.NumberFormat(locale, { signDisplay: 'always' })
        .formatToParts(-1)
        .find((part) => part.type === 'minusSign')?.value ?? '-';
    return minusSign + formatted + suffix;
  }
  return composeFromPattern(positivePattern, formatted + suffix);
}

/**
 * Splice [digits] (a fully formatted number, with suffix) into [pattern]'s
 * literal scaffolding. Any character in [pattern] that isn't part of the
 * number-shape vocabulary (`0 # , . % ‰`) is emitted verbatim, with the
 * `digits` block inserted where the first number-shape character occurs.
 * Used so a picture like `'$#,##0.00'` keeps its leading `$` and
 * `'(#,##0.00)'` (negative subpattern) gets the surrounding parens.
 */
function composeFromPattern(pattern: string, digits: string): string {
  const shapeCharacters = new Set(['0', '#', ',', '.', '%', '‰']);
  let result = '';
  let inserted = false;
  for (const character of pattern) {
    if (shapeCharacters.has(character)) {
      if (!inserted) {
        result += digits;
        inserted = true;
      }
      // Skip subsequent shape characters — `digits` already contains them.
    } else {
      result += character;
    }
  }
  return inserted ? result : digits;
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
  expr.registerFunction('formatLocaleNumber', (value: unknown, picture: unknown) => {
    // Missing-field semantics match $formatDate: a `null`/`undefined` value
    // (e.g. an unbound path) passes through unchanged so the expression chip
    // can fall back to its raw label rather than rendering "undefined".
    if (value === undefined || value === null) return value;
    if (typeof picture !== 'string') return value;
    return formatLocaleNumberValue(value, picture, locale);
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
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : String(error);
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
