import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TemplateEditor } from '@epistola/headless-editor';
import { setEditor, getEditor } from '../mount';

describe('TextBlockController module-level editor access', () => {
  let editor: TemplateEditor;

  beforeEach(() => {
    editor = new TemplateEditor();
    setEditor(editor);
  });

  afterEach(() => {
    setEditor(null);
  });

  it('should return null when no editor is set', () => {
    setEditor(null);
    expect(getEditor()).toBeNull();
  });

  it('should return the editor instance when set', () => {
    expect(getEditor()).toBe(editor);
  });

  it('should allow replacing the editor instance', () => {
    const editor2 = new TemplateEditor();
    setEditor(editor2);
    expect(getEditor()).toBe(editor2);
  });
});
