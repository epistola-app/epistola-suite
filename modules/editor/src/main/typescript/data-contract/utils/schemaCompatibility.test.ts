import { describe, expect, it } from 'vitest';
import { checkSchemaCompatibility } from './schemaCompatibility.js';

describe('checkSchemaCompatibility', () => {
  // ===========================================================================
  // Valid / compatible schemas
  // ===========================================================================

  describe('compatible schemas', () => {
    it('accepts minimal valid schema', () => {
      const result = checkSchemaCompatibility({ type: 'object', properties: {} });
      expect(result.compatible).toBe(true);
      expect(result.issues).toHaveLength(0);
    });

    it('accepts schema with $schema and additionalProperties', () => {
      const result = checkSchemaCompatibility({
        $schema: 'http://json-schema.org/draft-07/schema#',
        type: 'object',
        properties: {},
        additionalProperties: true,
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts schema with primitive property types', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          name: { type: 'string' },
          age: { type: 'integer' },
          score: { type: 'number' },
          active: { type: 'boolean' },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts schema with required array', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          name: { type: 'string' },
        },
        required: ['name'],
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts string with format:date', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          birthDate: { type: 'string', format: 'date' },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts string with format:email', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          email: { type: 'string', format: 'email' },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts array with items', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          tags: { type: 'array', items: { type: 'string' } },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts array with object items', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          items: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                name: { type: 'string' },
                price: { type: 'number' },
              },
              required: ['name'],
            },
          },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts nested objects', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          address: {
            type: 'object',
            properties: {
              street: { type: 'string' },
              city: { type: 'string' },
            },
          },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts description on properties', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          name: { type: 'string', description: 'The user name' },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts numeric constraints (minimum, maximum)', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          age: { type: 'integer', minimum: 0, maximum: 150 },
          score: { type: 'number', minimum: 0 },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts array constraint (minItems)', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          items: { type: 'array', items: { type: 'string' }, minItems: 1 },
        },
      });
      expect(result.compatible).toBe(true);
    });

    it('accepts deeply nested compatible schema', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          level1: {
            type: 'object',
            properties: {
              level2: {
                type: 'object',
                properties: {
                  value: { type: 'string' },
                },
              },
            },
          },
        },
      });
      expect(result.compatible).toBe(true);
    });
  });

  // ===========================================================================
  // Invalid / incompatible schemas
  // ===========================================================================

  describe('non-schema inputs', () => {
    it('rejects non-object input', () => {
      expect(checkSchemaCompatibility('string')).toMatchObject({ compatible: false });
      expect(checkSchemaCompatibility(42)).toMatchObject({ compatible: false });
      expect(checkSchemaCompatibility(null)).toMatchObject({ compatible: false });
      expect(checkSchemaCompatibility([1, 2])).toMatchObject({ compatible: false });
    });
  });

  describe('root-level issues', () => {
    it('flags non-object root type', () => {
      const result = checkSchemaCompatibility({ type: 'array', items: { type: 'string' } });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'non-object-root')).toBe(true);
    });

    it('flags unsupported root keys', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {},
        $defs: { foo: { type: 'string' } },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === '$defs')).toBe(true);
    });

    it('flags additionalProperties: false', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {},
        additionalProperties: false,
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'additionalProperties-false')).toBe(true);
    });
  });

  describe('composition keywords', () => {
    it('flags allOf', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {},
        allOf: [{ required: ['name'] }],
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'allOf')).toBe(true);
    });

    it('flags anyOf', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {},
        anyOf: [{ type: 'object' }],
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'anyOf')).toBe(true);
    });

    it('flags oneOf', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {},
        oneOf: [{ type: 'object' }],
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'oneOf')).toBe(true);
    });

    it('flags not', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          name: { type: 'string', not: { maxLength: 0 } },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'not')).toBe(true);
    });
  });

  describe('property-level issues', () => {
    it('flags enum', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          status: { type: 'string', enum: ['active', 'inactive'] },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'enum')).toBe(true);
    });

    it('flags const', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          version: { type: 'string', const: '1.0' },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'const')).toBe(true);
    });

    it('flags default', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          name: { type: 'string', default: 'unknown' },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'default')).toBe(true);
    });

    it('flags $ref', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          address: { $ref: '#/$defs/Address' },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === '$ref')).toBe(true);
    });

    it('flags type unions', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          value: { type: ['string', 'null'] },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'type-union')).toBe(true);
    });

    it('flags pattern', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          zipCode: { type: 'string', pattern: '^\\d{5}$' },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'pattern')).toBe(true);
    });

    it('flags minLength and maxLength', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          name: { type: 'string', minLength: 1, maxLength: 100 },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'minLength')).toBe(true);
      expect(result.issues.some((i) => i.feature === 'maxLength')).toBe(true);
    });

    it('flags exclusiveMinimum and exclusiveMaximum', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          value: { type: 'number', exclusiveMinimum: 0, exclusiveMaximum: 100 },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'exclusiveMinimum')).toBe(true);
      expect(result.issues.some((i) => i.feature === 'exclusiveMaximum')).toBe(true);
    });

    it('flags title', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          name: { type: 'string', title: 'User Name' },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'title')).toBe(true);
    });

    it('flags unsupported format values', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          hostname: { type: 'string', format: 'hostname' },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'format-hostname')).toBe(true);
    });

    it('flags format on non-string type', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          value: { type: 'integer', format: 'int32' },
        },
      });
      expect(result.compatible).toBe(false);
    });

    it('flags if/then/else', () => {
      const conditionalSchema = JSON.parse(`
        {
          "type": "object",
          "properties": {},
          "if": { "properties": { "type": { "const": "a" } } },
          "then": { "required": ["a"] },
          "else": { "required": ["b"] }
        }
      `);

      const result = checkSchemaCompatibility(conditionalSchema);
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'if')).toBe(true);
      expect(result.issues.some((i) => i.feature === 'then')).toBe(true);
      expect(result.issues.some((i) => i.feature === 'else')).toBe(true);
    });

    it('flags patternProperties', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          data: { type: 'object', patternProperties: { '^S_': { type: 'string' } } },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'patternProperties')).toBe(true);
    });

    it('flags tuple-form items', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          pair: { type: 'array', items: [{ type: 'string' }, { type: 'number' }] },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'tuple-items')).toBe(true);
    });

    it('flags maxItems and uniqueItems', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          tags: { type: 'array', items: { type: 'string' }, maxItems: 10, uniqueItems: true },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'maxItems')).toBe(true);
      expect(result.issues.some((i) => i.feature === 'uniqueItems')).toBe(true);
    });

    it('flags readOnly and writeOnly', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          id: { type: 'string', readOnly: true },
          password: { type: 'string', writeOnly: true },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'readOnly')).toBe(true);
      expect(result.issues.some((i) => i.feature === 'writeOnly')).toBe(true);
    });

    it('flags deprecated', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          oldField: { type: 'string', deprecated: true },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'deprecated')).toBe(true);
    });

    it('flags multipleOf', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          quantity: { type: 'integer', multipleOf: 5 },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'multipleOf')).toBe(true);
    });
  });

  describe('nested issues', () => {
    it('detects issues in nested object properties', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          address: {
            type: 'object',
            properties: {
              zipCode: { type: 'string', pattern: '^\\d{5}$' },
            },
          },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues[0].path).toContain('address');
      expect(result.issues[0].path).toContain('zipCode');
    });

    it('detects issues in array items', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          items: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                status: { type: 'string', enum: ['active', 'inactive'] },
              },
            },
          },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'enum')).toBe(true);
      expect(result.issues[0].path).toContain('items');
    });

    it('detects additionalProperties: false in nested objects', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          data: {
            type: 'object',
            properties: { name: { type: 'string' } },
            additionalProperties: false,
          },
        },
      });
      expect(result.compatible).toBe(false);
      expect(result.issues.some((i) => i.feature === 'additionalProperties-false')).toBe(true);
    });
  });

  describe('multiple issues', () => {
    it('collects all issues from a complex schema', () => {
      const result = checkSchemaCompatibility({
        type: 'object',
        properties: {
          name: { type: 'string', minLength: 1 },
          status: { type: 'string', enum: ['a', 'b'] },
          score: { type: 'number', exclusiveMinimum: 0 },
        },
        $defs: { Foo: { type: 'string' } },
      });
      expect(result.compatible).toBe(false);
      // Should find at least: minLength, enum, exclusiveMinimum, $defs
      expect(result.issues.length).toBeGreaterThanOrEqual(4);
    });
  });
});
