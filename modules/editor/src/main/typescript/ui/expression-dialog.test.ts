import { describe, it, expect } from 'vitest';
import {
  parseFormatDateExpression,
  wrapFormatDate,
  parseFormatNumberExpression,
  wrapFormatNumber,
  tryParseAsBuilderExpression,
  buildExpression,
  isStaleFieldReference,
} from './expression-dialog.js';
import type { FieldPath } from '../engine/schema-paths.js';

describe('parseFormatDateExpression', () => {
  it('parses a simple field path', () => {
    expect(parseFormatDateExpression("$formatDate(invoiceDate, 'dd-MM-yyyy')")).toEqual({
      fieldPath: 'invoiceDate',
      pattern: 'dd-MM-yyyy',
    });
  });

  it('parses a dotted field path', () => {
    expect(parseFormatDateExpression("$formatDate(customer.birthDate, 'dd-MM-yyyy')")).toEqual({
      fieldPath: 'customer.birthDate',
      pattern: 'dd-MM-yyyy',
    });
  });

  it('parses with spaces around arguments', () => {
    expect(parseFormatDateExpression("$formatDate( invoiceDate , 'dd-MM-yyyy' )")).toEqual({
      fieldPath: 'invoiceDate',
      pattern: 'dd-MM-yyyy',
    });
  });

  it('parses d MMMM yyyy pattern', () => {
    expect(parseFormatDateExpression("$formatDate(date, 'd MMMM yyyy')")).toEqual({
      fieldPath: 'date',
      pattern: 'd MMMM yyyy',
    });
  });

  it('returns null for a bare field path', () => {
    expect(parseFormatDateExpression('invoiceDate')).toBeNull();
  });

  it('returns null for a different function call', () => {
    expect(parseFormatDateExpression('$uppercase(name)')).toBeNull();
  });

  it('returns null for empty string', () => {
    expect(parseFormatDateExpression('')).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// parseFormatNumberExpression
// ---------------------------------------------------------------------------

describe('parseFormatNumberExpression', () => {
  it('parses a simple field path', () => {
    expect(parseFormatNumberExpression("$formatNumber(total, '#,##0.00')")).toEqual({
      fieldPath: 'total',
      pattern: '#,##0.00',
    });
  });

  it('parses a dotted field path', () => {
    expect(parseFormatNumberExpression("$formatNumber(item.price, '#,##0.00')")).toEqual({
      fieldPath: 'item.price',
      pattern: '#,##0.00',
    });
  });

  it('parses with spaces around arguments', () => {
    expect(parseFormatNumberExpression("$formatNumber( total , '#,##0.00' )")).toEqual({
      fieldPath: 'total',
      pattern: '#,##0.00',
    });
  });

  it('parses percentage pattern', () => {
    expect(parseFormatNumberExpression("$formatNumber(rate, '0%')")).toEqual({
      fieldPath: 'rate',
      pattern: '0%',
    });
  });

  it('returns null for a bare field path', () => {
    expect(parseFormatNumberExpression('total')).toBeNull();
  });

  it('returns null for a $formatDate call', () => {
    expect(parseFormatNumberExpression("$formatDate(date, 'dd-MM-yyyy')")).toBeNull();
  });

  it('returns null for empty string', () => {
    expect(parseFormatNumberExpression('')).toBeNull();
  });
});

describe('wrapFormatNumber', () => {
  it('wraps a simple field', () => {
    expect(wrapFormatNumber('total', '#,##0.00')).toBe("$formatNumber(total, '#,##0.00')");
  });

  it('wraps a dotted field path', () => {
    expect(wrapFormatNumber('item.price', '0.00')).toBe("$formatNumber(item.price, '0.00')");
  });
});

describe('wrapFormatDate', () => {
  it('wraps a simple field', () => {
    expect(wrapFormatDate('invoiceDate', 'dd-MM-yyyy')).toBe(
      "$formatDate(invoiceDate, 'dd-MM-yyyy')",
    );
  });

  it('wraps a dotted field path', () => {
    expect(wrapFormatDate('customer.birthDate', 'dd/MM/yyyy')).toBe(
      "$formatDate(customer.birthDate, 'dd/MM/yyyy')",
    );
  });
});

// ---------------------------------------------------------------------------
// tryParseAsBuilderExpression
// ---------------------------------------------------------------------------

const testFieldPaths: FieldPath[] = [
  { path: 'name', type: 'string' },
  { path: 'invoiceDate', type: 'date' },
  { path: 'customer.birthDate', type: 'date' },
  { path: 'total', type: 'number' },
];

describe('tryParseAsBuilderExpression', () => {
  it('parses a bare field path', () => {
    expect(tryParseAsBuilderExpression('name', testFieldPaths)).toEqual({
      fieldPath: 'name',
      fieldType: 'string',
      formatType: 'none',
      formatPattern: '',
    });
  });

  it('parses a $formatDate expression', () => {
    expect(
      tryParseAsBuilderExpression("$formatDate(invoiceDate, 'dd-MM-yyyy')", testFieldPaths),
    ).toEqual({
      fieldPath: 'invoiceDate',
      fieldType: 'date',
      formatType: 'date',
      formatPattern: 'dd-MM-yyyy',
    });
  });

  it('parses a dotted $formatDate expression', () => {
    expect(
      tryParseAsBuilderExpression("$formatDate(customer.birthDate, 'd MMMM yyyy')", testFieldPaths),
    ).toEqual({
      fieldPath: 'customer.birthDate',
      fieldType: 'date',
      formatType: 'date',
      formatPattern: 'd MMMM yyyy',
    });
  });

  it('returns null for complex expression', () => {
    expect(tryParseAsBuilderExpression('name & " " & total', testFieldPaths)).toBeNull();
  });

  it('returns null for field not in fieldPaths', () => {
    expect(tryParseAsBuilderExpression('unknown', testFieldPaths)).toBeNull();
  });

  it('returns null for empty expression', () => {
    expect(tryParseAsBuilderExpression('', testFieldPaths)).toBeNull();
  });

  it('returns null for whitespace-only expression', () => {
    expect(tryParseAsBuilderExpression('   ', testFieldPaths)).toBeNull();
  });

  // --- scoped field paths ---

  it('parses a scoped field path', () => {
    const withScoped = [...testFieldPaths, { path: 'item.name', type: 'string', scope: 'item' }];
    expect(tryParseAsBuilderExpression('item.name', withScoped)).toEqual({
      fieldPath: 'item.name',
      fieldType: 'string',
      formatType: 'none',
      formatPattern: '',
    });
  });

  it('parses $formatDate on a scoped date field', () => {
    const withScoped = [...testFieldPaths, { path: 'item.date', type: 'date', scope: 'item' }];
    expect(tryParseAsBuilderExpression("$formatDate(item.date, 'dd-MM-yyyy')", withScoped)).toEqual(
      {
        fieldPath: 'item.date',
        fieldType: 'date',
        formatType: 'date',
        formatPattern: 'dd-MM-yyyy',
      },
    );
  });

  // --- $formatNumber ---

  it('parses a $formatNumber expression', () => {
    expect(tryParseAsBuilderExpression("$formatNumber(total, '#,##0.00')", testFieldPaths)).toEqual(
      {
        fieldPath: 'total',
        fieldType: 'number',
        formatType: 'number',
        formatPattern: '#,##0.00',
      },
    );
  });

  it('parses $formatNumber on a scoped number field', () => {
    const withScoped = [...testFieldPaths, { path: 'item.price', type: 'number', scope: 'item' }];
    expect(
      tryParseAsBuilderExpression("$formatNumber(item.price, '#,##0.00')", withScoped),
    ).toEqual({
      fieldPath: 'item.price',
      fieldType: 'number',
      formatType: 'number',
      formatPattern: '#,##0.00',
    });
  });
});

// ---------------------------------------------------------------------------
// buildExpression
// ---------------------------------------------------------------------------

describe('buildExpression', () => {
  it('builds a bare field reference', () => {
    expect(
      buildExpression({
        fieldPath: 'name',
        fieldType: 'string',
        formatType: 'none',
        formatPattern: '',
      }),
    ).toBe('name');
  });

  it('builds a date-formatted expression', () => {
    expect(
      buildExpression({
        fieldPath: 'invoiceDate',
        fieldType: 'date',
        formatType: 'date',
        formatPattern: 'dd-MM-yyyy',
      }),
    ).toBe("$formatDate(invoiceDate, 'dd-MM-yyyy')");
  });

  it('builds bare field when formatPattern is empty even if formatType is date', () => {
    expect(
      buildExpression({
        fieldPath: 'invoiceDate',
        fieldType: 'date',
        formatType: 'date',
        formatPattern: '',
      }),
    ).toBe('invoiceDate');
  });

  it('builds a number-formatted expression', () => {
    expect(
      buildExpression({
        fieldPath: 'total',
        fieldType: 'number',
        formatType: 'number',
        formatPattern: '#,##0.00',
      }),
    ).toBe("$formatNumber(total, '#,##0.00')");
  });

  it('builds bare field when formatPattern is empty even if formatType is number', () => {
    expect(
      buildExpression({
        fieldPath: 'total',
        fieldType: 'number',
        formatType: 'number',
        formatPattern: '',
      }),
    ).toBe('total');
  });
});

// ---------------------------------------------------------------------------
// isStaleFieldReference
// ---------------------------------------------------------------------------

describe('isStaleFieldReference', () => {
  it('returns true for a simple path not in field paths', () => {
    expect(isStaleFieldReference('item.name', testFieldPaths)).toBe(true);
  });

  it('returns true for a $formatDate with unknown field', () => {
    expect(isStaleFieldReference("$formatDate(item.date, 'dd-MM-yyyy')", testFieldPaths)).toBe(
      true,
    );
  });

  it('returns false for a known field', () => {
    expect(isStaleFieldReference('name', testFieldPaths)).toBe(false);
  });

  it('returns false for a complex expression', () => {
    expect(isStaleFieldReference('name & " " & total', testFieldPaths)).toBe(false);
  });

  it('returns false for empty string', () => {
    expect(isStaleFieldReference('', testFieldPaths)).toBe(false);
  });

  it('returns false for a $formatDate with known field', () => {
    expect(isStaleFieldReference("$formatDate(invoiceDate, 'dd-MM-yyyy')", testFieldPaths)).toBe(
      false,
    );
  });

  it('returns true for a $formatNumber with unknown field', () => {
    expect(isStaleFieldReference("$formatNumber(item.price, '#,##0.00')", testFieldPaths)).toBe(
      true,
    );
  });

  it('returns false for a $formatNumber with known field', () => {
    expect(isStaleFieldReference("$formatNumber(total, '#,##0.00')", testFieldPaths)).toBe(false);
  });
});
