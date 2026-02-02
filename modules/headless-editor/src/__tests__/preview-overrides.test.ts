import { describe, it, expect, vi } from 'vitest';
import { TemplateEditor } from '../editor';
import { DEFAULT_PREVIEW_OVERRIDES } from '../types';
import type { PreviewOverrides } from '../types';

describe('Preview Overrides', () => {
  describe('initial state', () => {
    it('should have empty preview overrides by default', () => {
      const editor = new TemplateEditor();
      const overrides = editor.getPreviewOverrides();

      expect(overrides).toEqual(DEFAULT_PREVIEW_OVERRIDES);
      expect(overrides.conditionals).toEqual({});
      expect(overrides.loops).toEqual({});
    });
  });

  describe('setPreviewOverride', () => {
    it('should set conditional override to show', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'show');

      const overrides = editor.getPreviewOverrides();
      expect(overrides.conditionals['block-1']).toBe('show');
    });

    it('should set conditional override to hide', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'hide');

      const overrides = editor.getPreviewOverrides();
      expect(overrides.conditionals['block-1']).toBe('hide');
    });

    it('should set conditional override to data', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'data');

      const overrides = editor.getPreviewOverrides();
      expect(overrides.conditionals['block-1']).toBe('data');
    });

    it('should set loop override to a number', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('loops', 'block-2', 3);

      const overrides = editor.getPreviewOverrides();
      expect(overrides.loops['block-2']).toBe(3);
    });

    it('should set loop override to data', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('loops', 'block-2', 'data');

      const overrides = editor.getPreviewOverrides();
      expect(overrides.loops['block-2']).toBe('data');
    });

    it('should update existing override', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'show');
      editor.setPreviewOverride('conditionals', 'block-1', 'hide');

      const overrides = editor.getPreviewOverrides();
      expect(overrides.conditionals['block-1']).toBe('hide');
    });

    it('should preserve other overrides when setting one', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'show');
      editor.setPreviewOverride('conditionals', 'block-2', 'hide');
      editor.setPreviewOverride('loops', 'block-3', 5);

      const overrides = editor.getPreviewOverrides();
      expect(overrides.conditionals['block-1']).toBe('show');
      expect(overrides.conditionals['block-2']).toBe('hide');
      expect(overrides.loops['block-3']).toBe(5);
    });
  });

  describe('getPreviewOverrides', () => {
    it('should return current overrides', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'show');

      const overrides = editor.getPreviewOverrides();
      expect(overrides.conditionals).toHaveProperty('block-1');
      expect(overrides.conditionals['block-1']).toBe('show');
    });

    it('should return a copy of overrides (immutable)', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'show');
      const overrides = editor.getPreviewOverrides();

      // Modify the returned object
      overrides.conditionals['block-1'] = 'hide';

      // Original should be unchanged
      const freshOverrides = editor.getPreviewOverrides();
      expect(freshOverrides.conditionals['block-1']).toBe('show');
    });
  });

  describe('clearPreviewOverrides', () => {
    it('should clear all conditional overrides', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'show');
      editor.setPreviewOverride('conditionals', 'block-2', 'hide');
      editor.clearPreviewOverrides();

      const overrides = editor.getPreviewOverrides();
      expect(overrides.conditionals).toEqual({});
    });

    it('should clear all loop overrides', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('loops', 'block-1', 3);
      editor.setPreviewOverride('loops', 'block-2', 5);
      editor.clearPreviewOverrides();

      const overrides = editor.getPreviewOverrides();
      expect(overrides.loops).toEqual({});
    });

    it('should reset to default empty state', () => {
      const editor = new TemplateEditor();

      editor.setPreviewOverride('conditionals', 'block-1', 'show');
      editor.setPreviewOverride('loops', 'block-2', 3);
      editor.clearPreviewOverrides();

      const overrides = editor.getPreviewOverrides();
      expect(overrides).toEqual(DEFAULT_PREVIEW_OVERRIDES);
      expect(overrides.conditionals).toEqual({});
      expect(overrides.loops).toEqual({});
    });
  });

  describe('subscriptions', () => {
    it('should subscribe to preview overrides changes', () => {
      const editor = new TemplateEditor();
      const values: PreviewOverrides[] = [];

      editor.getStores().$previewOverrides.subscribe((value) => {
        values.push(value);
      });

      editor.setPreviewOverride('conditionals', 'block-1', 'show');

      // nanostores calls immediately with initial value, then with new value
      expect(values.length).toBeGreaterThanOrEqual(2);
      expect(values[0]).toEqual(DEFAULT_PREVIEW_OVERRIDES);
      expect(values[values.length - 1]?.conditionals['block-1']).toBe('show');
    });

    it('should be called when setting loop override', () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();

      const unsubscribe = editor.getStores().$previewOverrides.subscribe(callback);

      // Reset mock to ignore initial call
      callback.mockClear();

      editor.setPreviewOverride('loops', 'block-1', 5);

      expect(callback).toHaveBeenCalled();
      const lastCall = callback.mock.calls[callback.mock.calls.length - 1];
      expect(lastCall?.[0]?.loops).toHaveProperty('block-1', 5);

      unsubscribe();
    });

    it('should be called when clearing overrides', () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();

      editor.getStores().$previewOverrides.subscribe(callback);

      // Reset mock to ignore initial call
      callback.mockClear();

      editor.setPreviewOverride('conditionals', 'block-1', 'show');
      const callsBeforeClear = callback.mock.calls.length;

      editor.clearPreviewOverrides();

      expect(callback.mock.calls.length).toBeGreaterThan(callsBeforeClear);
      const lastCall = callback.mock.calls[callback.mock.calls.length - 1];
      expect(lastCall?.[0]).toEqual(DEFAULT_PREVIEW_OVERRIDES);
    });

    it('should unsubscribe correctly', () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();

      const unsubscribe = editor.getStores().$previewOverrides.subscribe(callback);
      unsubscribe();

      const callsBefore = callback.mock.calls.length;

      editor.setPreviewOverride('conditionals', 'block-1', 'show');

      expect(callback.mock.calls.length).toBe(callsBefore);
    });
  });

  describe('store integration', () => {
    it('getStores should include $previewOverrides', () => {
      const editor = new TemplateEditor();
      const stores = editor.getStores();

      expect(stores.$previewOverrides).toBeDefined();
    });

    it('should be able to set bulk overrides via store', () => {
      const editor = new TemplateEditor();

      editor.getStores().$previewOverrides.set({
        conditionals: { 'block-1': 'show', 'block-2': 'hide' },
        loops: { 'block-3': 5, 'block-4': 'data' },
      });

      const overrides = editor.getPreviewOverrides();
      expect(overrides.conditionals).toEqual({ 'block-1': 'show', 'block-2': 'hide' });
      expect(overrides.loops).toEqual({ 'block-3': 5, 'block-4': 'data' });
    });
  });
});
