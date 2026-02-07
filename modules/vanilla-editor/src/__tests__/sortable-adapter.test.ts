import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TemplateEditor } from '@epistola/headless-editor';
import { SortableAdapter } from '../sortable-adapter';

describe('SortableAdapter', () => {
  let container: HTMLElement;
  let editor: TemplateEditor;
  let adapter: SortableAdapter;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    editor = new TemplateEditor();
    adapter = new SortableAdapter({
      editor,
      container,
      dragDropPort: editor.getDragDropPort(),
    });
  });

  afterEach(() => {
    adapter.destroy();
    container.remove();
  });

  it('should initialize with isDragging false', () => {
    expect(adapter.getIsDragging()).toBe(false);
  });

  it('should setup without error on empty container', () => {
    expect(() => adapter.setup()).not.toThrow();
  });

  it('should setup without error on container with blocks', () => {
    editor.addBlock('text');
    // Simulate rendered DOM with a block element
    const blockEl = document.createElement('div');
    blockEl.dataset.blockId = 'block-1';
    blockEl.dataset.blockType = 'text';
    container.appendChild(blockEl);

    expect(() => adapter.setup()).not.toThrow();
  });

  it('should destroy all instances', () => {
    editor.addBlock('text');
    const blockEl = document.createElement('div');
    blockEl.dataset.blockId = 'block-1';
    container.appendChild(blockEl);

    adapter.setup();
    adapter.destroy();
    // Calling destroy again should be safe
    expect(() => adapter.destroy()).not.toThrow();
  });

  it('should setup nested sortable containers', () => {
    const blockEl = document.createElement('div');
    blockEl.dataset.blockId = 'block-1';
    container.appendChild(blockEl);

    const nestedContainer = document.createElement('div');
    nestedContainer.classList.add('sortable-container');
    nestedContainer.dataset.parentId = 'block-1';
    blockEl.appendChild(nestedContainer);

    expect(() => adapter.setup()).not.toThrow();
  });

  it('should re-setup after destroy', () => {
    const blockEl = document.createElement('div');
    blockEl.dataset.blockId = 'block-1';
    container.appendChild(blockEl);

    adapter.setup();
    adapter.destroy();
    expect(() => adapter.setup()).not.toThrow();
  });

  it('should accept invalid drop callback', () => {
    const callback = vi.fn();
    adapter.setInvalidDropCallback(callback);
    // Callback is stored; we verify it doesn't throw
    expect(() => adapter.setInvalidDropCallback(callback)).not.toThrow();
  });
});
