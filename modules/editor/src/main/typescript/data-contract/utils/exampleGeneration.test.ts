// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import {
  RICH_TEXT_INLINE_SCHEMA_REF,
  type JsonObject,
  type JsonSchema,
  type JsonValue,
} from '../types.js';
import { validateDataAgainstSchema } from './schemaValidation.js';
import { completeExampleFromSchema } from './exampleGeneration.js';
import { createSemanticExampleValues } from './semanticExampleValues.js';

function complete(schema: JsonSchema, existing: JsonObject) {
  return completeExampleFromSchema(schema, existing, createSemanticExampleValues('test-example'));
}

describe('completeExampleFromSchema', () => {
  it('keeps generic schema completion independent from semantic providers', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' }, active: { type: 'boolean' } },
    };

    expect(completeExampleFromSchema(schema, {})).toEqual({
      name: 'Example value',
      active: false,
    });
  });

  it('generates deterministic values that respect schema hints and constraints', () => {
    const schema: JsonSchema = {
      type: 'object',
      required: ['name', 'status', 'createdOn', 'amount', 'active'],
      properties: {
        name: { type: 'string', minLength: 15 },
        status: { type: 'string', enum: ['draft', 'final'] },
        country: { type: 'string', default: 'NL' },
        createdOn: { type: 'string', format: 'date' },
        updatedAt: { type: 'string', format: 'date-time' },
        email: { type: 'string', format: 'email' },
        website: { type: 'string', format: 'uri' },
        amount: { type: 'number', minimum: 2.5 },
        count: { type: 'integer', exclusiveMinimum: 3 },
        active: { type: 'boolean' },
      },
    };

    const generated = complete(schema, {});

    expect(generated.name).not.toBe('Example valuexx');
    expect((generated.name as string).length).toBeGreaterThanOrEqual(15);
    expect(generated).toMatchObject({
      status: 'draft',
      country: 'NL',
      createdOn: '2024-01-01',
      updatedAt: '2024-01-01T12:00:00Z',
      amount: 125.5,
      count: 4,
      active: true,
    });
    expect(generated.email).toMatch(/@example\.(com|net|org)$/);
    expect(() => new URL(generated.website as string)).not.toThrow();
    expect(complete(schema, {})).toEqual(generated);
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('preserves authored values while completing nested objects and arrays', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        address: {
          type: 'object',
          properties: {
            street: { type: 'string' },
            city: { type: 'string' },
          },
          required: ['street', 'city'],
        },
        contacts: {
          type: 'array',
          minItems: 2,
          items: {
            type: 'object',
            properties: {
              label: { type: 'string' },
              preferred: { type: 'boolean' },
            },
            required: ['label', 'preferred'],
          },
        },
      },
    };

    const generated = complete(schema, {
      name: 'Authored name',
      address: { city: 'Amsterdam' },
      contacts: [{ label: 'Personal' }],
      custom: 'keep me',
    });

    expect(generated).toMatchObject({
      name: 'Authored name',
      address: { street: expect.any(String), city: 'Amsterdam' },
      custom: 'keep me',
    });
    const contacts = generated.contacts as JsonValue[];
    expect(contacts.length).toBeGreaterThanOrEqual(2);
    expect(contacts.length).toBeLessThanOrEqual(3);
    expect(contacts[0]).toEqual({ label: 'Personal', preferred: false });
    for (const contact of contacts.slice(1)) {
      expect(contact).toEqual({ label: 'Voorbeeld voor label', preferred: false });
    }
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('uses defaults as a base and completes their missing nested values', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        settings: {
          type: 'object',
          default: { locale: 'nl-NL' },
          properties: {
            locale: { type: 'string' },
            notifications: { type: 'boolean' },
          },
        },
      },
    };

    expect(complete(schema, {})).toEqual({
      settings: { locale: 'nl-NL', notifications: false },
    });
  });

  it('supports local schema references and registered rich-text references', () => {
    const schema: JsonSchema = {
      type: 'object',
      $defs: {
        address: {
          type: 'object',
          properties: { city: { type: 'string' } },
          required: ['city'],
        },
      },
      properties: {
        address: { $ref: '#/$defs/address' },
        introduction: { $ref: RICH_TEXT_INLINE_SCHEMA_REF },
      },
    };

    const generated = complete(schema, {});

    expect(generated.address).toEqual({ city: expect.any(String) });
    expect(generated.introduction).toMatchObject({ type: 'doc' });
  });

  it('honors array minimum and maximum constraints', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        values: { type: 'array', items: { type: 'string' }, maxItems: 0 },
        single: { type: 'array', items: { type: 'string' }, maxItems: 1 },
        requiredItems: { type: 'array', items: { type: 'string' }, minItems: 5 },
      },
    };

    const generated = complete(schema, {});

    expect(generated.values).toEqual([]);
    expect(generated.single).toHaveLength(1);
    expect(generated.requiredItems).toHaveLength(5);
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('keeps explicit schema formats and numeric constraints authoritative', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        phoneNumber: { type: 'string', format: 'email' },
        website: { type: 'string', format: 'date' },
        amount: { type: 'number', maximum: 100 },
        age: { type: 'integer', minimum: 50 },
      },
    };

    const generated = complete(schema, {});

    expect(generated.phoneNumber).toBe('example@example.com');
    expect(generated.website).toBe('2024-01-01');
    expect(generated.amount).toBe(100);
    expect(generated.age).toBe(50);
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });

  it('generates through recursively nested arrays and objects', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        arr: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              arr: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: { name: { type: 'string' } },
                  required: ['name'],
                },
              },
            },
            required: ['arr'],
          },
        },
      },
      required: ['arr'],
    };

    const generated = complete(schema, {});

    const outerItems = generated.arr as JsonValue[];
    expect(outerItems.length).toBeGreaterThanOrEqual(2);
    expect(outerItems.length).toBeLessThanOrEqual(3);
    for (const outerItem of outerItems) {
      const innerItems = (outerItem as JsonObject).arr as JsonValue[];
      expect(innerItems.length).toBeGreaterThanOrEqual(2);
      expect(innerItems.length).toBeLessThanOrEqual(3);
      for (const innerItem of innerItems) {
        expect(innerItem).toEqual({ name: expect.any(String) });
      }
    }
    expect(validateDataAgainstSchema(generated, schema).valid).toBe(true);
  });
});
