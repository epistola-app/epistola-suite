import { describe, expect, it, vi } from 'vitest';
import { DataContractStore } from './DataContractStore.js';
import { executeSave, orchestrateSave } from './SaveOrchestrator.js';
import type { JsonSchema } from './types.js';

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
});
