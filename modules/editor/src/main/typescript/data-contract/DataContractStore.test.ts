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
