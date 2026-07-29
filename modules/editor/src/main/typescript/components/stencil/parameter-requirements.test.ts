// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import type { JsonSchema } from '../../data-contract/types.js';
import {
  bindingsDeclaredBySchema,
  missingRequiredParameters,
  parameterHasDefault,
} from './parameter-requirements.js';

describe('stencil parameter requirements', () => {
  const schema = {
    type: 'object',
    properties: {
      bound: { type: 'string' },
      defaulted: { type: 'string', default: 'fallback' },
      nullableDefault: { type: 'string', default: null },
      missing: { type: 'string' },
      optional: { type: 'string' },
    },
    required: ['bound', 'defaulted', 'nullableDefault', 'missing'],
  } as JsonSchema;

  it('treats an explicit default as satisfying a required parameter', () => {
    expect(parameterHasDefault(schema, 'defaulted')).toBe(true);
    expect(parameterHasDefault(schema, 'nullableDefault')).toBe(true);
  });

  it('returns only required parameters without a binding or default', () => {
    expect(missingRequiredParameters(schema, { bound: 'customer.name' })).toEqual(['missing']);
  });

  it('treats a blank binding as missing', () => {
    expect(missingRequiredParameters(schema, { bound: '   ', missing: "'value'" })).toEqual([
      'bound',
    ]);
  });

  it('removes bindings for parameters no longer declared by the schema', () => {
    expect(
      bindingsDeclaredBySchema(schema, {
        bound: 'customer.name',
        removed: 'customer.legacyName',
      }),
    ).toEqual({ bound: 'customer.name' });
    expect(bindingsDeclaredBySchema(undefined, { bound: 'customer.name' })).toEqual({});
  });
});
