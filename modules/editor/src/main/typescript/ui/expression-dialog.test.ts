import { describe, it, expect } from 'vitest';
import {
  parseFormatDateExpression,
  wrapFormatDate,
  tryParseAsBuilderExpression,
  buildExpression,
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
});
