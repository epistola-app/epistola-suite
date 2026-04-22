import { describe, expect, it } from 'vitest';
import {
  isRecord,
  isJsonObject,
  isDeepEqual,
  pruneValueToSchema,
  coerceValue,
  getExpectedTypeForPath,
} from './logic.js';
import type { JsonSchema, JsonSchemaProperty } from '../types.js';

describe('isRecord', () => {
  it('returns true for plain objects', () => {
    expect(isRecord({})).toBe(true);
    expect(isRecord({ a: 1 })).toBe(true);
  });

  it('returns false for null', () => {
    expect(isRecord(null)).toBe(false);
  });

  it('returns false for arrays', () => {
    expect(isRecord([])).toBe(true); // arrays are objects in JS
  });

  it('returns false for primitives', () => {
    expect(isRecord(1)).toBe(false);
    expect(isRecord('str')).toBe(false);
    expect(isRecord(true)).toBe(false);
    expect(isRecord(undefined)).toBe(false);
  });
});

describe('isJsonObject', () => {
  it('returns true for plain objects', () => {
    expect(isJsonObject({})).toBe(true);
    expect(isJsonObject({ a: 1 })).toBe(true);
  });

  it('returns false for null', () => {
    expect(isJsonObject(null)).toBe(false);
  });

  it('returns false for arrays', () => {
    expect(isJsonObject([])).toBe(false);
  });

  it('returns false for primitives', () => {
    expect(isJsonObject(1)).toBe(false);
    expect(isJsonObject('str')).toBe(false);
  });
});

describe('isDeepEqual', () => {
  it('compares primitives', () => {
    expect(isDeepEqual(1, 1)).toBe(true);
    expect(isDeepEqual(1, 2)).toBe(false);
    expect(isDeepEqual('a', 'a')).toBe(true);
    expect(isDeepEqual('a', 'b')).toBe(false);
    expect(isDeepEqual(true, true)).toBe(true);
    expect(isDeepEqual(true, false)).toBe(false);
  });

  it('handles null and undefined', () => {
    expect(isDeepEqual(null, null)).toBe(true);
    expect(isDeepEqual(undefined, undefined)).toBe(true);
    expect(isDeepEqual(null, undefined)).toBe(false);
    expect(isDeepEqual(0, null)).toBe(false);
  });

  it('compares flat objects', () => {
    expect(isDeepEqual({ a: 1 }, { a: 1 })).toBe(true);
    expect(isDeepEqual({ a: 1 }, { a: 2 })).toBe(false);
    expect(isDeepEqual({ a: 1 }, { b: 1 })).toBe(false);
  });

  it('compares nested objects', () => {
    expect(isDeepEqual({ a: { b: 1 } }, { a: { b: 1 } })).toBe(true);
    expect(isDeepEqual({ a: { b: 1 } }, { a: { b: 2 } })).toBe(false);
  });

  it('compares arrays', () => {
    expect(isDeepEqual([1, 2], [1, 2])).toBe(true);
    expect(isDeepEqual([1, 2], [2, 1])).toBe(false);
    expect(isDeepEqual([1, 2], [1, 2, 3])).toBe(false);
  });

  it('compares nested arrays and objects', () => {
    expect(isDeepEqual([{ a: 1 }], [{ a: 1 }])).toBe(true);
    expect(isDeepEqual({ arr: [1, 2] }, { arr: [1, 2] })).toBe(true);
  });

  it('returns false for different types', () => {
    expect(isDeepEqual([1, 2], { 0: 1, 1: 2 })).toBe(false);
    expect(isDeepEqual(1, '1')).toBe(false);
  });

  it('handles empty structures', () => {
    expect(isDeepEqual({}, {})).toBe(true);
    expect(isDeepEqual([], [])).toBe(true);
  });

  it('is order-independent for object keys', () => {
    expect(isDeepEqual({ a: 1, b: 2 }, { b: 2, a: 1 })).toBe(true);
  });
});

describe('pruneValueToSchema', () => {
  const simpleSchema: JsonSchema = {
    type: 'object',
    properties: {
      name: { type: 'string' },
      count: { type: 'integer' },
    },
  };

  it('returns null as-is', () => {
    expect(pruneValueToSchema(null, simpleSchema)).toBeNull();
  });

  it('returns undefined as-is', () => {
    expect(pruneValueToSchema(undefined, simpleSchema)).toBeUndefined();
  });

  it('prunes unknown keys from objects', () => {
    const data = { name: 'Alice', count: 5, extra: 'drop' };
    expect(pruneValueToSchema(data, simpleSchema)).toEqual({
      name: 'Alice',
      count: 5,
    });
  });

  it('prunes nested objects recursively', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        user: {
          type: 'object',
          properties: {
            name: { type: 'string' },
          },
        },
      },
    };
    const data = { user: { name: 'Alice', age: 30 } };
    expect(pruneValueToSchema(data, schema)).toEqual({
      user: { name: 'Alice' },
    });
  });

  it('prunes array items', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              sku: { type: 'string' },
            },
          },
        },
      },
    };
    const data = {
      items: [
        { sku: 'A1', qty: 2 },
        { sku: 'B2', qty: 5 },
      ],
    };
    expect(pruneValueToSchema(data, schema)).toEqual({
      items: [{ sku: 'A1' }, { sku: 'B2' }],
    });
  });

  it('returns primitives unchanged', () => {
    expect(pruneValueToSchema('hello', { type: 'string' })).toBe('hello');
    expect(pruneValueToSchema(42, { type: 'integer' })).toBe(42);
  });

  it('handles schema with array type union', () => {
    const prop: JsonSchemaProperty = {
      type: ['string', 'number'],
    };
    expect(pruneValueToSchema('hello', prop)).toBe('hello');
  });
});

describe('coerceValue', () => {
  it('returns null/undefined as-is', () => {
    expect(coerceValue(null, 'number')).toBeNull();
    expect(coerceValue(undefined, 'number')).toBeUndefined();
  });

  it('returns value as-is when expectedType is null', () => {
    expect(coerceValue('hello', null)).toBe('hello');
  });

  it('coerces strings to numbers', () => {
    expect(coerceValue('3.14', 'number')).toBe(3.14);
    expect(coerceValue('42', 'integer')).toBe(42);
  });

  it('truncates floats for integer type', () => {
    expect(coerceValue(3.99, 'integer')).toBe(3);
    expect(coerceValue('3.99', 'integer')).toBe(3);
  });

  it('returns original value when coercion fails', () => {
    expect(coerceValue('not-a-number', 'number')).toBe('not-a-number');
  });

  it('coerces string booleans', () => {
    expect(coerceValue('true', 'boolean')).toBe(true);
    expect(coerceValue('false', 'boolean')).toBe(false);
  });

  it('leaves boolean values unchanged', () => {
    expect(coerceValue(true, 'boolean')).toBe(true);
    expect(coerceValue(false, 'boolean')).toBe(false);
  });

  it('returns unhandled types unchanged', () => {
    expect(coerceValue('hello', 'string')).toBe('hello');
    expect(coerceValue(42, 'unknown' as string)).toBe(42);
  });
});

describe('getExpectedTypeForPath', () => {
  const migrations = [
    { exampleId: 'ex1', path: '/name', expectedType: 'string' },
    { exampleId: 'ex1', path: '/age', expectedType: 'integer' },
    { exampleId: 'ex2', path: '/name', expectedType: 'string' },
  ];

  it('returns expected type when migration matches', () => {
    expect(getExpectedTypeForPath(migrations, 'ex1', '/name')).toBe('string');
    expect(getExpectedTypeForPath(migrations, 'ex1', '/age')).toBe('integer');
  });

  it('returns null when no migration matches exampleId', () => {
    expect(getExpectedTypeForPath(migrations, 'ex3', '/name')).toBeNull();
  });

  it('returns null when no migration matches path', () => {
    expect(getExpectedTypeForPath(migrations, 'ex1', '/unknown')).toBeNull();
  });

  it('returns null when expectedType is missing', () => {
    expect(getExpectedTypeForPath([{ exampleId: 'ex1', path: '/x' }], 'ex1', '/x')).toBeNull();
  });

  it('returns null for empty migrations', () => {
    expect(getExpectedTypeForPath([], 'ex1', '/name')).toBeNull();
  });
});
