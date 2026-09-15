// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import type { SchemaField } from '../types.js';
import { validateSchemaFields } from './field-validation.js';

describe('validateSchemaFields', () => {
  it('returns no errors for a valid maxItems/minItems pair', () => {
    const fields: SchemaField[] = [
      {
        id: 'tags',
        name: 'tags',
        type: 'array',
        arrayItemType: 'string',
        required: false,
        minItems: 1,
        maxItems: 5,
      },
    ];
    expect(validateSchemaFields(fields)).toEqual([]);
  });

  it('flags maxItems less than minItems, keyed by field id', () => {
    const fields: SchemaField[] = [
      {
        id: 'tags',
        name: 'tags',
        type: 'array',
        arrayItemType: 'string',
        required: false,
        minItems: 5,
        maxItems: 1,
      },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('tags');
    expect(errors[0].message).toContain('Max items');
  });

  it('allows equal minItems and maxItems', () => {
    const fields: SchemaField[] = [
      {
        id: 'tags',
        name: 'tags',
        type: 'array',
        arrayItemType: 'string',
        required: false,
        minItems: 3,
        maxItems: 3,
      },
    ];
    expect(validateSchemaFields(fields)).toEqual([]);
  });

  it('does not flag when only one bound is set', () => {
    const fields: SchemaField[] = [
      { id: '1', name: 'a', type: 'array', arrayItemType: 'string', required: false, minItems: 5 },
      { id: '2', name: 'b', type: 'array', arrayItemType: 'string', required: false, maxItems: 1 },
    ];
    expect(validateSchemaFields(fields)).toEqual([]);
  });

  it('recurses into nested fields of an object', () => {
    const fields: SchemaField[] = [
      {
        id: 'customer',
        name: 'customer',
        type: 'object',
        required: false,
        nestedFields: [
          {
            id: 'tags',
            name: 'tags',
            type: 'array',
            arrayItemType: 'string',
            required: false,
            minItems: 5,
            maxItems: 1,
          },
        ],
      },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('tags');
  });

  it('flags a negative minItems', () => {
    const fields: SchemaField[] = [
      {
        id: 'tags',
        name: 'tags',
        type: 'array',
        arrayItemType: 'string',
        required: false,
        minItems: -1,
      },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('tags');
    expect(errors[0].message).toContain('Min items');
  });

  it('flags a negative maxItems', () => {
    const fields: SchemaField[] = [
      {
        id: 'tags',
        name: 'tags',
        type: 'array',
        arrayItemType: 'string',
        required: false,
        maxItems: -1,
      },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('tags');
    expect(errors[0].message).toContain('Max items');
  });

  it('recurses into nested fields of an array-of-objects', () => {
    const fields: SchemaField[] = [
      {
        id: 'orders',
        name: 'orders',
        type: 'array',
        arrayItemType: 'object',
        required: false,
        nestedFields: [
          {
            id: 'items',
            name: 'items',
            type: 'array',
            arrayItemType: 'string',
            required: false,
            minItems: 5,
            maxItems: 1,
          },
        ],
      },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('items');
  });

  it('returns no errors for a default value matching the field type', () => {
    const fields: SchemaField[] = [
      { id: 'country', name: 'country', type: 'string', required: false, default: 'NL' },
    ];
    expect(validateSchemaFields(fields)).toEqual([]);
  });

  it('flags a default value of the wrong type, keyed by field id', () => {
    const fields: SchemaField[] = [
      { id: 'age', name: 'age', type: 'integer', required: false, default: 'thirty' },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('age');
    expect(errors[0].message).toContain('Default value');
    expect(errors[0].message).toContain('integer');
  });

  it("flags a default value outside the field's minimum/maximum range", () => {
    const fields: SchemaField[] = [
      {
        id: 'score',
        name: 'score',
        type: 'number',
        required: false,
        minimum: 0,
        maximum: 100,
        default: 150,
      },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('score');
  });

  it('flags a default value that does not match the field format', () => {
    const fields: SchemaField[] = [
      {
        id: 'email',
        name: 'email',
        type: 'string',
        required: false,
        format: 'email',
        default: 'not-an-email',
      },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('email');
  });

  it('allows a boolean default of false (falsy but present)', () => {
    const fields: SchemaField[] = [
      { id: 'active', name: 'active', type: 'boolean', required: false, default: false },
    ];
    expect(validateSchemaFields(fields)).toEqual([]);
  });

  it('recurses into nested object fields for default validation', () => {
    const fields: SchemaField[] = [
      {
        id: 'address',
        name: 'address',
        type: 'object',
        required: false,
        nestedFields: [
          { id: 'zip', name: 'zip', type: 'integer', required: false, default: 'invalid' },
        ],
      },
    ];
    const errors = validateSchemaFields(fields);
    expect(errors).toHaveLength(1);
    expect(errors[0].path).toBe('zip');
  });
});
