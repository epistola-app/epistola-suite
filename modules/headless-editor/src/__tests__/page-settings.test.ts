import { describe, it, expect, vi } from 'vitest';
import { TemplateEditor } from '../editor';
import type { PageSettings } from '../types';

describe('Page Settings', () => {
  describe('updatePageSettings', () => {
    it('should update format', () => {
      const editor = new TemplateEditor();

      editor.updatePageSettings({ format: 'Letter' });

      expect(editor.getTemplate().pageSettings?.format).toBe('Letter');
    });

    it('should update orientation', () => {
      const editor = new TemplateEditor();

      editor.updatePageSettings({ orientation: 'landscape' });

      expect(editor.getTemplate().pageSettings?.orientation).toBe('landscape');
    });

    it('should update margins', () => {
      const editor = new TemplateEditor();
      const newMargins = { top: 30, right: 25, bottom: 30, left: 25 };

      editor.updatePageSettings({ margins: newMargins });

      expect(editor.getTemplate().pageSettings?.margins).toEqual(newMargins);
    });

    it('should partial update preserve other settings', () => {
      const editor = new TemplateEditor();

      // First set all settings
      editor.updatePageSettings({
        format: 'Letter',
        orientation: 'portrait',
        margins: { top: 30, right: 20, bottom: 30, left: 20 },
      });

      // Then update only format
      editor.updatePageSettings({ format: 'A4' });

      const settings = editor.getTemplate().pageSettings;
      expect(settings?.format).toBe('A4');
      expect(settings?.orientation).toBe('portrait');
      expect(settings?.margins).toEqual({ top: 30, right: 20, bottom: 30, left: 20 });
    });

    it('should partial margins update preserve other margin values', () => {
      const editor = new TemplateEditor();

      // Set initial margins
      editor.updatePageSettings({
        margins: { top: 30, right: 20, bottom: 30, left: 20 },
      });

      // Update only top margin - implementation handles partial margins
      editor.updatePageSettings({ margins: { top: 50 } as unknown as PageSettings['margins'] });

      const margins = editor.getTemplate().pageSettings?.margins;
      expect(margins?.top).toBe(50);
      expect(margins?.right).toBe(20);
      expect(margins?.bottom).toBe(30);
      expect(margins?.left).toBe(20);
    });

    it('should create default page settings if none exist', () => {
      const editor = new TemplateEditor();

      // Template starts without pageSettings
      expect(editor.getTemplate().pageSettings).toBeUndefined();

      editor.updatePageSettings({ format: 'Letter' });

      const settings = editor.getTemplate().pageSettings;
      expect(settings).toBeDefined();
      expect(settings?.format).toBe('Letter');
      expect(settings?.orientation).toBe('portrait');
      expect(settings?.margins).toEqual({ top: 20, right: 20, bottom: 20, left: 20 });
    });

    it('should save to history for undo', () => {
      const editor = new TemplateEditor();

      editor.updatePageSettings({ format: 'Letter' });

      expect(editor.canUndo()).toBe(true);
    });

    it('should undo revert page settings changes', () => {
      const editor = new TemplateEditor();

      // Set initial format
      editor.updatePageSettings({ format: 'A4' });

      // Change format
      editor.updatePageSettings({ format: 'Letter' });
      expect(editor.getTemplate().pageSettings?.format).toBe('Letter');

      // Undo should revert to A4
      editor.undo();
      expect(editor.getTemplate().pageSettings?.format).toBe('A4');
    });

    it('should undo revert margins changes', () => {
      const editor = new TemplateEditor();

      // Set initial margins
      editor.updatePageSettings({
        margins: { top: 20, right: 20, bottom: 20, left: 20 },
      });

      // Change margins
      editor.updatePageSettings({
        margins: { top: 50, right: 50, bottom: 50, left: 50 },
      });
      expect(editor.getTemplate().pageSettings?.margins).toEqual({
        top: 50,
        right: 50,
        bottom: 50,
        left: 50,
      });

      // Undo should revert to original margins
      editor.undo();
      expect(editor.getTemplate().pageSettings?.margins).toEqual({
        top: 20,
        right: 20,
        bottom: 20,
        left: 20,
      });
    });

    it('should allow redo after undo', () => {
      const editor = new TemplateEditor();

      editor.updatePageSettings({ format: 'Letter' });
      editor.undo();

      expect(editor.canRedo()).toBe(true);

      editor.redo();
      expect(editor.getTemplate().pageSettings?.format).toBe('Letter');
    });

    it('should notify on template change when updating page settings', () => {
      const onTemplateChange = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onTemplateChange } });

      editor.updatePageSettings({ format: 'Letter' });

      expect(onTemplateChange).toHaveBeenCalled();
    });

    it('should get page settings via getTemplate', () => {
      const editor = new TemplateEditor();
      const expectedSettings: PageSettings = {
        format: 'Letter',
        orientation: 'landscape',
        margins: { top: 30, right: 20, bottom: 30, left: 20 },
      };

      editor.updatePageSettings(expectedSettings);

      expect(editor.getTemplate().pageSettings).toEqual(expectedSettings);
    });
  });
});
