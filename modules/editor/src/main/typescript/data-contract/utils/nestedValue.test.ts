import { describe, expect, it } from 'vitest';
import {
  normalizePath,
  getNestedValue,
  setNestedValue,
  deleteNestedValue,
  validationPathToFormPath,
} from './nestedValue.js';

describe('normalizePath', () => {
  it('converts slash prefix to dot notation', () => {
    expect(normalizePath('/customer/city')).toBe('customer.city');
  });

  it('removes trailing slash', () => {
    expect(normalizePath('customer/city/')).toBe('customer.city');
  });

  it('converts mixed slashes to dots', () => {
    expect(normalizePath('/customer/address/city/')).toBe('customer.address.city');
  });

  it('keeps dot paths unchanged', () => {
    expect(normalizePath('customer.address.0')).toBe('customer.address.0');
  });

  it('handles empty string', () => {
    expect(normalizePath('')).toBe('');
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

  it('handles path without $. prefix', () => {
    expect(validationPathToFormPath('name')).toBe('name');
  });
});

describe('getNestedValue', () => {
  it('reads nested object values', () => {
    const data = { user: { name: 'Alice' } };
    expect(getNestedValue(data, 'user.name')).toBe('Alice');
  });

  it('reads array values by index', () => {
    const data = { items: ['a', 'b', 'c'] };
    expect(getNestedValue(data, 'items.1')).toBe('b');
  });

  it('reads deeply nested paths', () => {
    const data = {
      user: {
        profile: {
          addresses: [{ city: 'Tokyo' }],
        },
      },
    };
    expect(getNestedValue(data, 'user.profile.addresses.0.city')).toBe('Tokyo');
  });

  it('returns undefined for missing path', () => {
    const data = { user: { name: 'Alice' } };
    expect(getNestedValue(data, 'user.age')).toBeUndefined();
  });

  it('returns undefined for invalid array segment', () => {
    const data = { items: [{ name: 'A' }] };
    expect(getNestedValue(data, 'items.one.name')).toBeUndefined();
  });

  it('returns undefined for null intermediate', () => {
    const data = { user: null };
    expect(getNestedValue(data, 'user.name')).toBeUndefined();
  });

  it('returns undefined for primitive intermediate', () => {
    const data = { user: 'Alice' };
    expect(getNestedValue(data, 'user.name')).toBeUndefined();
  });

  it('returns undefined for empty path', () => {
    const data = { user: 'Alice' };
    expect(getNestedValue(data, '')).toBeUndefined();
  });

  it('returns undefined when value is not JSON-compatible', () => {
    const data = { fn: () => 'hello' };
    expect(getNestedValue(data, 'fn')).toBeUndefined();
  });

  it('returns null when final value is null', () => {
    const data = { user: null };
    expect(getNestedValue(data, 'user')).toBeNull();
  });
});

describe('setNestedValue', () => {
  it('sets nested object value immutably', () => {
    const source = { user: { name: 'Alice' } };
    const updated = setNestedValue(source, 'user.name', 'Bob');

    expect(updated).toEqual({ user: { name: 'Bob' } });
    expect(source).toEqual({ user: { name: 'Alice' } });
  });

  it('creates missing intermediate objects', () => {
    const source = {};
    const updated = setNestedValue(source, 'user.name', 'Alice');

    expect(updated).toEqual({ user: { name: 'Alice' } });
  });

  it('creates intermediate arrays for numeric segments', () => {
    const source = {};
    const updated = setNestedValue(source, 'items.0.name', 'First');

    expect(updated).toEqual({ items: [{ name: 'First' }] });
  });

  it('creates multiple array items', () => {
    const source = {};
    const updated = setNestedValue(source, 'items.2.name', 'Third');

    expect(updated).toEqual({ items: [undefined, undefined, { name: 'Third' }] });
  });

  it('replaces primitive with object when needed', () => {
    const source = { user: 'Alice' };
    const updated = setNestedValue(source, 'user.name', 'Bob');

    expect(updated).toEqual({ user: { name: 'Bob' } });
  });

  it('returns original for empty path', () => {
    const source = { user: 'Alice' };
    const updated = setNestedValue(source, '', 'Bob');

    expect(updated).toBe(source);
  });

  it('sets root-level value', () => {
    const source = { name: 'Alice' };
    const updated = setNestedValue(source, 'name', 'Bob');

    expect(updated).toEqual({ name: 'Bob' });
    expect(source).toEqual({ name: 'Alice' });
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

  it('returns unchanged clone for out-of-bounds array index', () => {
    const source = { items: [{ id: 1 }] };
    const updated = deleteNestedValue(source, 'items.5');

    expect(updated).toEqual(source);
    expect(updated).not.toBe(source);
  });

  it('returns unchanged clone for negative array index', () => {
    const source = { items: [{ id: 1 }] };
    const updated = deleteNestedValue(source, 'items.-1');

    expect(updated).toEqual(source);
    expect(updated).not.toBe(source);
  });

  it('returns structured clone for empty path', () => {
    const source = { user: { name: 'Alice' } };
    const updated = deleteNestedValue(source, '');

    expect(updated).toEqual(source);
    expect(updated).not.toBe(source);
  });

  it('returns original object when intermediate is not container', () => {
    const source = { user: 'Alice' };
    const updated = deleteNestedValue(source, 'user.name');

    expect(updated).toBe(source);
  });

  it('returns unchanged clone when intermediate array item is not container', () => {
    const source = { items: ['a', 'b'] };
    const updated = deleteNestedValue(source, 'items.0.nested');

    expect(updated).toEqual(source);
    expect(updated).not.toBe(source);
  });

  it('deletes nested array item inside object', () => {
    const source = { user: { tags: ['a', 'b', 'c'] } };
    const updated = deleteNestedValue(source, 'user.tags.1');

    expect(updated).toEqual({ user: { tags: ['a', 'c'] } });
  });

  it('deletes deeply nested value inside array item', () => {
    const source = { items: [{ name: 'Alice' }, { name: 'Bob' }] };
    const updated = deleteNestedValue(source, 'items.0.name');

    expect(updated).toEqual({ items: [{}, { name: 'Bob' }] });
    expect(source).toEqual({ items: [{ name: 'Alice' }, { name: 'Bob' }] });
  });
});
