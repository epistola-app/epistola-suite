import { describe, it, expect, vi } from 'vitest';
import { TemplateEditor } from '../editor';
import { DEFAULT_TEST_DATA } from '../types';
import type { DataExample, JsonObject } from '../types';

describe('Data Examples', () => {
  describe('initial state', () => {
    it('should have empty data examples array', () => {
      const editor = new TemplateEditor();
      const state = editor.getState();

      expect(state.dataExamples).toEqual([]);
      expect(state.selectedDataExampleId).toBeNull();
    });

    it('should have default test data', () => {
      const editor = new TemplateEditor();
      const state = editor.getState();

      expect(state.testData).toEqual(DEFAULT_TEST_DATA);
    });
  });

  describe('setDataExamples', () => {
    it('should set data examples', () => {
      const editor = new TemplateEditor();
      const examples: DataExample[] = [
        { id: 'ex-1', name: 'Example 1', data: { value: 1 } },
        { id: 'ex-2', name: 'Example 2', data: { value: 2 } },
      ];

      editor.setDataExamples(examples);

      expect(editor.getDataExamples()).toEqual(examples);
    });

    it('should auto-select first example when no selection exists', () => {
      const editor = new TemplateEditor();
      const examples: DataExample[] = [
        { id: 'ex-1', name: 'Example 1', data: { value: 1 } },
        { id: 'ex-2', name: 'Example 2', data: { value: 2 } },
      ];

      editor.setDataExamples(examples);

      expect(editor.getSelectedDataExampleId()).toBe('ex-1');
      expect(editor.getTestData()).toEqual({ value: 1 });
    });

    it('should not change selection when already selected', () => {
      const editor = new TemplateEditor();
      const examples1: DataExample[] = [
        { id: 'ex-1', name: 'Example 1', data: { value: 1 } },
      ];
      const examples2: DataExample[] = [
        { id: 'ex-2', name: 'Example 2', data: { value: 2 } },
        { id: 'ex-3', name: 'Example 3', data: { value: 3 } },
      ];

      editor.setDataExamples(examples1);
      expect(editor.getSelectedDataExampleId()).toBe('ex-1');

      editor.setDataExamples(examples2);
      // Selection should remain as first example since we had a selection
      expect(editor.getSelectedDataExampleId()).toBe('ex-1');
    });

    it('should clear selection and restore defaults when setting empty array', () => {
      const editor = new TemplateEditor();
      const examples: DataExample[] = [
        { id: 'ex-1', name: 'Example 1', data: { value: 1 } },
      ];

      editor.setDataExamples(examples);
      expect(editor.getSelectedDataExampleId()).toBe('ex-1');

      editor.setDataExamples([]);
      expect(editor.getSelectedDataExampleId()).toBeNull();
      expect(editor.getTestData()).toEqual(DEFAULT_TEST_DATA);
    });
  });

  describe('addDataExample', () => {
    it('should add a single example to empty list', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.addDataExample(example);

      expect(editor.getDataExamples()).toHaveLength(1);
      expect(editor.getDataExamples()[0]).toEqual(example);
    });

    it('should append example to existing list', () => {
      const editor = new TemplateEditor();
      const example1: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };
      const example2: DataExample = { id: 'ex-2', name: 'Example 2', data: { value: 2 } };

      editor.setDataExamples([example1]);
      editor.addDataExample(example2);

      expect(editor.getDataExamples()).toHaveLength(2);
      expect(editor.getDataExamples()[1]).toEqual(example2);
    });
  });

  describe('updateDataExample', () => {
    it('should update example name', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);
      editor.updateDataExample('ex-1', { name: 'Updated Name' });

      expect(editor.getDataExamples()[0]?.name).toBe('Updated Name');
      expect(editor.getDataExamples()[0]?.data).toEqual({ value: 1 });
    });

    it('should update example data', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);
      editor.updateDataExample('ex-1', { data: { value: 100 } });

      expect(editor.getDataExamples()[0]?.data).toEqual({ value: 100 });
    });

    it('should sync testData when updating selected example data', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);
      expect(editor.getTestData()).toEqual({ value: 1 });

      editor.updateDataExample('ex-1', { data: { value: 999 } });

      expect(editor.getTestData()).toEqual({ value: 999 });
    });

    it('should not sync testData when updating non-selected example', () => {
      const editor = new TemplateEditor();
      const example1: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };
      const example2: DataExample = { id: 'ex-2', name: 'Example 2', data: { value: 2 } };

      editor.setDataExamples([example1, example2]);
      expect(editor.getTestData()).toEqual({ value: 1 });

      editor.updateDataExample('ex-2', { data: { value: 999 } });

      expect(editor.getTestData()).toEqual({ value: 1 });
    });

    it('should not affect other examples', () => {
      const editor = new TemplateEditor();
      const example1: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };
      const example2: DataExample = { id: 'ex-2', name: 'Example 2', data: { value: 2 } };

      editor.setDataExamples([example1, example2]);
      editor.updateDataExample('ex-1', { name: 'Updated' });

      expect(editor.getDataExamples()[1]).toEqual(example2);
    });
  });

  describe('deleteDataExample', () => {
    it('should delete example by id', () => {
      const editor = new TemplateEditor();
      const example1: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };
      const example2: DataExample = { id: 'ex-2', name: 'Example 2', data: { value: 2 } };

      editor.setDataExamples([example1, example2]);
      editor.deleteDataExample('ex-1');

      expect(editor.getDataExamples()).toHaveLength(1);
      expect(editor.getDataExamples()[0]?.id).toBe('ex-2');
    });

    it('should select first remaining example when deleting selected example', () => {
      const editor = new TemplateEditor();
      const example1: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };
      const example2: DataExample = { id: 'ex-2', name: 'Example 2', data: { value: 2 } };

      editor.setDataExamples([example1, example2]);
      expect(editor.getSelectedDataExampleId()).toBe('ex-1');

      editor.deleteDataExample('ex-1');

      expect(editor.getSelectedDataExampleId()).toBe('ex-2');
      expect(editor.getTestData()).toEqual({ value: 2 });
    });

    it('should clear selection and restore defaults when deleting last example', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);
      expect(editor.getSelectedDataExampleId()).toBe('ex-1');

      editor.deleteDataExample('ex-1');

      expect(editor.getSelectedDataExampleId()).toBeNull();
      expect(editor.getTestData()).toEqual(DEFAULT_TEST_DATA);
    });

    it('should not change selection when deleting non-selected example', () => {
      const editor = new TemplateEditor();
      const example1: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };
      const example2: DataExample = { id: 'ex-2', name: 'Example 2', data: { value: 2 } };

      editor.setDataExamples([example1, example2]);
      expect(editor.getSelectedDataExampleId()).toBe('ex-1');

      editor.deleteDataExample('ex-2');

      expect(editor.getSelectedDataExampleId()).toBe('ex-1');
      expect(editor.getTestData()).toEqual({ value: 1 });
    });
  });

  describe('selectDataExample', () => {
    it('should select an example by id', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);
      editor.selectDataExample('ex-1');

      expect(editor.getSelectedDataExampleId()).toBe('ex-1');
    });

    it('should copy example data to testData when selecting', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { nested: { value: 42 } } };

      editor.setDataExamples([example]);
      editor.selectDataExample('ex-1');

      expect(editor.getTestData()).toEqual({ nested: { value: 42 } });
    });

    it('should deep copy data to avoid mutation issues', () => {
      const editor = new TemplateEditor();
      const data: JsonObject = { nested: { value: 42 } };
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data };

      editor.setDataExamples([example]);
      editor.selectDataExample('ex-1');

      // Modify the original data
      (data.nested as JsonObject).value = 999;

      // TestData should not be affected
      expect(editor.getTestData()).toEqual({ nested: { value: 42 } });
    });

    it('should restore default testData when deselecting (null)', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);
      expect(editor.getTestData()).toEqual({ value: 1 });

      editor.selectDataExample(null);

      expect(editor.getSelectedDataExampleId()).toBeNull();
      expect(editor.getTestData()).toEqual(DEFAULT_TEST_DATA);
    });

    it('should handle selecting non-existent id gracefully', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);
      editor.selectDataExample('ex-1');

      // Select non-existent id - should set null but keep current testData
      // Actually, looking at the implementation, it will set the ID to null
      // and then try to find the example. Since it won't find it, testData stays as is.
      // But the id will be set to the non-existent value briefly.
      // Let me check the implementation...

      // Implementation sets the ID first, then tries to find the example
      // So it will set ID to 'non-existent' but won't update testData
      // Actually, looking more closely: if id is not null but example is not found,
      // testData is not updated at all. So it keeps the previous value.
      editor.selectDataExample('non-existent');

      expect(editor.getSelectedDataExampleId()).toBe('non-existent');
      // testData remains unchanged since example wasn't found
      expect(editor.getTestData()).toEqual({ value: 1 });
    });
  });

  describe('getters', () => {
    it('getDataExamples should return current examples', () => {
      const editor = new TemplateEditor();
      const examples: DataExample[] = [
        { id: 'ex-1', name: 'Example 1', data: { value: 1 } },
      ];

      editor.setDataExamples(examples);

      expect(editor.getDataExamples()).toEqual(examples);
    });

    it('getSelectedDataExampleId should return current selection', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);

      expect(editor.getSelectedDataExampleId()).toBe('ex-1');
    });

    it('getTestData should return current test data', () => {
      const editor = new TemplateEditor();

      expect(editor.getTestData()).toEqual(DEFAULT_TEST_DATA);
    });
  });

  describe('subscriptions', () => {
    it('should subscribe to data examples changes', () => {
      const editor = new TemplateEditor();
      const values: DataExample[][] = [];

      editor.getStores().$dataExamples.subscribe((value) => {
        values.push([...value]);
      });

      const examples: DataExample[] = [{ id: 'ex-1', name: 'Example 1', data: {} }];
      editor.setDataExamples(examples);

      // nanostores calls immediately with initial value ([]), then with new value
      expect(values.length).toBe(2);
      expect(values[0]).toEqual([]);
      expect(values[1]).toEqual(examples);
    });

    it('should subscribe to selected data example id changes', () => {
      const editor = new TemplateEditor();
      const values: (string | null)[] = [];

      editor.getStores().$selectedDataExampleId.subscribe((value) => {
        values.push(value);
      });

      const examples: DataExample[] = [{ id: 'ex-1', name: 'Example 1', data: {} }];
      editor.setDataExamples(examples);

      // nanostores calls immediately with initial value (null), then with new value
      expect(values.length).toBe(2);
      expect(values[0]).toBeNull();
      expect(values[1]).toBe('ex-1');
    });

    it('should subscribe to test data changes', () => {
      const editor = new TemplateEditor();
      const values: JsonObject[] = [];

      editor.getStores().$testData.subscribe((value) => {
        values.push(value);
      });

      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };
      editor.setDataExamples([example]);

      // nanostores calls immediately with initial value (DEFAULT_TEST_DATA), then with new value
      expect(values.length).toBe(2);
      expect(values[0]).toEqual(DEFAULT_TEST_DATA);
      expect(values[1]).toEqual({ value: 1 });
    });

    it('should unsubscribe correctly', () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();

      const unsubscribe = editor.getStores().$dataExamples.subscribe(callback);
      unsubscribe();

      editor.setDataExamples([{ id: 'ex-1', name: 'Example 1', data: {} }]);

      // Callback should not be called after unsubscribe
      // Note: nanostores may call once immediately on subscribe, but not after unsubscribe
      const callCountAfterUnsubscribe = callback.mock.calls.length;

      editor.setDataExamples([{ id: 'ex-2', name: 'Example 2', data: {} }]);

      expect(callback.mock.calls.length).toBe(callCountAfterUnsubscribe);
    });
  });

  describe('state integration', () => {
    it('getState should include data examples state', () => {
      const editor = new TemplateEditor();
      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };

      editor.setDataExamples([example]);

      const state = editor.getState();
      expect(state.dataExamples).toEqual([example]);
      expect(state.selectedDataExampleId).toBe('ex-1');
      expect(state.testData).toEqual({ value: 1 });
    });

    it('subscribe should include data examples in state updates', () => {
      const editor = new TemplateEditor();
      const states: ReturnType<typeof editor.getState>[] = [];

      editor.subscribe((state) => {
        states.push(state);
      });

      const example: DataExample = { id: 'ex-1', name: 'Example 1', data: { value: 1 } };
      editor.setDataExamples([example]);

      // Check the last captured state - it should have all updates
      const lastState = states[states.length - 1];
      expect(lastState).toBeDefined();
      expect(lastState.dataExamples).toEqual([example]);
      expect(lastState.selectedDataExampleId).toBe('ex-1');
      expect(lastState.testData).toEqual({ value: 1 });
    });
  });
});
