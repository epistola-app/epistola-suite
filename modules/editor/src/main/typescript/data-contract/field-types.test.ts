import { describe, expect, it } from 'vitest';
import {
  ARRAY_ITEM_FIELD_TYPES,
  CONTRACT_FIELD_TYPES,
  FIELD_TYPE_DEFS,
  FIELD_TYPE_LABELS,
  fieldTypeLabel,
  isScalarFieldType,
  scalarFromJsonSchema,
  scalarToJsonSchema,
  STENCIL_PARAM_TYPES,
} from './field-types.js';

describe('field-types registry', () => {
  it('labels every type and exposes them via FIELD_TYPE_LABELS', () => {
    expect(FIELD_TYPE_LABELS.string).toBe('Text');
    expect(FIELD_TYPE_LABELS.boolean).toBe('Yes/No');
    expect(FIELD_TYPE_LABELS.date).toBe('Date');
    expect(FIELD_TYPE_LABELS.datetime).toBe('Date & time');
    expect(FIELD_TYPE_LABELS.array).toBe('List');
    // fieldTypeLabel() agrees with the map.
    for (const def of FIELD_TYPE_DEFS) {
      expect(fieldTypeLabel(def.id)).toBe(def.label);
    }
  });

  it('round-trips every scalar through JSON Schema', () => {
    for (const def of FIELD_TYPE_DEFS) {
      if (def.kind !== 'scalar') continue;
      const json = scalarToJsonSchema(def.id as never);
      expect(scalarFromJsonSchema(json.type, json.format)).toBe(def.id);
    }
  });

  it('maps the date scalars to the expected JSON Schema', () => {
    expect(scalarToJsonSchema('date')).toEqual({ type: 'string', format: 'date' });
    expect(scalarToJsonSchema('datetime')).toEqual({ type: 'string', format: 'date-time' });
    expect(scalarToJsonSchema('string')).toEqual({ type: 'string' });
  });

  it('does not resolve string formats that are constraints, not types', () => {
    // email/uri are string-format constraints, not scalar types.
    expect(scalarFromJsonSchema('string', 'email')).toBeNull();
    expect(scalarFromJsonSchema('string', 'uri')).toBeNull();
    // plain string still resolves.
    expect(scalarFromJsonSchema('string', undefined)).toBe('string');
  });

  it('classifies scalars correctly', () => {
    expect(isScalarFieldType('string')).toBe(true);
    expect(isScalarFieldType('datetime')).toBe(true);
    expect(isScalarFieldType('richTextInline')).toBe(false);
    expect(isScalarFieldType('array')).toBe(false);
    expect(isScalarFieldType('object')).toBe(false);
  });

  it('stencil parameters use exactly the scalar subset', () => {
    const scalars = FIELD_TYPE_DEFS.filter((d) => d.kind === 'scalar').map((d) => d.id);
    expect(STENCIL_PARAM_TYPES.map((d) => d.id)).toEqual(scalars);
    // No ref/container type leaks into the stencil subset.
    expect(STENCIL_PARAM_TYPES.every((d) => d.kind === 'scalar')).toBe(true);
  });

  it('contract offers the full set; array items exclude nested arrays', () => {
    expect(CONTRACT_FIELD_TYPES).toEqual([
      'string',
      'number',
      'integer',
      'boolean',
      'date',
      'datetime',
      'richTextInline',
      'richTextBlock',
      'array',
      'object',
    ]);
    // Array items can be anything except another array.
    expect(ARRAY_ITEM_FIELD_TYPES).not.toContain('array');
    expect(ARRAY_ITEM_FIELD_TYPES).toContain('object');
    expect(ARRAY_ITEM_FIELD_TYPES).toContain('datetime');
  });
});
