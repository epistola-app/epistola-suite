// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { checkSchemaCompatibility } from './compatibility.js';
import { normalizeSchemaForVisualEditor } from './normalization.js';

describe('normalizeSchemaForVisualEditor', () => {
  it('inlines nested local references and removes definitions', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      $defs: {
        address: {
          type: 'object',
          properties: {
            city: { type: 'string' },
          },
          required: ['city'],
        },
      },
      properties: {
        addresses: {
          type: 'array',
          items: { $ref: '#/$defs/address' },
        },
      },
    });

    expect(result.issues).toEqual([]);
    expect(result.schema).toMatchObject({
      type: 'object',
      properties: {
        addresses: {
          type: 'array',
          items: {
            type: 'object',
            properties: { city: { type: 'string' } },
            required: ['city'],
          },
        },
      },
    });
    expect(result.schema).not.toHaveProperty('$defs');
    expect(checkSchemaCompatibility(result.schema).compatible).toBe(true);
  });

  it('flattens compatible allOf object composition', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      properties: {
        contact: {
          type: 'object',
          properties: { name: { type: 'string' } },
          required: ['name'],
          allOf: [
            {
              type: 'object',
              properties: { email: { type: 'string', format: 'email' } },
              required: ['email'],
            },
          ],
        },
      },
    });

    expect(result.issues).toEqual([]);
    expect(result.schema?.properties?.contact).toEqual({
      type: 'object',
      properties: {
        name: { type: 'string' },
        email: { type: 'string', format: 'email' },
      },
      required: ['name', 'email'],
    });
  });

  it('retains reference sibling annotations while inlining', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      $defs: {
        address: {
          type: 'object',
          description: 'Generic address',
          properties: { city: { type: 'string' } },
        },
      },
      properties: {
        postalAddress: {
          $ref: '#/$defs/address',
          description: 'Postal address',
        },
      },
    });

    expect(result.issues).toEqual([]);
    expect(result.schema?.properties?.postalAddress).toMatchObject({
      type: 'object',
      description: 'Postal address',
      properties: { city: { type: 'string' } },
    });
  });

  it('collapses single-member compositions and type unions', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      properties: {
        name: {
          anyOf: [{ type: ['string'] }],
        },
      },
    });

    expect(result.issues).toEqual([]);
    expect(result.schema?.properties?.name).toEqual({ type: 'string' });
  });

  it('rejects multi-branch unions instead of weakening their semantics', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      properties: {
        subject: {
          oneOf: [
            { type: 'object', properties: { personName: { type: 'string' } } },
            { type: 'object', properties: { organizationName: { type: 'string' } } },
          ],
        },
      },
    });

    expect(result.schema).toBeNull();
    expect(result.issues).toEqual(
      expect.arrayContaining([expect.objectContaining({ path: '$.properties.subject.oneOf' })]),
    );
  });

  it('rejects nullable unions instead of silently dropping null', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      properties: {
        address: {
          anyOf: [{ type: 'null' }, { type: 'object', properties: { city: { type: 'string' } } }],
        },
      },
    });

    expect(result.schema).toBeNull();
    expect(result.issues.some((issue) => issue.feature === 'anyOf')).toBe(true);
  });

  it('rejects arrays directly containing arrays because the visual model cannot round-trip them', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      properties: {
        matrix: {
          type: 'array',
          items: {
            type: 'array',
            items: { type: 'string' },
          },
        },
      },
    });

    expect(result.schema).toBeNull();
    expect(result.issues).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: '$.properties.matrix.items.type',
          feature: 'nested-array',
        }),
      ]),
    );
  });

  it('rejects unconstrained properties that have no visual field type', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      properties: {
        anything: {},
      },
    });

    expect(result.schema).toBeNull();
    expect(result.issues).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: '$.properties.anything.type',
          feature: 'missing-type',
        }),
      ]),
    );
  });

  it('rejects recursive references with a useful path', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      $defs: {
        node: {
          type: 'object',
          properties: {
            child: { $ref: '#/$defs/node' },
          },
        },
      },
      properties: {
        root: { $ref: '#/$defs/node' },
      },
    });

    expect(result.schema).toBeNull();
    expect(result.issues).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: '$.$defs.node.properties.child.$ref',
          feature: 'recursive-ref',
        }),
      ]),
    );
  });

  it('rejects conflicting allOf assertions', () => {
    const result = normalizeSchemaForVisualEditor({
      type: 'object',
      properties: {
        value: {
          allOf: [{ type: 'string' }, { type: 'integer' }],
        },
      },
    });

    expect(result.schema).toBeNull();
    expect(result.issues).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: '$.properties.value.type',
          feature: 'composition-conflict',
        }),
      ]),
    );
  });
});
