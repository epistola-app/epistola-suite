import { describe, expect, it } from 'vitest';
import {
  buildFieldErrorMap,
  canClearOptionalField,
  deleteNestedValue,
  getNestedValue,
  hasFieldValue,
  hasChildErrors,
  normalizePath,
  setNestedValue,
  validationPathToFormPath,
} from './ExampleForm.js';
import type { SchemaValidationError } from '../utils/schemaValidation.js';

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

describe('normalizePath', () => {
  it('converts slash paths to dot paths', () => {
    expect(normalizePath('/customer/address/city')).toBe('customer.address.city');
  });

  it('keeps dot paths unchanged except slash cleanup', () => {
    expect(normalizePath('customer.address.0')).toBe('customer.address.0');
  });
});

describe('getNestedValue', () => {
  it('reads nested object and array values', () => {
    const data = {
      user: {
        profile: {
          addresses: [{ city: 'Tokyo' }],
        },
      },
    };

    expect(getNestedValue(data, 'user.profile.addresses.0.city')).toBe('Tokyo');
  });

  it('returns undefined for invalid array segment', () => {
    const data = { items: [{ name: 'A' }] };

    expect(getNestedValue(data, 'items.one.name')).toBeUndefined();
  });
});

describe('setNestedValue', () => {
  it('creates missing intermediate objects and arrays immutably', () => {
    const source = { user: {} };

    const updated = setNestedValue(source, 'user.addresses.0.city', 'Berlin');

    expect(updated).toEqual({ user: { addresses: [{ city: 'Berlin' }] } });
    expect(source).toEqual({ user: {} });
  });
});

describe('deleteNestedValue', () => {
  it('removes object property when path exists', () => {
    const source = { user: { name: 'Alice', age: 30 } };

    const updated = deleteNestedValue(source, 'user.age');

    expect(updated).toEqual({ user: { name: 'Alice' } });
    expect(source).toEqual({ user: { name: 'Alice', age: 30 } });
  });

  it('removes array item when index path exists', () => {
    const source = { items: [{ id: 1 }, { id: 2 }, { id: 3 }] };

    const updated = deleteNestedValue(source, 'items.1');

    expect(updated).toEqual({ items: [{ id: 1 }, { id: 3 }] });
  });

  it('returns unchanged clone when path does not exist', () => {
    const source = { user: { name: 'Alice' } };

    const updated = deleteNestedValue(source, 'user.address.city');

    expect(updated).toEqual(source);
    expect(updated).not.toBe(source);
  });
});

describe('optional clear behavior', () => {
  it('hasFieldValue treats only null and undefined as empty', () => {
    const missingValue = (() : void => {
      return;
    })();

    expect(hasFieldValue(missingValue)).toBe(false);
    expect(hasFieldValue(null)).toBe(false);
    expect(hasFieldValue('')).toBe(true);
    expect(hasFieldValue(false)).toBe(true);
    expect(hasFieldValue(0)).toBe(true);
  });

  it('canClearOptionalField respects required flag and value presence', () => {
    expect(canClearOptionalField(false, '')).toBe(true);
    expect(canClearOptionalField(false, false)).toBe(true);
    expect(canClearOptionalField(false, 0)).toBe(true);
    expect(canClearOptionalField(false)).toBe(false);
    expect(canClearOptionalField(false, null)).toBe(false);
    expect(canClearOptionalField(true, 'value')).toBe(false);
  });
});
