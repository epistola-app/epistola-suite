// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import {
  MAX_RESOLVED_SCHEMA_VARIANTS,
  resolveSchemaForValue,
  resolveSchemaVariants,
  type JsonSchemaNode,
} from './schema-resolution.js';

describe('resolveSchemaForValue', () => {
  it('resolves local definitions while retaining sibling annotations', () => {
    const schema = {
      type: 'object',
      $defs: {
        address: {
          type: 'object',
          properties: { city: { type: 'string' } },
          required: ['city'],
        },
      },
    };

    expect(
      resolveSchemaForValue({ $ref: '#/$defs/address', description: 'Postal address' }, schema),
    ).toMatchObject({
      type: 'object',
      description: 'Postal address',
      properties: { city: { type: 'string' } },
      required: ['city'],
    });
  });

  it('combines object properties and required fields from allOf', () => {
    const composed = {
      type: 'object',
      properties: { id: { type: 'string' } },
      required: ['id'],
      allOf: [
        {
          properties: { name: { type: 'string' } },
          required: ['name'],
        },
      ],
    };

    expect(resolveSchemaForValue(composed, { type: 'object' })).toMatchObject({
      properties: { id: { type: 'string' }, name: { type: 'string' } },
      required: ['id', 'name'],
    });
  });

  it('selects the oneOf branch matching the current object', () => {
    const union = {
      oneOf: [
        {
          type: 'object',
          properties: { personName: { type: 'string' } },
          required: ['personName'],
        },
        {
          type: 'object',
          properties: { companyName: { type: 'string' } },
          required: ['companyName'],
        },
      ],
    };

    expect(
      resolveSchemaForValue(union, { type: 'object' }, { companyName: 'Epistola' }),
    ).toMatchObject({
      properties: { companyName: { type: 'string' } },
    });
  });

  it('keeps a partially filled object on the union branch with matching fields', () => {
    const union = {
      oneOf: [
        {
          type: 'object',
          properties: { personName: { type: 'string' }, email: { type: 'string' } },
          required: ['personName', 'email'],
        },
        {
          type: 'object',
          properties: {
            companyName: { type: 'string' },
            registrationNumber: { type: 'string' },
          },
          required: ['companyName', 'registrationNumber'],
        },
      ],
    };

    expect(
      resolveSchemaForValue(union, { type: 'object' }, { companyName: 'Epistola' }),
    ).toMatchObject({
      properties: {
        companyName: { type: 'string' },
        registrationNumber: { type: 'string' },
      },
    });
  });

  it('uses nested property values to distinguish otherwise identical branches', () => {
    const union = {
      oneOf: [
        {
          type: 'object',
          properties: { identifier: { type: 'number' } },
          required: ['identifier'],
        },
        {
          type: 'object',
          properties: { identifier: { type: 'string' } },
          required: ['identifier'],
        },
      ],
    };

    expect(resolveSchemaForValue(union, union, { identifier: 'NL-123' })).toMatchObject({
      properties: { identifier: { type: 'string' } },
    });
  });

  it('selects an editable branch when a nullable union starts with null', () => {
    const nullable = {
      anyOf: [{ type: 'null' }, { properties: { city: { type: 'string' } } }],
    };

    expect(resolveSchemaForValue(nullable, nullable)).toMatchObject({
      properties: { city: { type: 'string' } },
    });
    expect(resolveSchemaForValue(nullable, nullable, null)).toMatchObject({
      properties: { city: { type: 'string' } },
    });
  });

  it('resolves escaped JSON Pointer segments', () => {
    const schema = {
      $defs: {
        'address/home': {
          type: 'object',
          properties: { city: { type: 'string' } },
        },
      },
    };

    expect(resolveSchemaForValue({ $ref: '#/$defs/address~1home' }, schema)).toMatchObject({
      properties: { city: { type: 'string' } },
    });
  });

  it('applies oneOf and anyOf when both constrain the same schema', () => {
    const schema = {
      oneOf: [
        { properties: { personName: { type: 'string' } } },
        { properties: { organizationName: { type: 'string' } } },
      ],
      anyOf: [
        { properties: { email: { type: 'string' } } },
        { properties: { phoneNumber: { type: 'string' } } },
      ],
    };

    const variants = resolveSchemaVariants(schema, schema);
    expect(variants).toHaveLength(4);
    expect(variants.map((variant) => Object.keys(variant.schema.properties ?? {}))).toEqual([
      ['personName', 'email'],
      ['personName', 'phoneNumber'],
      ['organizationName', 'email'],
      ['organizationName', 'phoneNumber'],
    ]);
  });

  it('deduplicates variants and caps combinatorial expansion', () => {
    const duplicate = {
      oneOf: [{ type: 'string' }, { type: 'string' }],
    };
    expect(resolveSchemaVariants(duplicate, duplicate)).toHaveLength(1);

    const composed: JsonSchemaNode = {
      allOf: Array.from({ length: 10 }, (_, index) => ({
        oneOf: [
          { properties: { [`left${index}`]: { type: 'string' } } },
          { properties: { [`right${index}`]: { type: 'string' } } },
        ],
      })),
    };
    expect(resolveSchemaVariants(composed, composed)).toHaveLength(MAX_RESOLVED_SCHEMA_VARIANTS);
  });
});
