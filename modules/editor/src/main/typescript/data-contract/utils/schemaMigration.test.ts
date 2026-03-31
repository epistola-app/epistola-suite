import { describe, expect, it } from 'vitest';
import { applyAllMigrations, applyMigration, detectMigrations } from './schemaMigration.js';
import type { DataExample, JsonObject, JsonSchema } from '../types.js';

describe('detectMigrations', () => {
  it('returns compatible when schema is null', () => {
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { name: 'John' } }];

    const result = detectMigrations(null, examples);

    expect(result.compatible).toBe(true);
    expect(result.migrations).toHaveLength(0);
  });

  it('returns compatible when examples are empty', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
    };

    const result = detectMigrations(schema, []);

    expect(result.compatible).toBe(true);
    expect(result.migrations).toHaveLength(0);
  });

  it('returns compatible when data matches schema', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        age: { type: 'integer' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { name: 'John', age: 30 } }];

    const result = detectMigrations(schema, examples);

    expect(result.compatible).toBe(true);
    expect(result.migrations).toHaveLength(0);
  });

  it('detects type mismatch', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        age: { type: 'integer' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { age: 'thirty' } }];

    const result = detectMigrations(schema, examples);

    expect(result.compatible).toBe(false);
    expect(result.migrations).toHaveLength(1);
    expect(result.migrations[0].issue).toBe('TYPE_MISMATCH');
    expect(result.migrations[0].path).toBe('$.age');
  });

  it('detects missing required field', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        name: { type: 'string' },
      },
      required: ['name'],
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: {} }];

    const result = detectMigrations(schema, examples);

    expect(result.compatible).toBe(false);
    expect(result.migrations).toHaveLength(1);
    expect(result.migrations[0].issue).toBe('MISSING_REQUIRED');
  });

  it('detects migrations in nested objects', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        user: {
          type: 'object',
          properties: {
            age: { type: 'integer' },
          },
        },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { user: { age: '25' } } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations).toHaveLength(1);
    expect(result.migrations[0].path).toBe('$.user.age');
  });

  it('detects migrations in array items', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        items: {
          type: 'array',
          items: { type: 'integer' },
        },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { items: [1, 'two', 3] } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations).toHaveLength(1);
    expect(result.migrations[0].path).toBe('$.items[1]');
  });

  it('suggests auto-migratable string to number conversion', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        count: { type: 'integer' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { count: '42' } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations).toHaveLength(1);
    expect(result.migrations[0].autoMigratable).toBe(true);
    expect(result.migrations[0].suggestedValue).toBe(42);
  });

  it("suggests auto-migratable boolean conversion from 'true' string", () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        active: { type: 'boolean' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { active: 'true' } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(true);
    expect(result.migrations[0].suggestedValue).toBe(true);
  });

  it("suggests auto-migratable boolean conversion from 'false' string", () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        active: { type: 'boolean' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { active: 'false' } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(true);
    expect(result.migrations[0].suggestedValue).toBe(false);
  });

  it("suggests auto-migratable boolean conversion from '0' string", () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        active: { type: 'boolean' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { active: '0' } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(true);
    expect(result.migrations[0].suggestedValue).toBe(false);
  });

  it("suggests auto-migratable boolean conversion from 'no' string", () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        active: { type: 'boolean' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { active: 'no' } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(true);
    expect(result.migrations[0].suggestedValue).toBe(false);
  });

  it('suggests auto-migratable boolean conversion from number', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        active: { type: 'boolean' },
        inactive: { type: 'boolean' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { active: 1, inactive: 0 } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations).toHaveLength(2);
    const activeMigration = result.migrations.find((m) => m.path === '$.active');
    const inactiveMigration = result.migrations.find((m) => m.path === '$.inactive');
    expect(activeMigration?.suggestedValue).toBe(true);
    expect(inactiveMigration?.suggestedValue).toBe(false);
  });

  it('returns not auto-migratable for invalid boolean string', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        active: { type: 'boolean' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { active: 'maybe' } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(false);
    expect(result.migrations[0].suggestedValue).toBeNull();
  });

  it('no migration needed when boolean value is already boolean', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        active: { type: 'boolean' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { active: true } }];

    const result = detectMigrations(schema, examples);

    expect(result.compatible).toBe(true);
    expect(result.migrations).toHaveLength(0);
  });

  it('returns not auto-migratable for object to string conversion', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        value: { type: 'string' },
      },
    };
    const examples: DataExample[] = [
      { id: '1', name: 'Test', data: { value: { nested: 'object' } } },
    ];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(false);
  });

  it('returns not auto-migratable for array to primitive conversion', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        value: { type: 'integer' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { value: [1, 2, 3] } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(false);
  });

  it('suggests auto-migratable string conversion from number', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        name: { type: 'string' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { name: 123 } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(true);
    expect(result.migrations[0].suggestedValue).toBe('123');
  });

  it('suggests auto-migratable string conversion from boolean', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        value: { type: 'string' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { value: true } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(true);
    expect(result.migrations[0].suggestedValue).toBe('true');
  });

  it('suggests auto-migratable float number conversion', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        price: { type: 'number' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { price: '19.99' } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(true);
    expect(result.migrations[0].suggestedValue).toBe(19.99);
  });

  it('returns not auto-migratable for invalid number string', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        count: { type: 'integer' },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { count: 'not-a-number' } }];

    const result = detectMigrations(schema, examples);

    expect(result.migrations[0].autoMigratable).toBe(false);
  });

  it('detects array items that are valid and skips them', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        numbers: {
          type: 'array',
          items: { type: 'integer' },
        },
      },
    };
    const examples: DataExample[] = [{ id: '1', name: 'Test', data: { numbers: [1, 2, 3] } }];

    const result = detectMigrations(schema, examples);

    expect(result.compatible).toBe(true);
    expect(result.migrations).toHaveLength(0);
  });

  it('handles multiple values in array with some needing migration', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        values: {
          type: 'array',
          items: { type: 'integer' },
        },
      },
    };
    const examples: DataExample[] = [
      { id: '1', name: 'Test', data: { values: [1, '2', 3, 'invalid'] } },
    ];

    const result = detectMigrations(schema, examples);

    // Should find 2 migrations: "2" (auto-migratable) and "invalid" (not auto-migratable)
    expect(result.migrations).toHaveLength(2);
  });

  // =========================================================================
  // Real-world schema change scenarios
  // =========================================================================

  describe('field rename scenario', () => {
    it('detects missing required after rename (old name in data)', () => {
      const schema: JsonSchema = {
        type: 'object',
        properties: {
          fullName: { type: 'string' },
        },
        required: ['fullName'],
      };
      // Example still has old field name "name"
      const examples: DataExample[] = [{ id: '1', name: 'Test', data: { name: 'John' } }];

      const result = detectMigrations(schema, examples);

      expect(result.compatible).toBe(false);
      expect(
        result.migrations.some((m) => m.issue === 'MISSING_REQUIRED' && m.path === '$.fullName'),
      ).toBe(true);
    });

    it('compatible when optional field is renamed (no data impact)', () => {
      const schema: JsonSchema = {
        type: 'object',
        properties: {
          fullName: { type: 'string' },
        },
      };
      // Example has old name, but new field is optional
      const examples: DataExample[] = [{ id: '1', name: 'Test', data: { name: 'John' } }];

      const result = detectMigrations(schema, examples);

      expect(result.compatible).toBe(true);
    });
  });

  describe('making field required scenario', () => {
    it('detects when existing optional field becomes required but data is missing', () => {
      const schema: JsonSchema = {
        type: 'object',
        properties: {
          name: { type: 'string' },
          email: { type: 'string' },
        },
        required: ['name', 'email'],
      };
      // Example has name but not email
      const examples: DataExample[] = [{ id: '1', name: 'Test', data: { name: 'John' } }];

      const result = detectMigrations(schema, examples);

      expect(result.compatible).toBe(false);
      expect(result.migrations).toHaveLength(1);
      expect(result.migrations[0].issue).toBe('MISSING_REQUIRED');
      expect(result.migrations[0].path).toBe('$.email');
    });

    it('compatible when field becomes required and data already has it', () => {
      const schema: JsonSchema = {
        type: 'object',
        properties: {
          name: { type: 'string' },
          email: { type: 'string' },
        },
        required: ['name', 'email'],
      };
      const examples: DataExample[] = [
        { id: '1', name: 'Test', data: { name: 'John', email: 'john@test.com' } },
      ];

      const result = detectMigrations(schema, examples);

      expect(result.compatible).toBe(true);
    });

    it('detects required in some examples but not others', () => {
      const schema: JsonSchema = {
        type: 'object',
        properties: {
          email: { type: 'string' },
        },
        required: ['email'],
      };
      const examples: DataExample[] = [
        { id: '1', name: 'With email', data: { email: 'a@b.com' } },
        { id: '2', name: 'Without email', data: {} },
        { id: '3', name: 'Also without', data: { name: 'test' } },
      ];

      const result = detectMigrations(schema, examples);

      expect(result.compatible).toBe(false);
      // Only examples 2 and 3 should have issues
      expect(result.migrations).toHaveLength(2);
      expect(result.migrations[0].exampleId).toBe('2');
      expect(result.migrations[1].exampleId).toBe('3');
    });
  });

  describe('type change scenario', () => {
    it('detects when field type changes from string to integer', () => {
      const schema: JsonSchema = {
        type: 'object',
        properties: {
          age: { type: 'integer' },
        },
      };
      const examples: DataExample[] = [
        { id: '1', name: 'Numeric string', data: { age: '25' } },
        { id: '2', name: 'Non-numeric', data: { age: 'young' } },
      ];

      const result = detectMigrations(schema, examples);

      expect(result.compatible).toBe(false);
      // "25" is auto-migratable, "young" is not
      const numericMigration = result.migrations.find((m) => m.exampleId === '1');
      const textMigration = result.migrations.find((m) => m.exampleId === '2');
      expect(numericMigration?.autoMigratable).toBe(true);
      expect(numericMigration?.suggestedValue).toBe(25);
      expect(textMigration?.autoMigratable).toBe(false);
    });

    it('integer value is compatible with number type', () => {
      const schema: JsonSchema = {
        type: 'object',
        properties: {
          value: { type: 'number' },
        },
      };
      const examples: DataExample[] = [{ id: '1', name: 'Test', data: { value: 42 } }];

      const result = detectMigrations(schema, examples);

      expect(result.compatible).toBe(true);
    });
  });

  describe('nested required field scenarios', () => {
    it('detects missing required in nested object', () => {
      const schema: JsonSchema = {
        type: 'object',
        properties: {
          address: {
            type: 'object',
            properties: {
              street: { type: 'string' },
              city: { type: 'string' },
            },
            required: ['street', 'city'],
          },
        },
      };
      const examples: DataExample[] = [
        { id: '1', name: 'Test', data: { address: { street: '123 Main' } } },
      ];

      const result = detectMigrations(schema, examples);

      expect(result.compatible).toBe(false);
      expect(result.migrations[0].issue).toBe('MISSING_REQUIRED');
      expect(result.migrations[0].path).toBe('$.address.city');
    });
  });

  it('handles multiple examples', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        age: { type: 'integer' },
      },
    };
    const examples: DataExample[] = [
      { id: '1', name: 'Example 1', data: { age: '25' } },
      { id: '2', name: 'Example 2', data: { age: '30' } },
    ];

    const result = detectMigrations(schema, examples);

    expect(result.migrations).toHaveLength(2);
    expect(result.migrations[0].exampleId).toBe('1');
    expect(result.migrations[1].exampleId).toBe('2');
  });
});

describe('applyMigration', () => {
  it('applies auto-migratable migration', () => {
    const data: JsonObject = { age: '25' };
    const migration = {
      exampleId: '1',
      exampleName: 'Test',
      path: '$.age',
      issue: 'TYPE_MISMATCH' as const,
      currentValue: '25',
      expectedType: 'integer',
      suggestedValue: 25,
      autoMigratable: true,
    };

    const result = applyMigration(data, migration);

    expect(result.age).toBe(25);
  });

  it('does not modify data for non-migratable migration', () => {
    const data: JsonObject = { complex: { nested: 'value' } };
    const migration = {
      exampleId: '1',
      exampleName: 'Test',
      path: '$.complex',
      issue: 'TYPE_MISMATCH' as const,
      currentValue: { nested: 'value' },
      expectedType: 'string',
      suggestedValue: null,
      autoMigratable: false,
    };

    const result = applyMigration(data, migration);

    expect(result).toEqual(data);
  });

  it('applies migration to nested path', () => {
    const data: JsonObject = { user: { age: '30' } };
    const migration = {
      exampleId: '1',
      exampleName: 'Test',
      path: '$.user.age',
      issue: 'TYPE_MISMATCH' as const,
      currentValue: '30',
      expectedType: 'integer',
      suggestedValue: 30,
      autoMigratable: true,
    };

    const result = applyMigration(data, migration);

    expect((result.user as JsonObject).age).toBe(30);
  });

  it('applies migration to array index', () => {
    const data: JsonObject = { items: [1, '2', 3] };
    const migration = {
      exampleId: '1',
      exampleName: 'Test',
      path: '$.items[1]',
      issue: 'TYPE_MISMATCH' as const,
      currentValue: '2',
      expectedType: 'integer',
      suggestedValue: 2,
      autoMigratable: true,
    };

    const result = applyMigration(data, migration);

    expect((result.items as number[])[1]).toBe(2);
  });

  it('does not mutate original data', () => {
    const data: JsonObject = { age: '25' };
    const migration = {
      exampleId: '1',
      exampleName: 'Test',
      path: '$.age',
      issue: 'TYPE_MISMATCH' as const,
      currentValue: '25',
      expectedType: 'integer',
      suggestedValue: 25,
      autoMigratable: true,
    };

    applyMigration(data, migration);

    expect(data.age).toBe('25');
  });

  it('creates parent objects when path does not exist', () => {
    const data: JsonObject = {};
    const migration = {
      exampleId: '1',
      exampleName: 'Test',
      path: '$.user.name',
      issue: 'MISSING_REQUIRED' as const,
      currentValue: undefined as unknown as string,
      expectedType: 'string',
      suggestedValue: 'John',
      autoMigratable: true,
    };

    const result = applyMigration(data, migration);

    expect((result.user as JsonObject).name).toBe('John');
  });

  it('creates parent arrays when path contains numeric index', () => {
    const data: JsonObject = {};
    const migration = {
      exampleId: '1',
      exampleName: 'Test',
      path: '$.items[0]',
      issue: 'MISSING_REQUIRED' as const,
      currentValue: undefined as unknown as string,
      expectedType: 'string',
      suggestedValue: 'first',
      autoMigratable: true,
    };

    const result = applyMigration(data, migration);

    expect(Array.isArray(result.items)).toBe(true);
    expect((result.items as string[])[0]).toBe('first');
  });
});

describe('applyAllMigrations', () => {
  it('applies multiple migrations', () => {
    const data: JsonObject = { age: '25', count: '10' };
    const migrations = [
      {
        exampleId: '1',
        exampleName: 'Test',
        path: '$.age',
        issue: 'TYPE_MISMATCH' as const,
        currentValue: '25',
        expectedType: 'integer',
        suggestedValue: 25,
        autoMigratable: true,
      },
      {
        exampleId: '1',
        exampleName: 'Test',
        path: '$.count',
        issue: 'TYPE_MISMATCH' as const,
        currentValue: '10',
        expectedType: 'integer',
        suggestedValue: 10,
        autoMigratable: true,
      },
    ];

    const result = applyAllMigrations(data, migrations);

    expect(result.age).toBe(25);
    expect(result.count).toBe(10);
  });

  it('skips non-migratable migrations', () => {
    const data: JsonObject = { valid: '25', invalid: { complex: true } };
    const migrations = [
      {
        exampleId: '1',
        exampleName: 'Test',
        path: '$.valid',
        issue: 'TYPE_MISMATCH' as const,
        currentValue: '25',
        expectedType: 'integer',
        suggestedValue: 25,
        autoMigratable: true,
      },
      {
        exampleId: '1',
        exampleName: 'Test',
        path: '$.invalid',
        issue: 'TYPE_MISMATCH' as const,
        currentValue: { complex: true },
        expectedType: 'string',
        suggestedValue: null,
        autoMigratable: false,
      },
    ];

    const result = applyAllMigrations(data, migrations);

    expect(result.valid).toBe(25);
    expect(result.invalid).toEqual({ complex: true });
  });

  it('handles empty migrations array', () => {
    const data: JsonObject = { name: 'John' };

    const result = applyAllMigrations(data, []);

    expect(result).toEqual(data);
  });
});
