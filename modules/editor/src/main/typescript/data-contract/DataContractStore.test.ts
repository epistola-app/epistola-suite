import { describe, expect, it, vi } from 'vitest';
import { DataContractStore } from './DataContractStore.js';
import type { JsonSchema, SaveCallbacks, SchemaCompatibilityPreviewResult } from './types.js';

/* oxlint-disable eslint/no-use-before-define */
/* oxlint-disable eslint/no-undefined */
/* oxlint-disable oxc/no-rest-spread-properties */
/* oxlint-disable oxc/no-optional-chaining */
/* oxlint-disable oxc/no-async-await */
/* oxlint-disable typescript-eslint/explicit-function-return-type */

const baseSchema: JsonSchema = {
  type: 'object',
  properties: {
    name: { type: 'string' },
    count: { type: 'integer' },
  },
};

function createStore(callbacks: SaveCallbacks = {}): DataContractStore {
  const store = new DataContractStore();
  store.init(
    structuredClone(baseSchema),
    [
      {
        id: 'example-1',
        name: 'Example 1',
        data: {
          name: 'One',
          count: 1,
          extraRoot: 'drop-me',
          items: [{ sku: 'A1', qty: 2, ignored: true }],
          meta: { unknown: 'drop-me-too' },
        },
      },
    ],
    callbacks,
  );
  return store;
}

function compatibleResult(): SchemaCompatibilityPreviewResult {
  return {
    compatible: true,
    errors: [],
    migrations: [],
    recentUsage: emptyRecentUsage(),
  };
}

function emptyRecentUsage(overrides = {}) {
  return {
    available: true,
    window: {
      maxDays: 7,
      sampleLimit: 100,
      checkedFrom: '2026-04-12T00:00:00Z',
      checkedTo: '2026-04-19T00:00:00Z',
    },
    summary: {
      checkedCount: 0,
      compatibleCount: 0,
      incompatibleCount: 0,
    },
    samples: [],
    issues: [],
    ...overrides,
  };
}

describe('DataContractStore.validateSchemaCompatibility', () => {
  it('returns compatible defaults when there is no schema', async () => {
    const store = new DataContractStore();
    store.init(null, [], {});

    const result = await store.validateSchemaCompatibility();

    expect(result.compatible).toBe(true);
    expect(result.errors).toEqual([]);
    expect(result.migrations).toEqual([]);
    expect(result.recentUsage.available).toBe(true);
    expect(result.recentUsage.summary).toEqual({
      checkedCount: 0,
      compatibleCount: 0,
      incompatibleCount: 0,
    });
    expect(result.recentUsage.samples).toEqual([]);
    expect(result.recentUsage.issues).toEqual([]);
    expect(result.recentUsage.window.maxDays).toBe(0);
    expect(result.recentUsage.window.sampleLimit).toBe(0);
  });

  it('uses provided schemaToValidate instead of state schema', async () => {
    const onValidateSchemaCompatibility = vi.fn().mockResolvedValue(compatibleResult());
    const store = createStore({ onValidateSchemaCompatibility });
    const overrideSchema: JsonSchema = {
      type: 'object',
      properties: {
        active: { type: 'boolean' },
      },
    };

    await store.validateSchemaCompatibility(overrideSchema);

    expect(onValidateSchemaCompatibility).toHaveBeenCalledTimes(1);
    expect(onValidateSchemaCompatibility).toHaveBeenCalledWith(
      overrideSchema,
      store.state.examples,
    );
  });

  it('returns unavailable result when callback throws', async () => {
    const store = createStore({
      onValidateSchemaCompatibility: vi.fn().mockRejectedValue(new Error('compat service down')),
    });

    const result = await store.validateSchemaCompatibility();

    expect(result.compatible).toBe(false);
    expect(result.recentUsage.available).toBe(false);
    expect(result.recentUsage.unavailableReason).toBe('compat service down');
    expect(result.error).toBe('compat service down');
  });
});

describe('DataContractStore.saveSchema', () => {
  it('passes null schema to callback when raw json schema is invalid root object', async () => {
    const onSaveSchema = vi.fn().mockResolvedValue({ success: true });
    const store = createStore({ onSaveSchema });

    store.dispatch({
      type: 'set-raw-json-schema',
      mode: 'json-only',
      schema: { properties: { name: { type: 'string' } } },
    });

    const result = await store.saveSchema(false);

    expect(result.success).toBe(true);
    expect(onSaveSchema).toHaveBeenCalledWith(null, false, undefined);
  });
});

describe('DataContractStore.pruneExamplesForSchema', () => {
  it('returns a cloned copy when schema is null', () => {
    const store = createStore();

    const pruned = store.pruneExamplesForSchema(null);

    expect(pruned).toEqual(store.state.examples);
    expect(pruned).not.toBe(store.state.examples);
    expect(pruned[0]).not.toBe(store.state.examples[0]);
  });

  it('removes unknown keys recursively for objects and arrays', () => {
    const store = createStore();
    const strictSchema: JsonSchema = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        count: { type: 'integer' },
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              sku: { type: 'string' },
              qty: { type: 'integer' },
            },
          },
        },
        meta: {
          type: 'object',
          properties: {},
        },
      },
    };

    const pruned = store.pruneExamplesForSchema(strictSchema);

    expect(pruned[0]?.data).toEqual({
      name: 'One',
      count: 1,
      items: [{ sku: 'A1', qty: 2 }],
      meta: {},
    });
  });
});

describe('DataContractStore.init', () => {
  it('resets transient UI and history state when re-initialized', () => {
    const store = createStore();

    store.dispatch({ type: 'select-tab', tab: 'examples' });
    store.dispatch({
      type: 'update-example-data',
      exampleId: 'example-1',
      path: 'count',
      value: 2,
    });
    store.dispatch({
      type: 'open-fix-screen',
      newSchema: baseSchema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/count',
          issue: 'TYPE_MISMATCH',
          currentValue: '2',
          expectedType: 'integer',
          suggestedValue: 2,
          autoMigratable: true,
        },
      ],
    });

    store.init(
      structuredClone(baseSchema),
      [{ id: 'example-2', name: 'Second', data: { name: 'Two', count: 2 } }],
      {},
    );

    expect(store.state.activeTab).toBe('schema');
    expect(store.state.fixScreen).toBeNull();
    expect(store.state.exampleHistories.size).toBe(0);
    expect(store.state.selectedExampleId).toBe('example-2');
    expect(store.state.saveStatus).toEqual({ type: 'idle' });
  });

  it('clones initial schema and examples so external mutations do not leak into state', () => {
    const schema: JsonSchema = structuredClone(baseSchema);
    const examples = [{ id: 'example-1', name: 'Example 1', data: { name: 'One', count: 1 } }];
    const store = new DataContractStore();

    store.init(schema, examples, {});

    if (!schema.properties) {
      throw new Error('schema.properties expected in test setup');
    }
    schema.properties.name = { type: 'integer' };
    examples[0].data.count = 999;

    expect(store.state.schema?.properties?.name?.type).toBe('string');
    expect(store.state.examples[0]?.data.count).toBe(1);
  });

  it('clones raw json schema when initial schema is incompatible', () => {
    const incompatible = {
      type: 'object',
      properties: {
        name: {
          type: 'string',
          enum: ['a'],
        },
      },
    } as unknown as JsonSchema;

    const store = new DataContractStore();
    store.init(incompatible, [], {});

    const nameProp = incompatible.properties?.name as { type: string } | undefined;
    if (!nameProp) {
      throw new Error('name property expected in test setup');
    }
    nameProp.type = 'integer';

    expect(store.state.schemaEditMode).toBe('json-only');
    expect((store.state.rawJsonSchema as JsonSchema | null)?.properties?.name?.type).toBe('string');
    expect(store.state.schema?.properties?.name?.type).toBe('string');
  });
});

describe('DataContractStore dirty tracking', () => {
  it('tracks schema dirty and clears it after revert', () => {
    const store = createStore();

    expect(store.isSchemaDirty).toBe(false);

    store.dispatch({
      type: 'set-schema',
      schema: {
        ...baseSchema,
        properties: {
          ...baseSchema.properties,
          enabled: { type: 'boolean' },
        },
      },
    });

    expect(store.isSchemaDirty).toBe(true);

    store.dispatch({ type: 'revert-to-committed' });

    expect(store.isSchemaDirty).toBe(false);
  });

  it('tracks examples dirty and clears it when examples are committed', () => {
    const store = createStore();

    expect(store.isExamplesDirty).toBe(false);

    store.dispatch({
      type: 'update-example-name',
      exampleId: 'example-1',
      name: 'Renamed',
    });

    expect(store.isExamplesDirty).toBe(true);

    store.dispatch({ type: 'commit-examples' });

    expect(store.isExamplesDirty).toBe(false);
  });

  it('treats key-reordered json-only schema as clean', () => {
    const store = createStore();

    store.dispatch({
      type: 'set-raw-json-schema',
      mode: 'json-only',
      schema: {
        type: 'object',
        properties: {
          a: { type: 'string' },
          b: { type: 'number' },
        },
      },
      asCommitted: true,
    });

    store.dispatch({
      type: 'set-raw-json-schema',
      mode: 'json-only',
      schema: {
        type: 'object',
        properties: {
          b: { type: 'number' },
          a: { type: 'string' },
        },
      },
    });

    expect(store.isSchemaDirty).toBe(false);
  });
});

describe('DataContractStore fix screen', () => {
  it('opens fix screen with migration fields', () => {
    const store = createStore();

    store.dispatch({
      type: 'open-fix-screen',
      newSchema: baseSchema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/count',
          issue: 'TYPE_MISMATCH',
          currentValue: '2',
          expectedType: 'integer',
          suggestedValue: 2,
          autoMigratable: true,
        },
      ],
    });

    expect(store.state.fixScreen).not.toBeNull();
    expect(store.state.fixScreen?.migrations).toHaveLength(1);
    expect(store.state.fixScreen?.fields.get('example-1:/count')).toEqual({
      value: '2',
      removed: false,
    });
  });

  it('coerces field change to expected type', () => {
    const store = createStore();

    store.dispatch({
      type: 'open-fix-screen',
      newSchema: baseSchema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/count',
          issue: 'TYPE_MISMATCH',
          currentValue: '2',
          expectedType: 'integer',
          suggestedValue: 2,
          autoMigratable: true,
        },
      ],
    });

    store.dispatch({
      type: 'fix-field-change',
      exampleId: 'example-1',
      path: '/count',
      value: '42',
    });

    const editedData = store.state.fixScreen?.editedData.get('example-1');
    expect(editedData?.count).toBe(42);
  });

  it('marks field as removed', () => {
    const store = createStore();

    store.dispatch({
      type: 'open-fix-screen',
      newSchema: baseSchema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/count',
          issue: 'UNKNOWN_FIELD',
          currentValue: 2,
          expectedType: 'integer',
          suggestedValue: 2,
          autoMigratable: true,
        },
      ],
    });

    store.dispatch({
      type: 'fix-remove-field',
      exampleId: 'example-1',
      path: '/count',
    });

    expect(store.state.fixScreen?.fields.get('example-1:/count')?.removed).toBe(true);
    const editedData = store.state.fixScreen?.editedData.get('example-1');
    expect(editedData?.count).toBeUndefined();
  });

  it('removes all unknown fields', () => {
    const store = createStore();

    store.dispatch({
      type: 'open-fix-screen',
      newSchema: baseSchema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/count',
          issue: 'UNKNOWN_FIELD',
          currentValue: 2,
          expectedType: 'integer',
          suggestedValue: 2,
          autoMigratable: true,
        },
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/name',
          issue: 'TYPE_MISMATCH',
          currentValue: 'hello',
          expectedType: 'string',
          suggestedValue: 'hello',
          autoMigratable: true,
        },
      ],
    });

    store.dispatch({ type: 'fix-remove-all-unknown' });

    expect(store.state.fixScreen?.fields.get('example-1:/count')?.removed).toBe(true);
    expect(store.state.fixScreen?.fields.get('example-1:/name')?.removed).toBe(false);
  });

  it('closes fix screen', () => {
    const store = createStore();

    store.dispatch({
      type: 'open-fix-screen',
      newSchema: baseSchema,
      migrations: [],
    });

    expect(store.state.fixScreen).not.toBeNull();

    store.dispatch({ type: 'close-fix-screen' });

    expect(store.state.fixScreen).toBeNull();
  });

  it('builds fixed examples from edited data', () => {
    const store = createStore();

    store.dispatch({
      type: 'open-fix-screen',
      newSchema: baseSchema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/count',
          issue: 'TYPE_MISMATCH',
          currentValue: '2',
          expectedType: 'integer',
          suggestedValue: 2,
          autoMigratable: true,
        },
      ],
    });

    store.dispatch({
      type: 'fix-field-change',
      exampleId: 'example-1',
      path: '/count',
      value: '99',
    });

    const fixed = store.buildFixedExamples();
    expect(fixed).toHaveLength(1);
    expect(fixed?.[0]?.data.count).toBe(99);
  });

  it('validates fix screen fields against new schema', () => {
    const store = createStore();

    store.dispatch({
      type: 'open-fix-screen',
      newSchema: baseSchema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/count',
          issue: 'TYPE_MISMATCH',
          currentValue: 'not-a-number',
          expectedType: 'integer',
          suggestedValue: 0,
          autoMigratable: true,
        },
      ],
    });

    store.dispatch({
      type: 'fix-field-change',
      exampleId: 'example-1',
      path: '/count',
      value: 'still-not-a-number',
    });

    const isValid = store.validateFixScreenFields();
    expect(isValid).toBe(false);
    expect(store.state.fixScreen?.errors.get('example-1:/count')).toBeDefined();
  });
});

describe('DataContractStore undo/redo', () => {
  it('undo returns true when history exists', () => {
    const store = createStore();

    store.dispatch({
      type: 'update-example-data',
      exampleId: 'example-1',
      path: 'count',
      value: 99,
    });

    expect(store.exampleCanUndo).toBe(true);

    store.dispatch({ type: 'undo-example' });

    expect(store.state.examples[0]?.data.count).toBe(1);
    expect(store.exampleCanUndo).toBe(false);
  });

  it('redo restores undone change', () => {
    const store = createStore();

    store.dispatch({
      type: 'update-example-data',
      exampleId: 'example-1',
      path: 'count',
      value: 99,
    });

    store.dispatch({ type: 'undo-example' });
    expect(store.state.examples[0]?.data.count).toBe(1);

    store.dispatch({ type: 'redo-example' });
    expect(store.state.examples[0]?.data.count).toBe(99);
  });

  it('undo is no-op when no selected example', () => {
    const store = new DataContractStore();
    store.init(baseSchema, [], {});

    expect(store.exampleCanUndo).toBe(false);
    store.dispatch({ type: 'undo-example' });
    expect(store.state.examples).toEqual([]);
  });

  it('redo is no-op when no selected example', () => {
    const store = new DataContractStore();
    store.init(baseSchema, [], {});

    expect(store.exampleCanRedo).toBe(false);
    store.dispatch({ type: 'redo-example' });
    expect(store.state.examples).toEqual([]);
  });
});

describe('DataContractStore save callbacks', () => {
  it('saveSchema commits locally when no callback provided', async () => {
    const store = createStore();

    store.dispatch({
      type: 'set-schema',
      schema: {
        ...baseSchema,
        properties: {
          ...baseSchema.properties,
          extra: { type: 'boolean' },
        },
      },
    });

    expect(store.isSchemaDirty).toBe(true);

    const result = await store.saveSchema();

    expect(result.success).toBe(true);
    expect(store.isSchemaDirty).toBe(false);
  });

  it('saveSchema propagates callback error', async () => {
    const store = createStore({
      onSaveSchema: vi.fn().mockRejectedValue(new Error('save failed')),
    });

    const result = await store.saveSchema();

    expect(result.success).toBe(false);
    expect(result.error).toBe('save failed');
  });

  it('saveExamples commits locally when no callback provided', async () => {
    const store = createStore();

    store.dispatch({
      type: 'update-example-name',
      exampleId: 'example-1',
      name: 'Renamed',
    });

    expect(store.isExamplesDirty).toBe(true);

    const result = await store.saveExamples();

    expect(result.success).toBe(true);
    expect(store.isExamplesDirty).toBe(false);
  });

  it('saveExamples propagates callback error', async () => {
    const store = createStore({
      onSaveDataExamples: vi.fn().mockRejectedValue(new Error('save failed')),
    });

    const result = await store.saveExamples();

    expect(result.success).toBe(false);
    expect(result.error).toBe('save failed');
  });
});

describe('DataContractStore host management', () => {
  it('attaches and detaches host controller', () => {
    const store = new DataContractStore();
    const host = {
      requestUpdate: vi.fn(),
      addController: vi.fn(),
      removeController: vi.fn(),
    };

    store.setHost(host);

    expect(host.addController).toHaveBeenCalledTimes(1);

    store.setHost(host);

    expect(host.addController).toHaveBeenCalledTimes(1);

    const newHost = {
      requestUpdate: vi.fn(),
      addController: vi.fn(),
      removeController: vi.fn(),
    };

    store.setHost(newHost);

    expect(host.removeController).toHaveBeenCalledTimes(1);
    expect(newHost.addController).toHaveBeenCalledTimes(1);
  });

  it('schedules update via host', async () => {
    const store = new DataContractStore();
    const host = {
      requestUpdate: vi.fn(),
      addController: vi.fn(),
      removeController: vi.fn(),
    };

    store.setHost(host);
    store.init(baseSchema, [], {});

    await new Promise((resolve) => queueMicrotask(resolve));

    expect(host.requestUpdate).toHaveBeenCalled();
  });
});
