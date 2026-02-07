import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TemplateEditor } from '@epistola/headless-editor';
import { setEditor, getEditor } from '../mount';

describe('ExpressionEditorController scope variables via core API', () => {
  let editor: TemplateEditor;

  beforeEach(() => {
    editor = new TemplateEditor();
    setEditor(editor);
  });

  afterEach(() => {
    setEditor(null);
  });

  it('should return empty scope variables for root-level block', () => {
    const block = editor.addBlock('text')!;
    const scopes = editor.getScopeVariables(block.id);
    expect(scopes).toEqual([]);
  });

  it('should return loop variable for block inside a loop', () => {
    const loop = editor.addBlock('loop')!;
    editor.updateBlock(loop.id, { itemAlias: 'order', expression: { raw: 'orders' } });
    const text = editor.addBlock('text', loop.id)!;

    const scopes = editor.getScopeVariables(text.id);
    expect(scopes.length).toBeGreaterThanOrEqual(1);
    expect(scopes.some((s) => s.name === 'order')).toBe(true);
  });

  it('should return nested loop variables for deeply nested block', () => {
    const outerLoop = editor.addBlock('loop')!;
    editor.updateBlock(outerLoop.id, { itemAlias: 'customer', expression: { raw: 'customers' } });

    const innerLoop = editor.addBlock('loop', outerLoop.id)!;
    editor.updateBlock(innerLoop.id, { itemAlias: 'order', expression: { raw: 'customer.orders' } });

    const text = editor.addBlock('text', innerLoop.id)!;

    const scopes = editor.getScopeVariables(text.id);
    expect(scopes.some((s) => s.name === 'customer')).toBe(true);
    expect(scopes.some((s) => s.name === 'order')).toBe(true);
  });

  it('should access test data from editor state', () => {
    const state = editor.getState();
    expect(state.testData).toBeDefined();
  });

  it('should get editor via getEditor()', () => {
    expect(getEditor()).toBe(editor);
  });
});
