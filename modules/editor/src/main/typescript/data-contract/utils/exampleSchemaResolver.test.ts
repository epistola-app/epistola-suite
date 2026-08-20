// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import type { JsonSchema, JsonSchemaProperty } from '../types.js';
import { resolveExampleSchema } from './exampleSchemaResolver.js';

describe('resolveExampleSchema', () => {
  it('resolves local definitions while retaining sibling annotations', () => {
    const schema: JsonSchema = {
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
      resolveExampleSchema({ $ref: '#/$defs/address', description: 'Postal address' }, schema),
    ).toMatchObject({
      type: 'object',
      description: 'Postal address',
      properties: { city: { type: 'string' } },
      required: ['city'],
    });
  });

  it('combines object properties and required fields from allOf', () => {
    const composed: JsonSchemaProperty = {
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

    expect(resolveExampleSchema(composed, { type: 'object' })).toMatchObject({
      properties: { id: { type: 'string' }, name: { type: 'string' } },
      required: ['id', 'name'],
    });
  });

  it('selects the oneOf branch matching the current object', () => {
    const union: JsonSchemaProperty = {
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
      resolveExampleSchema(union, { type: 'object' }, { companyName: 'Epistola' }),
    ).toMatchObject({
      properties: { companyName: { type: 'string' } },
    });
  });

  it('keeps a partially filled object on the union branch with matching fields', () => {
    const union: JsonSchemaProperty = {
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
      resolveExampleSchema(union, { type: 'object' }, { companyName: 'Epistola' }),
    ).toMatchObject({
      properties: {
        companyName: { type: 'string' },
        registrationNumber: { type: 'string' },
      },
    });
  });
});
