// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { RICH_TEXT_INLINE_SCHEMA_REF, type JsonSchema } from '../types.js';
import { validateDataAgainstSchema } from './schemaValidation.js';
import { completeExampleFromSchema } from './exampleGeneration.js';

describe('completeExampleFromSchema', () => {
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

    const generated = completeExampleFromSchema(schema, {});

    expect(generated).toEqual({
      name: 'Example valuexx',
      status: 'draft',
      country: 'NL',
      createdOn: '2024-01-01',
      updatedAt: '2024-01-01T12:00:00Z',
      email: 'example@example.com',
      website: 'https://example.com',
      amount: 2.5,
      count: 4,
      active: false,
    });
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

    const generated = completeExampleFromSchema(schema, {
      name: 'Authored name',
      address: { city: 'Amsterdam' },
      contacts: [{ label: 'Personal' }],
      custom: 'keep me',
    });

    expect(generated).toEqual({
      name: 'Authored name',
      address: { street: 'Example value', city: 'Amsterdam' },
      contacts: [
        { label: 'Personal', preferred: false },
        { label: 'Example value', preferred: false },
      ],
      custom: 'keep me',
    });
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

    expect(completeExampleFromSchema(schema, {})).toEqual({
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

    const generated = completeExampleFromSchema(schema, {});

    expect(generated.address).toEqual({ city: 'Example value' });
    expect(generated.introduction).toMatchObject({ type: 'doc' });
  });

  it('does not add an item when maxItems is zero', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        values: { type: 'array', items: { type: 'string' }, maxItems: 0 },
      },
    };

    expect(completeExampleFromSchema(schema, {})).toEqual({ values: [] });
  });
});
