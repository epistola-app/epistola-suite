import { describe, expect, it } from 'vitest';
import { detectBreakingChanges } from './schemaBreakingChanges.js';
import type { SchemaField } from '../types.js';

describe('detectBreakingChanges', () => {
  it('returns empty for identical schemas', () => {
    const fields: SchemaField[] = [
      { id: 'f1', name: 'name', type: 'string', required: true },
      { id: 'f2', name: 'age', type: 'integer', required: false },
    ];

    expect(detectBreakingChanges(fields, fields)).toEqual([]);
  });

  it('detects removed field', () => {
    const old: SchemaField[] = [
      { id: 'f1', name: 'name', type: 'string', required: true },
      { id: 'f2', name: 'age', type: 'integer', required: false },
    ];
    const next: SchemaField[] = [{ id: 'f1', name: 'name', type: 'string', required: true }];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe('removed');
    expect(changes[0].description).toBe('"age" removed');
  });

  it('detects renamed field by ID', () => {
    const old: SchemaField[] = [{ id: 'f1', name: 'firstName', type: 'string', required: true }];
    const next: SchemaField[] = [{ id: 'f1', name: 'fullName', type: 'string', required: true }];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe('renamed');
    expect(changes[0].description).toBe('"firstName" renamed to "fullName"');
  });

  it('detects type change', () => {
    const old: SchemaField[] = [{ id: 'f1', name: 'age', type: 'string', required: false }];
    const next: SchemaField[] = [{ id: 'f1', name: 'age', type: 'integer', required: false }];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe('type_changed');
    expect(changes[0].description).toContain('string');
    expect(changes[0].description).toContain('integer');
  });

  it('detects rename + type change on same field', () => {
    const old: SchemaField[] = [{ id: 'f1', name: 'count', type: 'string', required: false }];
    const next: SchemaField[] = [{ id: 'f1', name: 'total', type: 'integer', required: false }];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(2);
    expect(changes.some((c) => c.type === 'renamed')).toBe(true);
    expect(changes.some((c) => c.type === 'type_changed')).toBe(true);
  });

  it('ignores added fields (not breaking)', () => {
    const old: SchemaField[] = [{ id: 'f1', name: 'name', type: 'string', required: true }];
    const next: SchemaField[] = [
      { id: 'f1', name: 'name', type: 'string', required: true },
      { id: 'f2', name: 'email', type: 'string', required: false },
    ];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(0);
  });

  it('detects changes in nested object fields', () => {
    const old: SchemaField[] = [
      {
        id: 'f1',
        name: 'address',
        type: 'object',
        required: false,
        nestedFields: [
          { id: 'f1.1', name: 'street', type: 'string', required: true },
          { id: 'f1.2', name: 'city', type: 'string', required: true },
        ],
      },
    ];
    const next: SchemaField[] = [
      {
        id: 'f1',
        name: 'address',
        type: 'object',
        required: false,
        nestedFields: [{ id: 'f1.1', name: 'street', type: 'string', required: true }],
      },
    ];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe('removed');
    expect(changes[0].path).toBe('address.city');
  });

  it('detects array item type change', () => {
    const old: SchemaField[] = [
      { id: 'f1', name: 'tags', type: 'array', required: false, arrayItemType: 'string' },
    ];
    const next: SchemaField[] = [
      { id: 'f1', name: 'tags', type: 'array', required: false, arrayItemType: 'integer' },
    ];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe('type_changed');
    expect(changes[0].description).toContain('array<string>');
    expect(changes[0].description).toContain('array<integer>');
  });

  it('returns empty when old schema is empty and new fields are optional', () => {
    const next: SchemaField[] = [{ id: 'f1', name: 'name', type: 'string', required: false }];

    expect(detectBreakingChanges([], next)).toEqual([]);
  });

  it('detects new required field', () => {
    const old: SchemaField[] = [{ id: 'f1', name: 'name', type: 'string', required: true }];
    const next: SchemaField[] = [
      { id: 'f1', name: 'name', type: 'string', required: true },
      { id: 'f2', name: 'email', type: 'string', required: true },
    ];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe('required_added');
    expect(changes[0].description).toBe('"email" added as required');
  });

  it('ignores new optional field', () => {
    const old: SchemaField[] = [{ id: 'f1', name: 'name', type: 'string', required: true }];
    const next: SchemaField[] = [
      { id: 'f1', name: 'name', type: 'string', required: true },
      { id: 'f2', name: 'email', type: 'string', required: false },
    ];

    expect(detectBreakingChanges(old, next)).toEqual([]);
  });

  it('detects optional field made required', () => {
    const old: SchemaField[] = [{ id: 'f1', name: 'email', type: 'string', required: false }];
    const next: SchemaField[] = [{ id: 'f1', name: 'email', type: 'string', required: true }];

    const changes = detectBreakingChanges(old, next);

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe('made_required');
    expect(changes[0].description).toBe('"email" is now required');
  });

  it('does not flag required field staying required', () => {
    const old: SchemaField[] = [{ id: 'f1', name: 'name', type: 'string', required: true }];
    const next: SchemaField[] = [{ id: 'f1', name: 'name', type: 'string', required: true }];

    expect(detectBreakingChanges(old, next)).toEqual([]);
  });

  it('detects new required field added to empty schema', () => {
    const next: SchemaField[] = [{ id: 'f1', name: 'name', type: 'string', required: true }];

    const changes = detectBreakingChanges([], next);

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe('required_added');
  });
});
