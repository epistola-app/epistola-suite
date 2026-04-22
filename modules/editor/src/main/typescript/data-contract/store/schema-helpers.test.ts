import { describe, expect, it } from 'vitest';
import {
  getProperties,
  getItems,
  isJsonSchemaProperty,
  getPropertySchema,
  buildDefaultValue,
} from './schema-helpers.js';
import type { JsonSchema, JsonSchemaProperty } from '../types.js';

describe('getProperties', () => {
  it('returns properties object when valid', () => {
    expect(getProperties({ properties: { a: {} } })).toEqual({ a: {} });
  });

  it('returns null when properties is missing', () => {
    expect(getProperties({})).toBeNull();
  });

  it('returns null when properties is not an object', () => {
    expect(getProperties({ properties: 'bad' })).toBeNull();
  });
});

describe('getItems', () => {
  it('returns items object when valid', () => {
    expect(getItems({ items: { type: 'string' } })).toEqual({ type: 'string' });
  });

  it('returns null when items is missing', () => {
    expect(getItems({})).toBeNull();
  });

  it('returns null when items is not an object', () => {
    expect(getItems({ items: 'bad' })).toBeNull();
  });
});

describe('isJsonSchemaProperty', () => {
  it('returns true for object with string type', () => {
    expect(isJsonSchemaProperty({ type: 'string' })).toBe(true);
  });

  it('returns true for object with array type', () => {
    expect(isJsonSchemaProperty({ type: ['string', 'number'] })).toBe(true);
  });

  it('returns false for null', () => {
    expect(isJsonSchemaProperty(null)).toBe(false);
  });

  it('returns false for primitives', () => {
    expect(isJsonSchemaProperty('string')).toBe(false);
    expect(isJsonSchemaProperty(42)).toBe(false);
  });

  it('returns false for array type with non-string items', () => {
    expect(isJsonSchemaProperty({ type: [1, 2] })).toBe(false);
  });
});

describe('getPropertySchema', () => {
  const schema: JsonSchema = {
    type: 'object',
    properties: {
      name: { type: 'string' },
      address: {
        type: 'object',
        properties: {
          city: { type: 'string' },
          zip: { type: 'integer' },
        },
      },
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

  it('finds root-level property', () => {
    expect(getPropertySchema(schema, 'name')).toEqual({ type: 'string' });
  });

  it('finds nested object property', () => {
    expect(getPropertySchema(schema, 'address.city')).toEqual({ type: 'string' });
    expect(getPropertySchema(schema, 'address.zip')).toEqual({ type: 'integer' });
  });

  it('finds array item schema via numeric segment', () => {
    expect(getPropertySchema(schema, 'items.0')).toEqual({
      type: 'object',
      properties: { sku: { type: 'string' } },
    });
  });

  it('finds nested property inside array items', () => {
    expect(getPropertySchema(schema, 'items.0.sku')).toEqual({ type: 'string' });
  });

  it('returns null for unknown path', () => {
    expect(getPropertySchema(schema, 'unknown')).toBeNull();
  });

  it('returns null for deeply unknown path', () => {
    expect(getPropertySchema(schema, 'address.unknown')).toBeNull();
  });

  it('returns null for non-existent array index path', () => {
    expect(getPropertySchema(schema, 'unknown.0')).toBeNull();
  });

  it('returns null when intermediate is not an object', () => {
    expect(getPropertySchema(schema, 'name.invalid')).toBeNull();
  });

  it('returns null when numeric intermediate has no items schema', () => {
    const schemaNoItems: JsonSchema = {
      type: 'object',
      properties: {
        items: { type: 'array' },
      },
    };
    expect(getPropertySchema(schemaNoItems, 'items.0.sku')).toBeNull();
  });

  it('returns null when intermediate property not found and no items fallback', () => {
    const schemaNoFallback: JsonSchema = {
      type: 'object',
      properties: {
        data: { type: 'object', properties: {} },
      },
    };
    expect(getPropertySchema(schemaNoFallback, 'data.unknown')).toBeNull();
  });

  it('finds property through items fallback for intermediate segment', () => {
    const schemaWithItemsFallback: JsonSchema = {
      type: 'object',
      properties: {
        root: {
          type: 'object',
          properties: {},
          items: {
            type: 'object',
            properties: {
              inner: {
                type: 'object',
                properties: {
                  name: { type: 'string' },
                },
              },
            },
          },
        },
      },
    };
    // 'inner' is not in root.properties, but root.items.properties has it
    expect(getPropertySchema(schemaWithItemsFallback, 'root.inner.name')).toEqual({
      type: 'string',
    });
  });

  it('returns null when initial schema is not a record', () => {
    expect(getPropertySchema(null as unknown as JsonSchema, 'name')).toBeNull();
  });

  it('returns null when loop completes but current is not a schema property', () => {
    const badSchema = { type: 'object' } as JsonSchema;
    expect(getPropertySchema(badSchema, '')).toBeNull();
  });

  it('returns null when final segment resolves to non-schema value', () => {
    const schemaWithBadLeaf: JsonSchema = {
      type: 'object',
      properties: {
        root: {
          type: 'object',
          properties: {
            bad: 'not-a-schema' as unknown as JsonSchemaProperty,
          },
        },
      },
    };
    expect(getPropertySchema(schemaWithBadLeaf, 'root.bad')).toBeNull();
  });
});

describe('buildDefaultValue', () => {
  it('returns empty string for string type', () => {
    expect(buildDefaultValue({ type: 'string' })).toBe('');
  });

  it('returns empty string for date format', () => {
    expect(buildDefaultValue({ type: 'string', format: 'date' })).toBe('');
  });

  it('returns 0 for number type', () => {
    expect(buildDefaultValue({ type: 'number' })).toBe(0);
  });

  it('returns 0 for integer type', () => {
    expect(buildDefaultValue({ type: 'integer' })).toBe(0);
  });

  it('returns false for boolean type', () => {
    expect(buildDefaultValue({ type: 'boolean' })).toBe(false);
  });

  it('returns empty object for object type without properties', () => {
    expect(buildDefaultValue({ type: 'object' })).toEqual({});
  });

  it('returns object with default nested values', () => {
    const prop: JsonSchemaProperty = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        count: { type: 'integer' },
      },
    };
    expect(buildDefaultValue(prop)).toEqual({ name: '', count: 0 });
  });

  it('returns array with one default item', () => {
    const prop: JsonSchemaProperty = {
      type: 'array',
      items: { type: 'string' },
    };
    expect(buildDefaultValue(prop)).toEqual(['']);
  });

  it('returns empty array for array type without items', () => {
    expect(buildDefaultValue({ type: 'array' })).toEqual([]);
  });

  it('returns empty string for unknown type', () => {
    expect(buildDefaultValue({ type: 'unknown' as 'string' })).toBe('');
  });

  it('handles array type union', () => {
    expect(buildDefaultValue({ type: ['string', 'number'] })).toBe('');
  });
});
