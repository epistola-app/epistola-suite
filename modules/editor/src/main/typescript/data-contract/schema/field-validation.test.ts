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
});
