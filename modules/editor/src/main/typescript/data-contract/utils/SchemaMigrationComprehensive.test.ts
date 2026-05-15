/**
 * Migration detection — comprehensive test suite.
 *
 * Covers all field-type change scenarios: non-$ref types (string, number,
 * integer, boolean, object, array) and $ref-based types (richTextInline,
 * richTextBlock). Validates that required/optional-only changes do NOT
 * trigger false TYPE_MISMATCH for any type.
 *
 * Run: npx vitest run SchemaMigrationComprehensive.test
 */

import { describe, expect, it } from 'vitest';
import { detectMigrations, type MigrationSuggestion } from './schemaMigration.js';
import type { DataExample, JsonObject, JsonSchema } from '../types.js';
import { RICH_TEXT_BLOCK_SCHEMA_REF, RICH_TEXT_INLINE_SCHEMA_REF } from '../types.js';
import { formatValue } from '../sections/MigrationAssistant.js';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const ex = (id: string, data: JsonObject): DataExample => ({ id, name: id, data });

const inlineDoc = {
  type: 'doc' as const,
  content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Hello world' }] }],
};

const blockDoc = {
  type: 'doc' as const,
  content: [
    { type: 'paragraph', content: [{ type: 'text', text: 'Para 1' }] },
    { type: 'paragraph', content: [{ type: 'text', text: 'Para 2' }] },
  ],
};

function dump(m: MigrationSuggestion): string {
  return [
    `path=${m.path}`,
    `issue=${m.issue}`,
    `current=${formatValue(m.currentValue)}`,
    `expected=${m.expectedType}`,
    `auto=${m.autoMigratable}`,
  ].join('  ');
}

// ===========================================================================
// G. Edge cases — uncovered lines in schemaMigration.ts
// ===========================================================================

describe('Edge cases for coverage', () => {
  it('schema with no properties returns compatible', () => {
    const schema: JsonSchema = { type: 'object' };
    const examples = [ex('e1', { field: 'value' } as JsonObject) as unknown as DataExample];
    const { compatible } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(compatible).toBe(true);
  });

  it('non-object root schema returns compatible (line 80)', () => {
    const schema = { type: 'array', items: { type: 'string' } } as unknown as JsonSchema;
    const examples = [ex('e1', { field: 'value' } as JsonObject) as unknown as DataExample];
    const { compatible } = detectMigrations(schema, examples);
    expect(compatible).toBe(true);
  });

  it('array field type mismatched to non-array value hits tryConvert default (line 292)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { items: { type: 'array', items: { type: 'string' } } },
    };
    const examples = [ex('e1', { items: 'not-an-array' } as JsonObject) as unknown as DataExample];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    // TYPE_MISMATCH: actual 'string' vs expected 'array' → tryConvertValue default
    expect(migrations).toHaveLength(1);
    expect(migrations[0].expectedType).toBe('array');
    expect(migrations[0].autoMigratable).toBe(false);
  });

  it('string→boolean conversion is auto-migratable (tryConvertToBoolean line 333)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { active: { type: 'boolean' } },
    };
    // 'true'/'yes'/'1' → true, 'false'/'no'/'0' → false
    const examples = [
      ex('e1', { active: 'yes' as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].autoMigratable).toBe(true);
    expect(migrations[0].suggestedValue).toBe(true);
  });

  it('integer→string conversion is auto-migratable via String() (tryConvertToString line 303)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
    };
    const examples = [
      ex('e1', { name: 42 as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].autoMigratable).toBe(true);
    expect(migrations[0].suggestedValue).toBe('42');
  });

  it('boolean→string conversion is auto-migratable (tryConvertToString line 303)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
    };
    const examples = [
      ex('e1', { name: true as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].autoMigratable).toBe(true);
    expect(migrations[0].suggestedValue).toBe('true');
  });

  it('string→number conversion is auto-migratable (tryConvertToNumber line 317)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { count: { type: 'number' } },
    };
    const examples = [
      ex('e1', { count: '42' as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].autoMigratable).toBe(true);
    expect(migrations[0].suggestedValue).toBe(42);
  });

  it('string→integer conversion via parseInt (tryConvertToNumber integer branch)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { count: { type: 'integer' } },
    };
    const examples = [
      ex('e1', { count: '42' as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations[0].suggestedValue).toBe(42);
  });

  it('array of $ref with invalid item shape (lines 172-174)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        greetings: {
          type: 'array',
          items: { $ref: RICH_TEXT_INLINE_SCHEMA_REF },
        },
      },
    };
    const examples = [
      ex('e1', {
        greetings: [
          inlineDoc, // valid
          'not-a-doc', // invalid — should be flagged
        ] as unknown as JsonObject,
      } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log('array-$ref-items:', `${migrations.length} migration(s)`);
    for (const m of migrations) console.log('  ', dump(m));
    // The invalid item should produce a migration
    const invalidItem = migrations.find((m) => m.path.includes('[1]'));
    expect(invalidItem).toBeDefined();
    expect(invalidItem?.expectedType).toBe('Rich text (inline)');
  });

  it('string→number where string is not parsable → not auto-migratable', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { count: { type: 'number' } },
    };
    const examples = [
      ex('e1', { count: 'abc' as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].autoMigratable).toBe(false);
    expect(migrations[0].suggestedValue).toBeNull();
  });

  it('number→string conversion handles float (tryConvertToString line 303)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
    };
    const examples = [
      ex('e1', { name: 3.14 as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations[0].suggestedValue).toBe('3.14');
  });
});

describe('Non-$ref: no migration when field unchanged', () => {
  const schema: JsonSchema = {
    type: 'object',
    properties: {
      name: { type: 'string' },
      age: { type: 'integer' },
      active: { type: 'boolean' },
    },
    required: ['name'],
  };
  const examples = [ex('e1', { name: 'John', age: 30, active: true }) as JsonObject as DataExample];

  it('detects zero migrations', () => {
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(compatible).toBe(true);
    if (migrations.length > 0) {
      for (const m of migrations) console.log('  UNEXPECTED', dump(m));
    }
    expect(migrations).toHaveLength(0);
  });
});

describe('Non-$ref: no migration on metadata-only changes', () => {
  it('string required→optional with existing value', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
      // not required
    };
    const examples = [ex('e1', { name: 'John' }) as JsonObject as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });

  it('string optional→required with value present', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
      required: ['name'],
    };
    const examples = [ex('e1', { name: 'John' }) as JsonObject as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });

  it('string optional→required with value missing → MISSING_REQUIRED', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
      required: ['name'],
    };
    const examples = [ex('e1', {}) as JsonObject as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(compatible).toBe(false);
    expect(migrations).toHaveLength(1);
    expect(migrations[0].issue).toBe('MISSING_REQUIRED');
    expect(migrations[0].path).toBe('$.name');
  });

  it('integer required→optional with existing value', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { age: { type: 'integer' } },
    };
    const examples = [ex('e1', { age: 42 }) as JsonObject as DataExample];
    const { compatible } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(compatible).toBe(true);
  });
});

// ===========================================================================
// B. Non-$ref types — REAL type mismatches
// ===========================================================================

describe('Non-$ref: real type mismatches', () => {
  it('string→number: value "hello"', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { score: { type: 'number' } },
    };
    const examples = [
      ex('e1', { score: 'hello' as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].issue).toBe('TYPE_MISMATCH');
    expect(migrations[0].currentValue).toBe('hello');
    expect(migrations[0].expectedType).toBe('number');
  });

  it('number→string: value 42', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { name: { type: 'string' } },
    };
    const examples = [
      ex('e1', { name: 42 as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].issue).toBe('TYPE_MISMATCH');
    expect(migrations[0].currentValue).toBe(42);
    expect(migrations[0].expectedType).toBe('string');
  });

  it('string→boolean: value "yes"', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { active: { type: 'boolean' } },
    };
    const examples = [
      ex('e1', { active: 'yes' as unknown as JsonObject } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].currentValue).toBe('yes');
    expect(migrations[0].expectedType).toBe('boolean');
  });

  it('object→string: value plain object', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { address: { type: 'string' } },
    };
    const examples = [
      ex('e1', {
        address: { city: 'NYC' } as unknown as JsonObject,
      } as JsonObject) as unknown as DataExample,
    ];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].issue).toBe('TYPE_MISMATCH');
  });
});

// ===========================================================================
// C. Rich-text inline ($ref) — BUG: false TYPE_MISMATCH on metadata-only changes
// ===========================================================================

describe('Rich-text inline: no migration on metadata-only changes', () => {
  it('required→required with existing value → no migration', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { greeting: { $ref: RICH_TEXT_INLINE_SCHEMA_REF } },
      required: ['greeting'],
    };
    const examples = [ex('e1', { greeting: inlineDoc } as JsonObject) as unknown as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log(
      'inline required→required:',
      compatible ? 'compatible' : `${migrations.length} migration(s)`,
    );
    for (const m of migrations) console.log('  ', dump(m));
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });

  it('optional→optional with existing value → no migration', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { greeting: { $ref: RICH_TEXT_INLINE_SCHEMA_REF } },
      // not required
    };
    const examples = [ex('e1', { greeting: inlineDoc } as JsonObject) as unknown as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log(
      'inline optional→optional:',
      compatible ? 'compatible' : `${migrations.length} migration(s)`,
    );
    for (const m of migrations) console.log('  ', dump(m));
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });

  it('required→optional with existing value → no migration (BUG)', () => {
    // Field was required, now optional. Value exists. No data change.
    const schema: JsonSchema = {
      type: 'object',
      properties: { greeting: { $ref: RICH_TEXT_INLINE_SCHEMA_REF } },
    };
    const examples = [ex('e1', { greeting: inlineDoc } as JsonObject) as unknown as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log(
      'inline required→optional:',
      compatible ? 'compatible' : `${migrations.length} migration(s)`,
    );
    for (const m of migrations) console.log('  ', dump(m));
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });

  it('optional→required with value → no migration', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { greeting: { $ref: RICH_TEXT_INLINE_SCHEMA_REF } },
      required: ['greeting'],
    };
    const examples = [ex('e1', { greeting: inlineDoc } as JsonObject) as unknown as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log(
      'inline optional→required:',
      compatible ? 'compatible' : `${migrations.length} migration(s)`,
    );
    for (const m of migrations) console.log('  ', dump(m));
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });

  it('required with no value → MISSING_REQUIRED (NOT type mismatch)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { greeting: { $ref: RICH_TEXT_INLINE_SCHEMA_REF } },
      required: ['greeting'],
    };
    const examples = [ex('e1', {}) as JsonObject as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(compatible).toBe(false);
    if (migrations.length > 0) {
      expect(migrations[0].issue).toBe('MISSING_REQUIRED');
      expect(migrations[0].issue).not.toBe('TYPE_MISMATCH');
    }
  });
});

// ===========================================================================
// D. Rich-text block ($ref) — BUG: false TYPE_MISMATCH on metadata-only changes
// ===========================================================================

describe('Rich-text block: no migration on metadata-only changes', () => {
  it('optional→optional with value → no migration', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { bio: { $ref: RICH_TEXT_BLOCK_SCHEMA_REF } },
    };
    const examples = [ex('e1', { bio: blockDoc } as JsonObject) as unknown as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log(
      'block optional→optional:',
      compatible ? 'compatible' : `${migrations.length} migration(s)`,
    );
    for (const m of migrations) console.log('  ', dump(m));
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });

  it('required→optional with existing value → no migration (BUG)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { bio: { $ref: RICH_TEXT_BLOCK_SCHEMA_REF } },
    };
    const examples = [ex('e1', { bio: blockDoc } as JsonObject) as unknown as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log(
      'block required→optional:',
      compatible ? 'compatible' : `${migrations.length} migration(s)`,
    );
    for (const m of migrations) console.log('  ', dump(m));
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });
});

// ===========================================================================
// E. Rich-text → non-$ref (real type mismatches)
// ===========================================================================

describe('Rich-text→scalar: real type mismatches', () => {
  it('richTextInline value → string schema', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { greeting: { type: 'string' } },
    };
    const examples = [ex('e1', { greeting: inlineDoc } as JsonObject) as unknown as DataExample];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    // expectedType should NOT be "undefined" — should be a meaningful label
    expect(migrations[0].expectedType).not.toBe('undefined');
    expect(migrations[0].expectedType).toBe('string');
  });

  it('richTextBlock value → integer schema', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { bio: { type: 'integer' } },
    };
    const examples = [ex('e1', { bio: blockDoc } as JsonObject) as unknown as DataExample];
    const { migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    expect(migrations).toHaveLength(1);
    expect(migrations[0].expectedType).toBe('integer');
  });
});

// ===========================================================================
// F. Rich-text → rich-text ($ref → different $ref)
// ===========================================================================

describe('Rich-text $ref changes (inline↔block)', () => {
  it('inline doc → block schema: single-paragraph doc is valid for block too → no migration', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: { content: { $ref: RICH_TEXT_BLOCK_SCHEMA_REF } },
    };
    const examples = [ex('e1', { content: inlineDoc } as JsonObject) as unknown as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log(
      'inline-doc→block-schema:',
      compatible ? 'compatible' : `${migrations.length} migration(s)`,
    );
    for (const m of migrations) console.log('  ', dump(m));
    // A single-paragraph doc is valid under the block schema
    expect(compatible).toBe(true);
    expect(migrations).toHaveLength(0);
  });

  it('block doc with lists → inline schema: should detect issue', () => {
    const listDoc = {
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: 'Title' }] },
        { type: 'bullet_list', content: [] },
      ],
    };
    const schema: JsonSchema = {
      type: 'object',
      properties: { content: { $ref: RICH_TEXT_INLINE_SCHEMA_REF } },
    };
    const examples = [ex('e1', { content: listDoc } as JsonObject) as unknown as DataExample];
    const { compatible, migrations } = detectMigrations(
      schema as unknown as JsonSchema,
      examples as unknown as DataExample[],
    );
    console.log(
      'block-doc→inline-schema:',
      compatible ? 'compatible' : `${migrations.length} migration(s)`,
    );
    for (const m of migrations) console.log('  ', dump(m));
    // A block doc with lists doesn't match inline shape
    expect(compatible).toBe(false);
    if (migrations.length > 0) {
      // migration should relate to shape incompatibility, not a false TYPE_MISMATCH
      expect(migrations[0].expectedType).not.toBe('undefined');
    }
  });
});
