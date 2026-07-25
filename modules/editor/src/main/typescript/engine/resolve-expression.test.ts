// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import {
  evaluateExpression,
  formatResolvedValue,
  tryEvaluateExpression,
  formatForPreview,
  isValidExpression,
  validateArrayResult,
  validateBooleanResult,
  formatDateValue,
  formatLocaleNumberValue,
} from './resolve-expression.js';

// ---------------------------------------------------------------------------
// evaluateExpression
// ---------------------------------------------------------------------------

describe('evaluateExpression', () => {
  const data = {
    customer: {
      name: 'John Doe',
      age: 30,
      active: true,
      address: {
        city: 'Amsterdam',
        zip: '1012AB',
      },
    },
    items: [
      { name: 'Widget', price: 10 },
      { name: 'Gadget', price: 25 },
      { name: 'Gizmo', price: 15 },
    ],
    first: 'Jane',
    last: 'Smith',
    tags: ['a', 'b', 'c'],
  };

  it('resolves a simple path', async () => {
    expect(await evaluateExpression('customer.name', data)).toBe('John Doe');
  });

  it('resolves a nested path', async () => {
    expect(await evaluateExpression('customer.address.city', data)).toBe('Amsterdam');
  });

  it('resolves array access', async () => {
    expect(await evaluateExpression('items[0].name', data)).toBe('Widget');
  });

  it('resolves array mapping', async () => {
    const result = await evaluateExpression('items.name', data);
    // JSONata returns an array-like object; spread to compare as plain array
    expect([...(result as string[])]).toEqual(['Widget', 'Gadget', 'Gizmo']);
  });

  it('resolves string concatenation', async () => {
    expect(await evaluateExpression('first & " " & last', data)).toBe('Jane Smith');
  });

  it('resolves aggregation ($sum)', async () => {
    expect(await evaluateExpression('$sum(items.price)', data)).toBe(50);
  });

  it('resolves conditionals', async () => {
    expect(await evaluateExpression('customer.active ? "Yes" : "No"', data)).toBe('Yes');
  });

  it('returns undefined for missing paths', async () => {
    expect(await evaluateExpression('customer.nonexistent', data)).toBeUndefined();
  });

  it('returns undefined for invalid syntax', async () => {
    expect(await evaluateExpression('{{invalid}}', data)).toBeUndefined();
  });

  it('returns undefined for empty expression', async () => {
    expect(await evaluateExpression('', data)).toBeUndefined();
  });

  it('returns undefined for whitespace-only expression', async () => {
    expect(await evaluateExpression('   ', data)).toBeUndefined();
  });

  it('resolves number values', async () => {
    expect(await evaluateExpression('customer.age', data)).toBe(30);
  });

  it('resolves boolean values', async () => {
    expect(await evaluateExpression('customer.active', data)).toBe(true);
  });

  it('resolves array count', async () => {
    expect(await evaluateExpression('$count(items)', data)).toBe(3);
  });
});

// ---------------------------------------------------------------------------
// formatResolvedValue
// ---------------------------------------------------------------------------

describe('formatResolvedValue', () => {
  it('formats a string', () => {
    expect(formatResolvedValue('Hello')).toBe('Hello');
  });

  it('formats a number', () => {
    expect(formatResolvedValue(42)).toBe('42');
  });

  it('formats zero', () => {
    expect(formatResolvedValue(0)).toBe('0');
  });

  it('formats a boolean true', () => {
    expect(formatResolvedValue(true)).toBe('true');
  });

  it('formats a boolean false', () => {
    expect(formatResolvedValue(false)).toBe('false');
  });

  it('returns undefined for null', () => {
    expect(formatResolvedValue(null)).toBeUndefined();
  });

  it('returns undefined for undefined', () => {
    expect(formatResolvedValue(undefined)).toBeUndefined();
  });

  it('returns undefined for an object', () => {
    expect(formatResolvedValue({ a: 1 })).toBeUndefined();
  });

  it('returns undefined for an array', () => {
    expect(formatResolvedValue([1, 2, 3])).toBeUndefined();
  });

  it('returns undefined for an empty string', () => {
    expect(formatResolvedValue('')).toBeUndefined();
  });

  it('formats a float', () => {
    expect(formatResolvedValue(3.14)).toBe('3.14');
  });
});

// ---------------------------------------------------------------------------
// tryEvaluateExpression
// ---------------------------------------------------------------------------

describe('tryEvaluateExpression', () => {
  const data = {
    customer: { name: 'John Doe', age: 30 },
    items: [
      { name: 'Widget', price: 10 },
      { name: 'Gadget', price: 25 },
    ],
  };

  it('returns ok with value for a valid path', async () => {
    const result = await tryEvaluateExpression('customer.name', data);
    expect(result).toEqual({ ok: true, value: 'John Doe' });
  });

  it('returns ok with undefined for a missing path', async () => {
    const result = await tryEvaluateExpression('customer.nonexistent', data);
    expect(result).toEqual({ ok: true, value: undefined });
  });

  it('returns ok with number for aggregation', async () => {
    const result = await tryEvaluateExpression('$sum(items.price)', data);
    expect(result).toEqual({ ok: true, value: 35 });
  });

  it('returns error for syntax error', async () => {
    const result = await tryEvaluateExpression('{{broken', data);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.error).toBeTruthy();
    }
  });

  it('returns error for empty expression', async () => {
    const result = await tryEvaluateExpression('', data);
    expect(result).toEqual({ ok: false, error: 'Expression is empty' });
  });

  it('returns error for whitespace-only expression', async () => {
    const result = await tryEvaluateExpression('   ', data);
    expect(result).toEqual({ ok: false, error: 'Expression is empty' });
  });

  it('returns ok with boolean value', async () => {
    const result = await tryEvaluateExpression('customer.age > 18', data);
    expect(result).toEqual({ ok: true, value: true });
  });
});

// ---------------------------------------------------------------------------
// formatForPreview
// ---------------------------------------------------------------------------

describe('formatForPreview', () => {
  it('formats a string', () => {
    expect(formatForPreview('Hello')).toBe('Hello');
  });

  it('formats an empty string as "(empty string)"', () => {
    expect(formatForPreview('')).toBe('(empty string)');
  });

  it('formats undefined as "undefined"', () => {
    expect(formatForPreview(undefined)).toBe('undefined');
  });

  it('formats null as "null"', () => {
    expect(formatForPreview(null)).toBe('null');
  });

  it('formats a number', () => {
    expect(formatForPreview(42)).toBe('42');
  });

  it('formats a boolean', () => {
    expect(formatForPreview(true)).toBe('true');
    expect(formatForPreview(false)).toBe('false');
  });

  it('formats an object as JSON', () => {
    expect(formatForPreview({ a: 1, b: 2 })).toBe('{"a":1,"b":2}');
  });

  it('formats an array as JSON', () => {
    expect(formatForPreview([1, 2, 3])).toBe('[1,2,3]');
  });

  it('truncates long JSON with ellipsis', () => {
    const longArray = Array.from({ length: 100 }, (_, i) => `item-${i}`);
    const result = formatForPreview(longArray);
    expect(result.length).toBeLessThanOrEqual(121); // 120 + ellipsis char
    expect(result.endsWith('…')).toBe(true);
  });

  it('does not truncate short JSON', () => {
    const result = formatForPreview({ x: 1 });
    expect(result).toBe('{"x":1}');
    expect(result.endsWith('…')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// isValidExpression
// ---------------------------------------------------------------------------

describe('isValidExpression', () => {
  it('returns true for a simple path', () => {
    expect(isValidExpression('customer.name')).toBe(true);
  });

  it('returns true for a function call', () => {
    expect(isValidExpression('$sum(items.price)')).toBe(true);
  });

  it('returns true for string concatenation', () => {
    expect(isValidExpression('first & " " & last')).toBe(true);
  });

  it('returns true for a conditional', () => {
    expect(isValidExpression('active ? "Yes" : "No"')).toBe(true);
  });

  it('returns false for invalid syntax', () => {
    expect(isValidExpression('{{broken')).toBe(false);
  });

  it('returns false for empty expression', () => {
    expect(isValidExpression('')).toBe(false);
  });

  it('returns false for whitespace-only expression', () => {
    expect(isValidExpression('   ')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// validateArrayResult
// ---------------------------------------------------------------------------

describe('validateArrayResult', () => {
  it('returns null for an array', () => {
    expect(validateArrayResult([1, 2, 3])).toBeNull();
  });

  it('returns null for an empty array', () => {
    expect(validateArrayResult([])).toBeNull();
  });

  it('returns null for undefined (missing path)', () => {
    expect(validateArrayResult(undefined)).toBeNull();
  });

  it('returns error for a string', () => {
    const result = validateArrayResult('hello');
    expect(result).toContain('must evaluate to an array');
    expect(result).toContain('string');
  });

  it('returns error for a number', () => {
    const result = validateArrayResult(42);
    expect(result).toContain('must evaluate to an array');
    expect(result).toContain('number');
  });

  it('returns error for a boolean', () => {
    const result = validateArrayResult(true);
    expect(result).toContain('must evaluate to an array');
    expect(result).toContain('boolean');
  });

  it('returns error for null', () => {
    const result = validateArrayResult(null);
    expect(result).toContain('must evaluate to an array');
    expect(result).toContain('null');
  });

  it('returns error for an object (single-result filter)', () => {
    const result = validateArrayResult({ name: 'item' });
    expect(result).toContain('must evaluate to an array');
    expect(result).toContain('object');
  });
});

// ---------------------------------------------------------------------------
// validateBooleanResult
// ---------------------------------------------------------------------------

describe('validateBooleanResult', () => {
  it('returns null for true', () => {
    expect(validateBooleanResult(true)).toBeNull();
  });

  it('returns null for false', () => {
    expect(validateBooleanResult(false)).toBeNull();
  });

  it('returns null for undefined (missing path)', () => {
    expect(validateBooleanResult(undefined)).toBeNull();
  });

  it('returns error for a string', () => {
    const result = validateBooleanResult('yes');
    expect(result).toContain('must evaluate to a boolean');
    expect(result).toContain('string');
  });

  it('returns error for a number', () => {
    const result = validateBooleanResult(1);
    expect(result).toContain('must evaluate to a boolean');
    expect(result).toContain('number');
  });

  it('returns error for null', () => {
    const result = validateBooleanResult(null);
    expect(result).toContain('must evaluate to a boolean');
    expect(result).toContain('null');
  });

  it('returns error for an array', () => {
    const result = validateBooleanResult([1, 2]);
    expect(result).toContain('must evaluate to a boolean');
    expect(result).toContain('object');
  });

  it('returns error for an object', () => {
    const result = validateBooleanResult({ active: true });
    expect(result).toContain('must evaluate to a boolean');
    expect(result).toContain('object');
  });
});

// ---------------------------------------------------------------------------
// formatDateValue
// ---------------------------------------------------------------------------

describe('formatDateValue', () => {
  it('formats dd-MM-yyyy', () => {
    expect(formatDateValue('2024-01-15', 'dd-MM-yyyy')).toBe('15-01-2024');
  });

  describe('timezone handling (Option B — matches the PDF renderer)', () => {
    it('shows a naive datetime as-is ("time is time")', () => {
      expect(formatDateValue('2026-05-04T11:32:00', 'HH:mm', 'en-US', 'Europe/Amsterdam')).toBe(
        '11:32',
      );
    });

    it('converts a UTC instant to the render timezone (summer / CEST +02:00)', () => {
      expect(formatDateValue('2026-05-04T11:32:00Z', 'HH:mm', 'en-US', 'Europe/Amsterdam')).toBe(
        '13:32',
      );
    });

    it('converts a UTC instant to the render timezone (winter / CET +01:00)', () => {
      expect(formatDateValue('2026-01-04T11:32:00Z', 'HH:mm', 'en-US', 'Europe/Amsterdam')).toBe(
        '12:32',
      );
    });

    it('converts an explicit offset to the render timezone', () => {
      // 11:32+00:00 == 13:32 in Amsterdam (CEST) in May.
      expect(
        formatDateValue('2026-05-04T11:32:00+00:00', 'HH:mm', 'en-US', 'Europe/Amsterdam'),
      ).toBe('13:32');
    });
  });

  it('formats yyyy-MM-dd (identity)', () => {
    expect(formatDateValue('2024-01-15', 'yyyy-MM-dd')).toBe('2024-01-15');
  });

  it('formats dd/MM/yyyy', () => {
    expect(formatDateValue('2024-01-15', 'dd/MM/yyyy')).toBe('15/01/2024');
  });

  it('formats MM/dd/yyyy', () => {
    expect(formatDateValue('2024-01-15', 'MM/dd/yyyy')).toBe('01/15/2024');
  });

  it('formats d MMMM yyyy', () => {
    expect(formatDateValue('2024-01-15', 'd MMMM yyyy')).toBe('15 January 2024');
  });

  it('formats d MMMM yyyy without leading zero', () => {
    expect(formatDateValue('2024-07-05', 'd MMMM yyyy')).toBe('5 July 2024');
  });

  it('formats d MMM yyyy (short month)', () => {
    expect(formatDateValue('2024-01-15', 'd MMM yyyy')).toBe('15 Jan 2024');
  });

  it('formats EEEE (full weekday name) — 2024-01-15 is a Monday', () => {
    expect(formatDateValue('2024-01-15', 'EEEE MMMM d yyyy')).toBe('Monday January 15 2024');
    expect(formatDateValue('2024-01-15', 'EEEE d MMMM yyyy')).toBe('Monday 15 January 2024');
  });

  it('formats EEE (short weekday name)', () => {
    expect(formatDateValue('2024-01-15', 'EEE d MMM yyyy')).toBe('Mon 15 Jan 2024');
  });

  it('localizes weekday and month names together (nl-NL)', () => {
    expect(formatDateValue('2024-01-15', 'EEEE MMMM d yyyy', 'nl-NL')).toBe(
      'maandag januari 15 2024',
    );
    expect(formatDateValue('2024-01-15', 'EEEE d MMMM yyyy', 'nl-NL')).toBe(
      'maandag 15 januari 2024',
    );
  });

  it('returns raw value for non-date string', () => {
    expect(formatDateValue('not-a-date', 'dd-MM-yyyy')).toBe('not-a-date');
  });

  it('returns raw value for empty string', () => {
    expect(formatDateValue('', 'dd-MM-yyyy')).toBe('');
  });

  // --- datetime support ---

  it('formats a local datetime with date-only pattern', () => {
    expect(formatDateValue('2024-01-15T14:30:00', 'dd-MM-yyyy')).toBe('15-01-2024');
  });

  it('formats a local datetime with date+time pattern', () => {
    expect(formatDateValue('2024-01-15T14:30:00', 'dd-MM-yyyy HH:mm')).toBe('15-01-2024 14:30');
  });

  it('converts a UTC datetime to the default render timezone (CET +01:00 in January)', () => {
    // Option B: an offset-bearing instant is shown in the render timezone
    // (default Europe/Amsterdam), matching the PDF renderer. 14:30Z → 15:30 CET.
    expect(formatDateValue('2024-01-15T14:30:00Z', 'dd-MM-yyyy HH:mm')).toBe('15-01-2024 15:30');
  });

  it('formats time with seconds', () => {
    expect(formatDateValue('2024-01-15T14:30:45', 'HH:mm:ss')).toBe('14:30:45');
  });

  it('defaults time tokens to 00 for date-only values', () => {
    expect(formatDateValue('2024-01-15', 'dd-MM-yyyy HH:mm')).toBe('15-01-2024 00:00');
  });

  // --- locale-aware month names ---

  it('formats d MMMM yyyy in Dutch (lowercase month, the canonical "04 april 2026" case)', () => {
    expect(formatDateValue('2026-04-04', 'dd MMMM yyyy', 'nl-NL')).toBe('04 april 2026');
  });

  it('formats d MMM yyyy in Dutch (short lowercase month)', () => {
    // Dutch short months are lowercase in CLDR: jan, feb, mrt, apr, …
    const out = formatDateValue('2024-04-15', 'd MMM yyyy', 'nl-NL');
    // CLDR has used both "apr" and "apr." over time; assert it starts with "apr"
    // and contains the day + year so the test is stable across ICU versions.
    expect(out.toLowerCase()).toMatch(/^15 apr\.? 2024$/);
  });

  it('formats MMMM in German (capitalized as German convention requires)', () => {
    expect(formatDateValue('2024-03-15', 'dd MMMM yyyy', 'de-DE')).toBe('15 März 2024');
  });

  it('locale defaults to en-US when not provided (back-compat with pre-locale callers)', () => {
    expect(formatDateValue('2024-01-15', 'd MMMM yyyy')).toBe('15 January 2024');
  });

  it('numeric tokens are unaffected by locale (yyyy/MM/dd stay numeric)', () => {
    expect(formatDateValue('2026-04-04', 'yyyy-MM-dd', 'nl-NL')).toBe('2026-04-04');
  });
});

// ---------------------------------------------------------------------------
// $formatDate in evaluateExpression
// ---------------------------------------------------------------------------

describe('evaluateExpression with $formatDate', () => {
  it('formats a date field', async () => {
    const data = { invoiceDate: '2024-01-15' };
    expect(await evaluateExpression("$formatDate(invoiceDate, 'dd-MM-yyyy')", data)).toBe(
      '15-01-2024',
    );
  });

  it('formats in string concatenation', async () => {
    const data = { dueDate: '2024-02-15' };
    expect(await evaluateExpression('"Due: " & $formatDate(dueDate, \'dd-MM-yyyy\')', data)).toBe(
      'Due: 15-02-2024',
    );
  });

  it('returns raw value for non-date', async () => {
    const data = { val: 'hello' };
    expect(await evaluateExpression("$formatDate(val, 'dd-MM-yyyy')", data)).toBe('hello');
  });

  it('returns undefined for missing field', async () => {
    const data = { other: '2024-01-15' };
    expect(await evaluateExpression("$formatDate(missing, 'dd-MM-yyyy')", data)).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// formatLocaleNumberValue
// ---------------------------------------------------------------------------

describe('formatLocaleNumberValue', () => {
  it('formats with en-US grouping and decimal (default)', () => {
    expect(formatLocaleNumberValue(1234.56, '#,##0.00')).toBe('1,234.56');
  });

  it('formats with nl-NL grouping and decimal (the canonical case)', () => {
    expect(formatLocaleNumberValue(1234.56, '#,##0.00', 'nl-NL')).toBe('1.234,56');
  });

  it('formats with de-DE grouping and decimal', () => {
    expect(formatLocaleNumberValue(1234.56, '#,##0.00', 'de-DE')).toBe('1.234,56');
  });

  it('formats an integer with no decimals', () => {
    expect(formatLocaleNumberValue(1234, '#,##0', 'nl-NL')).toBe('1.234');
  });

  it('rounds HALF_EVEN to match the PDF renderer (Java DecimalFormat default)', () => {
    // Banker's rounding: ties go to the nearest even digit, NOT half-up.
    // Java's DecimalFormat (the PDF renderer) does this by default, so the
    // editor preview must agree: 8.5 → 8, not 9.
    expect(formatLocaleNumberValue(8.5, '#,##0', 'nl-NL')).toBe('8');
    expect(formatLocaleNumberValue(9.5, '#,##0', 'nl-NL')).toBe('10');
    expect(formatLocaleNumberValue(2.5, '#,##0', 'nl-NL')).toBe('2');
    expect(formatLocaleNumberValue(2.125, '#,##0.00', 'nl-NL')).toBe('2,12');
  });

  it('formats with optional fraction digits (#)', () => {
    // # = optional digit, so trailing zeros are suppressed
    expect(formatLocaleNumberValue(1234.5, '#,##0.##', 'nl-NL')).toBe('1.234,5');
  });

  it('formats with mixed required + optional fraction digits', () => {
    // 0 = required digit, # = optional → exactly 1 fraction shown, up to 2 max
    expect(formatLocaleNumberValue(1234.5, '#,##0.0#', 'nl-NL')).toBe('1.234,5');
    expect(formatLocaleNumberValue(1234.56, '#,##0.0#', 'nl-NL')).toBe('1.234,56');
  });

  it('formats a percent (multiplies by 100, appends locale percent sign)', () => {
    expect(formatLocaleNumberValue(0.21, '0.0%', 'nl-NL')).toBe('21,0%');
    expect(formatLocaleNumberValue(0.21, '0.0%', 'en-US')).toBe('21.0%');
  });

  it('formats a per-mille (multiplies by 1000)', () => {
    expect(formatLocaleNumberValue(0.0042, '0.00‰', 'en-US')).toBe('4.20‰');
  });

  it('handles a negative number with a locale-aware minus sign', () => {
    expect(formatLocaleNumberValue(-1234.5, '#,##0.00', 'nl-NL')).toBe('-1.234,50');
  });

  it('honours an explicit negative subpattern (parens style)', () => {
    expect(formatLocaleNumberValue(-12.5, '#,##0.00;(#,##0.00)', 'en-US')).toBe('(12.50)');
  });

  it('emits literal characters around the digit block', () => {
    // '$' prefix is a literal in the picture; the digit block slots in after it
    expect(formatLocaleNumberValue(1234.5, '$#,##0.00', 'en-US')).toBe('$1,234.50');
  });

  it('numeric tokens are scale-agnostic (zero comes out as a single 0)', () => {
    expect(formatLocaleNumberValue(0, '#,##0.00', 'nl-NL')).toBe('0,00');
  });

  it('returns the input as a string when value is not a number', () => {
    expect(formatLocaleNumberValue('not-a-number', '#,##0.00')).toBe('not-a-number');
    expect(formatLocaleNumberValue(NaN, '#,##0.00')).toBe('NaN');
  });

  it('locale defaults to en-US when not provided (back-compat)', () => {
    expect(formatLocaleNumberValue(1234.5, '#,##0.00')).toBe('1,234.50');
  });
});

// ---------------------------------------------------------------------------
// $formatLocaleNumber in evaluateExpression
// ---------------------------------------------------------------------------

describe('evaluateExpression with $formatLocaleNumber', () => {
  it("formats a number field with the resolver's locale", async () => {
    const data = { invoiceTotal: 1234.56 };
    expect(
      await evaluateExpression("$formatLocaleNumber(invoiceTotal, '#,##0.00')", data, 'nl-NL'),
    ).toBe('1.234,56');
  });

  it('returns the input when the field is missing', async () => {
    const data = { other: 1 };
    expect(
      await evaluateExpression("$formatLocaleNumber(missing, '#,##0.00')", data, 'nl-NL'),
    ).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Cross-language parity (shared golden fixture)
// ---------------------------------------------------------------------------
//
// $formatLocaleNumber is implemented twice — here (Intl.NumberFormat + a picture
// parser) and in the backend JsonataEvaluator (Java DecimalFormat) — and the two
// MUST agree so the editor preview matches the generated PDF. The golden table
// is the SINGLE source of truth, shared with the Kotlin LocaleNumberParityTest:
// modules/generation/src/test/resources/locale-number-parity.json. Both suites
// assert the same (value, picture, locale) -> expected, so a drift in either
// implementation fails one side. Add a row -> both languages are held to it.

interface ParityCase {
  value: number;
  picture: string;
  locale: string;
  expected: string;
}

const PARITY_FIXTURE_URL = new URL(
  '../../../../../generation/src/test/resources/locale-number-parity.json',
  import.meta.url,
);
const parityCases: ParityCase[] = JSON.parse(
  readFileSync(PARITY_FIXTURE_URL, 'utf-8'),
) as ParityCase[];

describe('formatLocaleNumberValue cross-language parity', () => {
  it('loads a non-empty shared fixture', () => {
    expect(parityCases.length).toBeGreaterThan(0);
  });

  it.each(parityCases)(
    'formatLocaleNumberValue($value, "$picture", "$locale") === "$expected"',
    ({ value, picture, locale, expected }) => {
      expect(formatLocaleNumberValue(value, picture, locale)).toBe(expected);
    },
  );
});
