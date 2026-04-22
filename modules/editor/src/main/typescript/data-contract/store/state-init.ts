import type { RecentUsageCompatibilitySummary, SchemaField } from '../types.js';
import { SchemaCommandHistory } from '../utils/schemaCommandHistory.js';
import type { EditorState } from '../store-types.js';

export function createInitialState(): EditorState {
  return {
    schema: null,
    committedSchema: null,
    examples: [],
    committedExamples: [],
    schemaDirty: false,
    examplesDirty: false,
    visualSchema: { fields: [] },
    schemaEditMode: 'visual',
    rawJsonSchema: null,
    committedRawJsonSchema: null,
    schemaCommandHistory: new SchemaCommandHistory(),
    activeTab: 'schema',
    selectedFieldId: null,
    selectedExampleId: null,
    expandedFields: new Set(),
    schemaViewMode: 'visual',
    fixScreen: null,
    saveStatus: { type: 'idle' },
    validationErrors: new Map(),
    schemaWarnings: [],
    exampleHistories: new Map(),
  };
}

export function findNewFieldId(
  oldFields: readonly SchemaField[],
  newFields: readonly SchemaField[],
): string | null {
  const oldIds = new Set<string>();
  const collectIds = (fields: readonly SchemaField[]): void => {
    for (const f of fields) {
      oldIds.add(f.id);
      if ((f.type === 'object' || f.type === 'array') && f.nestedFields) {
        collectIds(f.nestedFields);
      }
    }
  };
  collectIds(oldFields);

  const findNew = (fields: readonly SchemaField[]): string | null => {
    for (const f of fields) {
      if (!oldIds.has(f.id)) return f.id;
      if ((f.type === 'object' || f.type === 'array') && f.nestedFields) {
        const found = findNew(f.nestedFields);
        if (found) return found;
      }
    }
    return null;
  };
  return findNew(newFields);
}

export function defaultRecentUsageSummary(): RecentUsageCompatibilitySummary {
  const now = new Date().toISOString();
  return {
    available: true,
    window: {
      maxDays: 0,
      sampleLimit: 0,
      checkedFrom: now,
      checkedTo: now,
    },
    summary: {
      checkedCount: 0,
      compatibleCount: 0,
      incompatibleCount: 0,
    },
    samples: [],
    issues: [],
  };
}
