import { describe, expect, it, vi } from 'vitest';
import { DataContractStore } from './DataContractStore.js';
import { executeSave, flattenCompatibilityWarnings, orchestrateSave } from './SaveOrchestrator.js';
import type {
  JsonSchema,
  SaveCallbacks,
  SchemaCompatibilityPreviewResult,
  ValidationError,
} from './types.js';

const baseSchema: JsonSchema = {
  type: 'object',
  properties: {
    count: { type: 'integer' },
    name: { type: 'string' },
  },
};

function createStore(callbacks: SaveCallbacks = {}): DataContractStore {
  const store = new DataContractStore();
  store.init(
    structuredClone(baseSchema),
    [
      { id: 'example-1', name: 'Example 1', data: { count: 1, name: 'One' } },
      { id: 'example-2', name: 'Example 2', data: { count: 2, name: 'Two' } },
    ],
    callbacks,
  );
  return store;
}

function incompatibleResult(
  overrides: Partial<SchemaCompatibilityPreviewResult> = {},
): SchemaCompatibilityPreviewResult {
  return {
    compatible: false,
    errors: [],
    migrations: [],
    recentUsage: {
      available: true,
      checkedCount: 0,
      incompatibleCount: 0,
      issues: [],
    },
    ...overrides,
  };
}

describe('orchestrateSave', () => {
  it('returns save-schema force=true for force-save intent', () => {
    const store = createStore();

    expect(orchestrateSave(store, { type: 'force-save' })).toEqual({
      action: 'save-schema',
      force: true,
    });
  });

  it('returns none for fix-and-save when fix screen is closed', () => {
    const store = createStore();

    expect(orchestrateSave(store, { type: 'fix-and-save' })).toEqual({ action: 'none' });
  });

  it('returns none when schema and examples are clean', () => {
    const store = createStore();

    expect(orchestrateSave(store, { type: 'save' })).toEqual({ action: 'none' });
  });

  it('returns save-examples when only examples are dirty', () => {
    const store = createStore();

    store.dispatch({
      type: 'set-examples',
      examples: [{ id: 'example-1', name: 'Example 1', data: { count: 999, name: 'Changed' } }],
    });

    expect(orchestrateSave(store, { type: 'save' })).toEqual({ action: 'save-examples' });
  });

  it('opens fix screen when schema is incompatible and migrations exist', () => {
    const store = createStore();
    const nextSchema: JsonSchema = {
      ...baseSchema,
      properties: {
        ...baseSchema.properties,
        enabled: { type: 'boolean' },
      },
    };
    const migrations = [
      {
        exampleId: 'example-1',
        exampleName: 'Example 1',
        path: '/count',
        issue: 'TYPE_MISMATCH' as const,
        currentValue: '1',
        expectedType: 'integer',
        suggestedValue: 1,
        autoMigratable: true,
      },
    ];

    store.dispatch({ type: 'set-schema', schema: nextSchema });

    const outcome = orchestrateSave(store, { type: 'save' }, incompatibleResult({ migrations }));

    expect(outcome).toEqual({
      action: 'open-fix-screen',
      migrations,
      newSchema: nextSchema,
    });
  });

  it('returns force-save error when recent usage is unavailable', () => {
    const store = createStore();
    const nextSchema: JsonSchema = {
      ...baseSchema,
      properties: { ...baseSchema.properties, total: { type: 'number' } },
    };
    store.dispatch({ type: 'set-schema', schema: nextSchema });

    const outcome = orchestrateSave(
      store,
      { type: 'save' },
      incompatibleResult({
        recentUsage: {
          available: false,
          checkedCount: 0,
          incompatibleCount: 0,
          issues: [],
          unavailableReason: 'compat service unavailable',
        },
      }),
    );

    expect(outcome).toEqual({
      action: 'error',
      message: 'compat service unavailable',
      canForceSave: true,
    });
  });

  it('returns force-save error with recent usage incompatibility count', () => {
    const store = createStore();
    store.dispatch({
      type: 'set-schema',
      schema: {
        ...baseSchema,
        properties: { ...baseSchema.properties, extra: { type: 'string' } },
      },
    });

    const outcome = orchestrateSave(
      store,
      { type: 'save' },
      incompatibleResult({
        recentUsage: {
          available: true,
          checkedCount: 7,
          incompatibleCount: 3,
          issues: [],
        },
      }),
    );

    expect(outcome).toEqual({
      action: 'error',
      message: 'Schema incompatible with 3 of 7 recent generation requests.',
      canForceSave: true,
    });
  });

  it('returns force-save error with test-data incompatibility message', () => {
    const store = createStore();
    store.dispatch({
      type: 'set-schema',
      schema: {
        ...baseSchema,
        properties: { ...baseSchema.properties, extra: { type: 'string' } },
      },
    });

    const outcome = orchestrateSave(
      store,
      { type: 'save' },
      incompatibleResult({
        errors: [{ path: '$.count', message: 'must be integer' }],
      }),
    );

    expect(outcome).toEqual({
      action: 'error',
      message: 'Schema incompatible with current test data.',
      canForceSave: true,
    });
  });

  it('returns save-schema with examples for atomic save when schema and examples are dirty', () => {
    const store = createStore();
    const nextExamples = [
      { id: 'example-1', name: 'Example 1', data: { count: 7, name: 'Seven' } },
    ];

    store.dispatch({
      type: 'set-schema',
      schema: {
        ...baseSchema,
        properties: { ...baseSchema.properties, enabled: { type: 'boolean' } },
      },
    });
    store.dispatch({ type: 'set-examples', examples: nextExamples });

    expect(orchestrateSave(store, { type: 'save' })).toEqual({
      action: 'save-schema',
      force: false,
      examples: nextExamples,
    });
  });

  it('returns save-schema when only schema is dirty', () => {
    const store = createStore();

    store.dispatch({
      type: 'set-schema',
      schema: {
        ...baseSchema,
        properties: { ...baseSchema.properties, enabled: { type: 'boolean' } },
      },
    });

    expect(orchestrateSave(store, { type: 'save' })).toEqual({
      action: 'save-schema',
      force: false,
    });
  });
});

describe('executeSave', () => {
  it('sends the full example set when saving from the fix screen', async () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        count: { type: 'string' },
        name: { type: 'string' },
      },
    };

    const store = new DataContractStore();
    const onSaveSchema = vi.fn().mockResolvedValue({ success: true });

    store.init(
      schema,
      [
        { id: 'example-1', name: 'Example 1', data: { count: 42, name: 'One' } },
        { id: 'example-2', name: 'Example 2', data: { count: 'ok', name: 'Two' } },
      ],
      { onSaveSchema },
    );

    store.dispatch({
      type: 'open-fix-screen',
      newSchema: schema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: 'count',
          issue: 'TYPE_MISMATCH',
          currentValue: 42,
          expectedType: 'string',
          suggestedValue: '42',
          autoMigratable: true,
        },
      ],
    });

    store.dispatch({
      type: 'fix-field-change',
      exampleId: 'example-1',
      path: 'count',
      value: '42',
    });

    const outcome = orchestrateSave(store, { type: 'fix-and-save' });
    await executeSave(store, outcome);

    expect(onSaveSchema).toHaveBeenCalledTimes(1);
    // Use partial matching instead of toEqual to keep this focused on the
    // schema/fix-screen contract and avoid brittle failures on unrelated
    // shape/order changes in example payloads.
    expect(onSaveSchema).toHaveBeenCalledWith(
      schema,
      false,
      expect.arrayContaining([
        expect.objectContaining({ id: 'example-1', data: { count: '42', name: 'One' } }),
        expect.objectContaining({ id: 'example-2', data: { count: 'ok', name: 'Two' } }),
      ]),
    );

    const sentExamples = onSaveSchema.mock.calls[0]?.[2];
    expect(sentExamples).toHaveLength(2);
  });

  it('uses entered missing-required values in schema save payload from fix screen', async () => {
    const nextSchema: JsonSchema = {
      type: 'object',
      required: ['name', 'email'],
      properties: {
        name: { type: 'string' },
        email: { type: 'string' },
      },
    };

    const onSaveSchema = vi.fn().mockResolvedValue({ success: true });
    const store = new DataContractStore();
    store.init(
      {
        type: 'object',
        required: ['name'],
        properties: {
          name: { type: 'string' },
        },
      },
      [
        { id: 'example-1', name: 'Example 1', data: { name: 'One' } },
        { id: 'example-2', name: 'Example 2', data: { name: 'Two', email: 'two@epistola.dev' } },
      ],
      { onSaveSchema },
    );

    store.dispatch({ type: 'set-schema', schema: nextSchema });
    store.dispatch({
      type: 'open-fix-screen',
      newSchema: nextSchema,
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/email',
          issue: 'MISSING_REQUIRED',
          currentValue: null,
          expectedType: 'string',
          suggestedValue: '',
          autoMigratable: false,
        },
      ],
    });
    store.dispatch({
      type: 'fix-field-change',
      exampleId: 'example-1',
      path: '/email',
      value: 'one@epistola.dev',
    });

    const outcome = orchestrateSave(store, { type: 'fix-and-save' });
    await executeSave(store, outcome);

    expect(onSaveSchema).toHaveBeenCalledTimes(1);
    expect(onSaveSchema).toHaveBeenCalledWith(
      nextSchema,
      false,
      expect.arrayContaining([
        expect.objectContaining({
          id: 'example-1',
          data: { name: 'One', email: 'one@epistola.dev' },
        }),
        expect.objectContaining({
          id: 'example-2',
          data: { name: 'Two', email: 'two@epistola.dev' },
        }),
      ]),
    );
  });

  it('sets save-error with canForceSave=false when saving examples fails', async () => {
    const store = createStore({
      onSaveDataExamples: vi.fn().mockResolvedValue({
        success: false,
        error: 'examples failed',
      }),
    });

    const result = await executeSave(store, { action: 'save-examples' });

    expect(result).toEqual({ success: false, error: 'examples failed' });
    expect(store.state.saveStatus).toEqual({
      type: 'error',
      message: 'examples failed',
      canForceSave: false,
    });
  });

  it('sets save-error with force option and flattens warnings when schema save fails', async () => {
    const warningA: ValidationError = { path: '$.x', message: 'x invalid' };
    const warningB: ValidationError = { path: '$.y', message: 'y invalid' };

    const store = createStore({
      onSaveSchema: vi.fn().mockResolvedValue({
        success: false,
        error: 'schema failed',
        warnings: {
          first: [warningA],
          second: [warningB],
        },
      }),
    });

    const result = await executeSave(store, { action: 'save-schema', force: false });

    expect(result).toEqual({ success: false, error: 'schema failed' });
    expect(store.state.saveStatus).toEqual({
      type: 'error',
      message: 'schema failed',
      canForceSave: true,
    });
    expect(store.state.schemaWarnings).toEqual([warningA, warningB]);
  });

  it('opens fix screen outcome through store dispatch', async () => {
    const store = createStore();
    const nextSchema: JsonSchema = {
      ...baseSchema,
      properties: { ...baseSchema.properties, active: { type: 'boolean' } },
    };
    const migrations = [
      {
        exampleId: 'example-1',
        exampleName: 'Example 1',
        path: '/count',
        issue: 'TYPE_MISMATCH' as const,
        currentValue: '1',
        expectedType: 'integer',
        suggestedValue: 1,
        autoMigratable: true,
      },
    ];

    const result = await executeSave(store, {
      action: 'open-fix-screen',
      migrations,
      newSchema: nextSchema,
    });

    expect(result).toEqual({ success: true });
    expect(store.state.fixScreen).not.toBeNull();
    expect(store.state.fixScreen?.migrations).toEqual(migrations);
    expect(store.state.fixScreen?.newSchema).toEqual(nextSchema);
  });

  it('dispatches save-error for error and force-save outcomes', async () => {
    const store = createStore();

    const errorResult = await executeSave(store, {
      action: 'error',
      message: 'blocked by compatibility',
      canForceSave: true,
    });
    expect(errorResult).toEqual({ success: false, error: 'blocked by compatibility' });
    expect(store.state.saveStatus).toEqual({
      type: 'error',
      message: 'blocked by compatibility',
      canForceSave: true,
    });

    const forceSaveResult = await executeSave(store, {
      action: 'force-save',
      message: 'force action required',
    });
    expect(forceSaveResult).toEqual({ success: false, error: 'force action required' });
    expect(store.state.saveStatus).toEqual({
      type: 'error',
      message: 'force action required',
      canForceSave: true,
    });
  });

  it('keeps force-save available after fix-and-save is blocked by recent usage compatibility', async () => {
    const store = createStore({
      onSaveSchema: vi.fn().mockResolvedValue({
        success: false,
        error: 'Schema incompatible with 2 of 10 recent generation requests.',
      }),
    });

    const result = await executeSave(store, {
      action: 'save-schema',
      force: false,
      examples: store.state.examples,
    });

    expect(result).toEqual({
      success: false,
      error: 'Schema incompatible with 2 of 10 recent generation requests.',
    });
    expect(store.state.saveStatus).toEqual({
      type: 'error',
      message: 'Schema incompatible with 2 of 10 recent generation requests.',
      canForceSave: true,
    });
  });

  it('keeps force-save available for recent-request validation errors from fix-and-save', async () => {
    const store = createStore({
      onSaveSchema: vi.fn().mockResolvedValue({
        success: false,
        error:
          "Validation failed (12 issues in 12 examples): recent-request:019d9692-b201-7048-be32-069870c63090 request:019d9692-b201-7048-be32-069870c63090 /field11 required property 'field11' not found [status=COMPLETED correlation=order-0003]",
      }),
    });

    const result = await executeSave(store, {
      action: 'save-schema',
      force: false,
      examples: store.state.examples,
    });

    expect(result).toEqual({
      success: false,
      error:
        "Validation failed (12 issues in 12 examples): recent-request:019d9692-b201-7048-be32-069870c63090 request:019d9692-b201-7048-be32-069870c63090 /field11 required property 'field11' not found [status=COMPLETED correlation=order-0003]",
    });
    expect(store.state.saveStatus).toEqual({
      type: 'error',
      message:
        "Validation failed (12 issues in 12 examples): recent-request:019d9692-b201-7048-be32-069870c63090 request:019d9692-b201-7048-be32-069870c63090 /field11 required property 'field11' not found [status=COMPLETED correlation=order-0003]",
      canForceSave: true,
    });
  });
});

describe('flattenCompatibilityWarnings', () => {
  it('flattens schema, migration, and recent usage issues', () => {
    const result: SchemaCompatibilityPreviewResult = {
      compatible: false,
      errors: [{ path: '$.count', message: 'count must be integer' }],
      migrations: [
        {
          exampleId: 'example-1',
          exampleName: 'Example 1',
          path: '/count',
          issue: 'TYPE_MISMATCH',
          currentValue: 'abc',
          expectedType: 'integer',
          suggestedValue: null,
          autoMigratable: false,
        },
      ],
      recentUsage: {
        available: true,
        checkedCount: 3,
        incompatibleCount: 1,
        issues: [
          {
            requestId: 'req_1234567890abcdef',
            createdAt: '2026-01-01T00:00:00Z',
            correlationKey: 'corr-123',
            status: 'FAILED',
            errors: [{ path: '$.name', message: 'name is required' }],
          },
        ],
      },
    };

    expect(flattenCompatibilityWarnings(result)).toEqual([
      { path: '$.count', message: 'count must be integer' },
      { path: '/count', message: 'TYPE_MISMATCH: expected integer' },
      {
        path: 'request:req_1234567890abcdef $.name',
        message: 'name is required [status=FAILED correlation=corr-123]',
      },
    ]);
  });

  it('adds recentUsage unavailable warning with fallback message', () => {
    const result: SchemaCompatibilityPreviewResult = {
      compatible: false,
      errors: [],
      migrations: [],
      recentUsage: {
        available: false,
        checkedCount: 0,
        incompatibleCount: 0,
        issues: [],
      },
    };

    expect(flattenCompatibilityWarnings(result)).toEqual([
      {
        path: 'recentUsage',
        message: 'Recent usage compatibility check is temporarily unavailable.',
      },
    ]);
  });
});
