import { describe, expect, it, vi } from 'vitest';
import { DataContractStore } from './DataContractStore.js';
import type { JsonSchema, SaveCallbacks, SchemaCompatibilityPreviewResult } from './types.js';

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
    recentUsage: {
      available: true,
      checkedCount: 0,
      incompatibleCount: 0,
      issues: [],
    },
  };
}

describe('DataContractStore.validateSchemaCompatibility', () => {
  it('returns compatible defaults when there is no schema', async () => {
    const store = new DataContractStore();
    store.init(null, [], {});

    const result = await store.validateSchemaCompatibility();

    expect(result).toEqual({
      compatible: true,
      errors: [],
      migrations: [],
      recentUsage: {
        available: true,
        checkedCount: 0,
        incompatibleCount: 0,
        issues: [],
      },
    });
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
    expect(onValidateSchemaCompatibility).toHaveBeenCalledWith(overrideSchema, store.state.examples);
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
