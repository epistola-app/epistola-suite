import { describe, expect, it } from 'vitest';
import { createInitialState, findNewFieldId, defaultRecentUsageSummary } from './state-init.js';
import type { SchemaField } from '../types.js';

describe('createInitialState', () => {
  it('returns initial state with expected defaults', () => {
    const state = createInitialState();

    expect(state.schema).toBeNull();
    expect(state.committedSchema).toBeNull();
    expect(state.examples).toEqual([]);
    expect(state.committedExamples).toEqual([]);
    expect(state.schemaDirty).toBe(false);
    expect(state.examplesDirty).toBe(false);
    expect(state.visualSchema).toEqual({ fields: [] });
    expect(state.schemaEditMode).toBe('visual');
    expect(state.rawJsonSchema).toBeNull();
    expect(state.committedRawJsonSchema).toBeNull();
    expect(state.activeTab).toBe('schema');
    expect(state.selectedFieldId).toBeNull();
    expect(state.selectedExampleId).toBeNull();
    expect(state.expandedFields).toBeInstanceOf(Set);
    expect(state.schemaViewMode).toBe('visual');
    expect(state.fixScreen).toBeNull();
    expect(state.saveStatus).toEqual({ type: 'idle' });
    expect(state.validationErrors).toBeInstanceOf(Map);
    expect(state.schemaWarnings).toEqual([]);
    expect(state.exampleHistories).toBeInstanceOf(Map);
  });

  it('creates independent instances', () => {
    const s1 = createInitialState();
    const s2 = createInitialState();

    expect(s1).not.toBe(s2);
    expect(s1.examples).not.toBe(s2.examples);
    expect(s1.expandedFields).not.toBe(s2.expandedFields);
  });
});

describe('findNewFieldId', () => {
  const fieldA: SchemaField = {
    id: 'a',
    name: 'Field A',
    type: 'string',
    required: false,
  };

  const fieldB: SchemaField = {
    id: 'b',
    name: 'Field B',
    type: 'number',
    required: true,
  };

  const fieldC: SchemaField = {
    id: 'c',
    name: 'Field C',
    type: 'object',
    required: false,
    nestedFields: [{ id: 'c1', name: 'Nested C1', type: 'string', required: false }],
  };

  it('returns null when fields are identical', () => {
    expect(findNewFieldId([fieldA], [fieldA])).toBeNull();
  });

  it('finds new root field', () => {
    expect(findNewFieldId([fieldA], [fieldA, fieldB])).toBe('b');
  });

  it('finds new nested field', () => {
    const newC: SchemaField = {
      ...fieldC,
      nestedFields: [
        ...(fieldC.nestedFields ?? []),
        { id: 'c2', name: 'Nested C2', type: 'string', required: false },
      ],
    };
    expect(findNewFieldId([fieldC], [newC])).toBe('c2');
  });

  it('returns null when new fields is shorter', () => {
    expect(findNewFieldId([fieldA, fieldB], [fieldA])).toBeNull();
  });

  it('handles empty old fields', () => {
    expect(findNewFieldId([], [fieldA])).toBe('a');
  });

  it('handles empty new fields', () => {
    expect(findNewFieldId([fieldA], [])).toBeNull();
  });

  it('finds new field in array type with nested fields', () => {
    const oldArray: SchemaField = {
      id: 'arr',
      name: 'Array',
      type: 'array',
      required: false,
      nestedFields: [{ id: 'arr1', name: 'Item 1', type: 'string', required: false }],
    };
    const newArray: SchemaField = {
      ...oldArray,
      nestedFields: [
        ...(oldArray.nestedFields ?? []),
        { id: 'arr2', name: 'Item 2', type: 'string', required: false },
      ],
    };
    expect(findNewFieldId([oldArray], [newArray])).toBe('arr2');
  });
});

describe('defaultRecentUsageSummary', () => {
  it('returns summary with zeroed counts', () => {
    const summary = defaultRecentUsageSummary();

    expect(summary.available).toBe(true);
    expect(summary.window.maxDays).toBe(0);
    expect(summary.window.sampleLimit).toBe(0);
    expect(summary.summary.checkedCount).toBe(0);
    expect(summary.summary.compatibleCount).toBe(0);
    expect(summary.summary.incompatibleCount).toBe(0);
    expect(summary.samples).toEqual([]);
    expect(summary.issues).toEqual([]);
  });

  it('uses current ISO timestamp', () => {
    const before = Date.now();
    const summary = defaultRecentUsageSummary();
    const after = Date.now();

    expect(new Date(summary.window.checkedFrom).getTime()).toBeGreaterThanOrEqual(before);
    expect(new Date(summary.window.checkedTo).getTime()).toBeLessThanOrEqual(after);
  });

  it('creates independent instances', () => {
    const s1 = defaultRecentUsageSummary();
    const s2 = defaultRecentUsageSummary();

    expect(s1).not.toBe(s2);
    expect(s1.samples).not.toBe(s2.samples);
    expect(s1.issues).not.toBe(s2.issues);
  });
});
