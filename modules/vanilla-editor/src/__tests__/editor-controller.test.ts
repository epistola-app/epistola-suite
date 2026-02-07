import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TemplateEditor } from '@epistola/headless-editor';
import { setEditor, getEditor, getMountConfig } from '../mount';

describe('EditorController actions via getEditor()', () => {
  let editor: TemplateEditor;

  beforeEach(() => {
    editor = new TemplateEditor();
    setEditor(editor);
  });

  afterEach(() => {
    setEditor(null);
  });

  it('should add a block via getEditor()', () => {
    const e = getEditor()!;
    e.addBlock('text');
    expect(e.getState().template.blocks.length).toBe(1);
  });

  it('should add a block at root level (no parent)', () => {
    const e = getEditor()!;
    e.addBlock('text');
    e.addBlock('container');
    expect(e.getState().template.blocks.length).toBe(2);
    expect(e.getState().template.blocks[0].type).toBe('text');
    expect(e.getState().template.blocks[1].type).toBe('container');
  });

  it('should add a text block to a selected container', () => {
    const e = getEditor()!;
    const container = e.addBlock('container')!;
    e.selectBlock(container.id);
    e.addBlock('text', container.id);

    const state = e.getState();
    const containerBlock = state.template.blocks[0];
    expect(containerBlock.type).toBe('container');
    expect((containerBlock as { children: unknown[] }).children.length).toBe(1);
  });

  it('should undo and redo via getEditor()', () => {
    const e = getEditor()!;
    e.addBlock('text');
    expect(e.getState().template.blocks.length).toBe(1);

    e.undo();
    expect(e.getState().template.blocks.length).toBe(0);

    e.redo();
    expect(e.getState().template.blocks.length).toBe(1);
  });

  it('should delete selected block', () => {
    const e = getEditor()!;
    const block = e.addBlock('text')!;
    e.selectBlock(block.id);
    expect(e.getState().selectedBlockId).toBe(block.id);

    e.deleteBlock(block.id);
    expect(e.getState().template.blocks.length).toBe(0);
  });

  it('should clear selection', () => {
    const e = getEditor()!;
    const block = e.addBlock('text')!;
    e.selectBlock(block.id);
    expect(e.getState().selectedBlockId).toBe(block.id);

    e.selectBlock(null);
    expect(e.getState().selectedBlockId).toBeNull();
  });

  it('should track dirty state via $isDirty', () => {
    const stores = editor.getStores();
    expect(stores.$isDirty.get()).toBe(false);

    editor.addBlock('text');
    expect(stores.$isDirty.get()).toBe(true);
  });

  it('should track canUndo/canRedo via stores', () => {
    const stores = editor.getStores();
    expect(stores.$canUndo.get()).toBe(false);
    expect(stores.$canRedo.get()).toBe(false);

    editor.addBlock('text');
    expect(stores.$canUndo.get()).toBe(true);

    editor.undo();
    expect(stores.$canRedo.get()).toBe(true);
  });

  it('should export and import JSON', () => {
    editor.addBlock('text');
    const json = editor.exportJSON();
    expect(json).toBeTruthy();

    const editor2 = new TemplateEditor();
    editor2.importJSON(json);
    expect(editor2.getState().template.blocks.length).toBe(1);
  });

  it('should mark as saved', () => {
    editor.addBlock('text');
    const stores = editor.getStores();
    expect(stores.$isDirty.get()).toBe(true);

    editor.markAsSaved();
    expect(stores.$isDirty.get()).toBe(false);
  });
});

describe('getMountConfig()', () => {
  it('should return null when no editor is mounted', () => {
    expect(getMountConfig()).toBeNull();
  });
});
