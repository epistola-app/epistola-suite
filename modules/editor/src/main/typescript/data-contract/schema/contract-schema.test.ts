// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { validateDataContractSchema } from './contract-schema.js';

describe('validateDataContractSchema', () => {
  it('accepts an object schema', () => {
    expect(validateDataContractSchema({ type: 'object', properties: {} })).toEqual({ valid: true });
  });

  it('accepts an object root resolved through a local reference', () => {
    expect(
      validateDataContractSchema({
        $ref: '#/$defs/root',
        $defs: { root: { type: 'object', properties: {} } },
      }),
    ).toEqual({ valid: true });
  });

  it('accepts an object root constrained by composition', () => {
    expect(
      validateDataContractSchema({
        allOf: [{ $ref: '#/$defs/root' }, { additionalProperties: false }],
        $defs: { root: { type: 'object', properties: {} } },
      }),
    ).toEqual({ valid: true });
  });

  it('rejects arbitrary JSON objects', () => {
    expect(validateDataContractSchema({ schemaVersion: 5, resource: {} })).toEqual({
      valid: false,
      error: 'A data contract JSON Schema must require an object at its root',
    });
  });

  it('rejects schemas that allow a non-object root branch', () => {
    expect(
      validateDataContractSchema({ oneOf: [{ type: 'object' }, { type: 'string' }] }).valid,
    ).toBe(false);
  });
});
