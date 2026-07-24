// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import {
  validationPathToFormPath,
  buildFieldErrorMap,
  hasChildErrors,
  toDateTimeLocal,
  dateTimeOffset,
  combineDateTime,
} from './ExampleForm.js';
import type { SchemaValidationError } from '../utils/schemaValidation.js';

describe('toDateTimeLocal', () => {
  it('keeps a zoneless local date-time for the picker', () => {
    expect(toDateTimeLocal('2026-02-18T09:30:00')).toBe('2026-02-18T09:30:00');
    expect(toDateTimeLocal('2026-02-18T09:30')).toBe('2026-02-18T09:30');
  });

  it('strips a zone designator for display (re-applied on save)', () => {
    expect(toDateTimeLocal('2026-02-18T09:30:00Z')).toBe('2026-02-18T09:30:00');
    expect(toDateTimeLocal('2026-02-18T09:30:00+02:00')).toBe('2026-02-18T09:30:00');
  });

  it('widens a date-only value to midnight so it still renders', () => {
    expect(toDateTimeLocal('2026-02-18')).toBe('2026-02-18T00:00');
  });

  it('returns empty for unparseable / empty / non-string input', () => {
    expect(toDateTimeLocal('not a date')).toBe('');
    expect(toDateTimeLocal('')).toBe('');
    expect(toDateTimeLocal(undefined)).toBe('');
    expect(toDateTimeLocal(42)).toBe('');
  });
});

describe('dateTimeOffset', () => {
  it('extracts the zone designator for the offset dropdown', () => {
    expect(dateTimeOffset('2026-02-18T09:30:00Z')).toBe('Z');
    expect(dateTimeOffset('2026-02-18T09:30:00+02:00')).toBe('+02:00');
    expect(dateTimeOffset('2026-02-18T09:30:00-05:00')).toBe('-05:00');
  });

  it('returns empty for a naive value or non-string', () => {
    expect(dateTimeOffset('2026-02-18T09:30:00')).toBe('');
    expect(dateTimeOffset(undefined)).toBe('');
    expect(dateTimeOffset(42)).toBe('');
  });

  it('normalizes +00:00 to Z so UTC stays representable in the dropdown', () => {
    expect(dateTimeOffset('2026-02-18T09:30:00+00:00')).toBe('Z');
  });
});

describe('combineDateTime', () => {
  it('fills in seconds the control omitted', () => {
    expect(combineDateTime('2026-02-18T09:30', '')).toBe('2026-02-18T09:30:00');
  });

  it('appends the chosen offset (UTC or numeric)', () => {
    expect(combineDateTime('2026-02-18T10:00', 'Z')).toBe('2026-02-18T10:00:00Z');
    expect(combineDateTime('2026-02-18T10:00', '+02:00')).toBe('2026-02-18T10:00:00+02:00');
  });

  it('stays naive when no offset is chosen ("time is time")', () => {
    expect(combineDateTime('2026-02-18T10:00', '')).toBe('2026-02-18T10:00:00');
    expect(combineDateTime('2026-02-18T10:00:30', '')).toBe('2026-02-18T10:00:30');
  });

  it('returns empty for an empty local part', () => {
    expect(combineDateTime('', 'Z')).toBe('');
  });
});

describe('validationPathToFormPath', () => {
  it('strips leading $. prefix', () => {
    expect(validationPathToFormPath('$.name')).toBe('name');
  });

  it('converts bracket notation to dot notation', () => {
    expect(validationPathToFormPath('$.items[0]')).toBe('items.0');
  });

  it('handles nested paths with arrays', () => {
    expect(validationPathToFormPath('$.users[0].email')).toBe('users.0.email');
  });

  it('handles deeply nested paths', () => {
    expect(validationPathToFormPath('$.a.b[1].c[2].d')).toBe('a.b.1.c.2.d');
  });

  it('handles simple root-level field', () => {
    expect(validationPathToFormPath('$.firstName')).toBe('firstName');
  });

  it('handles path without $. prefix gracefully', () => {
    expect(validationPathToFormPath('name')).toBe('name');
  });
});

describe('buildFieldErrorMap', () => {
  it('returns empty map for empty errors', () => {
    const map = buildFieldErrorMap([]);
    expect(map.size).toBe(0);
  });

  it('maps validation paths to form paths', () => {
    const errors: SchemaValidationError[] = [
      { path: '$.name', message: 'is required' },
      { path: '$.age', message: 'must be integer' },
    ];
    const map = buildFieldErrorMap(errors);
    expect(map.get('name')).toBe('is required');
    expect(map.get('age')).toBe('must be integer');
  });

  it('handles array paths', () => {
    const errors: SchemaValidationError[] = [{ path: '$.items[0]', message: 'must be string' }];
    const map = buildFieldErrorMap(errors);
    expect(map.get('items.0')).toBe('must be string');
  });

  it('keeps first error per path (deduplicates)', () => {
    const errors: SchemaValidationError[] = [
      { path: '$.name', message: 'first error' },
      { path: '$.name', message: 'second error' },
    ];
    const map = buildFieldErrorMap(errors);
    expect(map.get('name')).toBe('first error');
  });

  it('handles nested object paths', () => {
    const errors: SchemaValidationError[] = [
      { path: '$.address.street', message: 'is required' },
      { path: '$.users[0].email', message: 'must be string' },
    ];
    const map = buildFieldErrorMap(errors);
    expect(map.get('address.street')).toBe('is required');
    expect(map.get('users.0.email')).toBe('must be string');
  });
});

describe('hasChildErrors', () => {
  it('returns false for empty errors map', () => {
    expect(hasChildErrors('address', new Map())).toBe(false);
  });

  it('returns true when path itself has an error', () => {
    const errors = new Map([['address', 'is required']]);
    expect(hasChildErrors('address', errors)).toBe(true);
  });

  it('returns true when a child path has an error', () => {
    const errors = new Map([['address.street', 'is required']]);
    expect(hasChildErrors('address', errors)).toBe(true);
  });

  it('returns true for deeply nested child errors', () => {
    const errors = new Map([['users.0.address.zip', 'must be string']]);
    expect(hasChildErrors('users', errors)).toBe(true);
    expect(hasChildErrors('users.0', errors)).toBe(true);
    expect(hasChildErrors('users.0.address', errors)).toBe(true);
  });

  it('returns false when no matching paths', () => {
    const errors = new Map([['name', 'is required']]);
    expect(hasChildErrors('address', errors)).toBe(false);
  });

  it('does not match partial path prefixes', () => {
    const errors = new Map([['addressExtra.street', 'is required']]);
    expect(hasChildErrors('address', errors)).toBe(false);
  });
});
