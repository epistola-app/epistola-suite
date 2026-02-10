import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TemplateEditor } from '@epistola/headless-editor';
import { BlockRenderer, getBadgeClass, getBadgeStyle, getBlockIcon } from '../renderer';

describe('BlockRenderer', () => {
  let container: HTMLElement;
  let editor: TemplateEditor;
  let renderer: BlockRenderer;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    editor = new TemplateEditor();
    renderer = new BlockRenderer({ container, editor });
  });

  afterEach(() => {
    renderer.destroy();
    container.remove();
  });

  describe('empty state', () => {
    it('should render empty state message when no blocks exist', () => {
      renderer.render();
      expect(container.textContent).toContain('No blocks yet');
    });
  });

  describe('block rendering', () => {
    it('should render a text block', () => {
      editor.addBlock('text');
      renderer.render();

      const blockEl = container.querySelector('[data-block-type="text"]');
      expect(blockEl).not.toBeNull();
      expect(blockEl?.getAttribute('data-block-id')).toBeTruthy();
    });

    it('should render a container block with empty state', () => {
      editor.addBlock('container');
      renderer.render();

      const blockEl = container.querySelector('[data-block-type="container"]');
      expect(blockEl).not.toBeNull();
      expect(container.textContent).toContain('Drop blocks here');
    });

    it('should render multiple blocks', () => {
      editor.addBlock('text');
      editor.addBlock('container');
      editor.addBlock('loop');
      renderer.render();

      const blocks = container.querySelectorAll('[data-block-id]');
      expect(blocks.length).toBe(3);
    });

    it('should render block header with badge and delete button', () => {
      editor.addBlock('text');
      renderer.render();

      const badge = container.querySelector('.badge');
      expect(badge).not.toBeNull();
      expect(badge?.textContent).toContain('text');

      const deleteBtn = container.querySelector('.btn-outline-danger');
      expect(deleteBtn).not.toBeNull();
    });

    it('should mark selected block with "selected" class', () => {
      const block = editor.addBlock('text')!;
      editor.selectBlock(block.id);
      renderer.render();

      const blockEl = container.querySelector(`[data-block-id="${block.id}"]`);
      expect(blockEl?.classList.contains('selected')).toBe(true);
    });

    it('applies inherited document styles to rendered block content', () => {
      const block = editor.addBlock('text')!;
      editor.updateDocumentStyles({
        fontFamily: 'Georgia, serif',
        color: '#222222',
        backgroundColor: '#f2f2f2',
      });

      renderer.render();

      const contentEl = container.querySelector(
        `[data-block-id="${block.id}"] .block-content`,
      ) as HTMLElement;

      expect(contentEl.getAttribute('style')).toContain('font-family: Georgia, serif;');
      expect(contentEl.style.color).toBe('rgb(34, 34, 34)');
      expect(contentEl.style.backgroundColor).toBe('rgb(242, 242, 242)');
    });

    it('applies block style overrides over document styles', () => {
      const block = editor.addBlock('text')!;
      editor.updateDocumentStyles({ color: '#444444', fontSize: '14px' });
      editor.updateBlock(block.id, {
        styles: { color: '#111111', paddingTop: '8px' },
      });

      renderer.render();

      const contentEl = container.querySelector(
        `[data-block-id="${block.id}"] .block-content`,
      ) as HTMLElement;
      const editorEl = container.querySelector(
        `[data-block-id="${block.id}"] .text-block-editor`,
      ) as HTMLElement;

      expect(contentEl.style.color).toBe('rgb(17, 17, 17)');
      expect(contentEl.getAttribute('style')).toContain('font-size: 14px;');
      expect(contentEl.getAttribute('style')).toContain('padding-top: 8px;');
      expect(editorEl.style.color).toBe('rgb(17, 17, 17)');
      expect(editorEl.getAttribute('style')).toContain('font-size: 14px;');
    });
  });

  describe('block selection via click', () => {
    it('should select block when clicked', () => {
      const block = editor.addBlock('text')!;
      renderer.render();

      const blockEl = container.querySelector(`[data-block-id="${block.id}"]`) as HTMLElement;
      blockEl.click();

      expect(editor.getState().selectedBlockId).toBe(block.id);
    });
  });

  describe('re-render', () => {
    it('should update DOM when rendered again after state change', () => {
      renderer.render();
      expect(container.textContent).toContain('No blocks yet');

      editor.addBlock('text');
      renderer.render();

      expect(container.textContent).not.toContain('No blocks yet');
      expect(container.querySelector('[data-block-type="text"]')).not.toBeNull();
    });

    it('should handle incremental block additions with re-renders between each', () => {
      renderer.render();
      expect(container.textContent).toContain('No blocks yet');

      editor.addBlock('text');
      renderer.render();
      expect(container.querySelectorAll('[data-block-type]').length).toBe(1);

      editor.addBlock('text');
      renderer.render();
      expect(container.querySelectorAll('[data-block-type]').length).toBe(2);

      editor.addBlock('text');
      renderer.render();
      expect(container.querySelectorAll('[data-block-type]').length).toBe(3);

      editor.addBlock('container');
      renderer.render();
      expect(container.querySelectorAll('[data-block-type]').length).toBe(4);

      editor.addBlock('text');
      renderer.render();
      expect(container.querySelectorAll('[data-block-type]').length).toBe(5);
    });

    it('should handle adding mixed block types incrementally', () => {
      editor.addBlock('text');
      renderer.render();

      editor.addBlock('conditional');
      renderer.render();

      editor.addBlock('loop');
      renderer.render();

      editor.addBlock('columns');
      renderer.render();

      editor.addBlock('pagebreak');
      renderer.render();

      const blocks = container.querySelectorAll('[data-block-type]');
      expect(blocks.length).toBe(5);
      expect(blocks[0].getAttribute('data-block-type')).toBe('text');
      expect(blocks[1].getAttribute('data-block-type')).toBe('conditional');
      expect(blocks[2].getAttribute('data-block-type')).toBe('loop');
      expect(blocks[3].getAttribute('data-block-type')).toBe('columns');
      expect(blocks[4].getAttribute('data-block-type')).toBe('pagebreak');
    });
  });

  describe('destroy', () => {
    it('should empty the container', () => {
      editor.addBlock('text');
      renderer.render();
      expect(container.children.length).toBeGreaterThan(0);

      renderer.destroy();
      expect(container.children.length).toBe(0);
    });
  });

  describe('page break block rendering', () => {
    it('should render a pagebreak block', () => {
      editor.addBlock('pagebreak');
      renderer.render();

      const blockEl = container.querySelector('[data-block-type="pagebreak"]');
      expect(blockEl).not.toBeNull();
      expect(container.textContent).toContain('Page Break');
    });
  });

  describe('nested blocks', () => {
    it('should render children inside a container block', () => {
      const containerBlock = editor.addBlock('container')!;
      const textBlock = editor.addBlock('text', containerBlock.id)!;
      renderer.render();

      // The child text block should exist somewhere in the DOM
      const nestedText = container.querySelector(`[data-block-id="${textBlock.id}"]`);
      expect(nestedText).not.toBeNull();
      // It should be within the container block's subtree
      const containerEl = container.querySelector(`[data-block-id="${containerBlock.id}"]`);
      expect(containerEl?.contains(nestedText!)).toBe(true);
    });
  });
});

describe('badge/icon utilities', () => {
  it('getBadgeClass returns correct class for known types', () => {
    expect(getBadgeClass('text')).toBe('bg-secondary');
    expect(getBadgeClass('conditional')).toBe('bg-warning');
    expect(getBadgeClass('loop')).toBe('bg-info');
  });

  it('getBadgeClass returns default for unknown type', () => {
    expect(getBadgeClass('unknown')).toBe('bg-secondary');
  });

  it('getBadgeStyle returns purple override for columns', () => {
    expect(getBadgeStyle('columns')).toContain('#6f42c1');
  });

  it('getBadgeStyle returns empty for other types', () => {
    expect(getBadgeStyle('text')).toBe('');
  });

  it('getBlockIcon returns correct icons', () => {
    expect(getBlockIcon('text')).toBe('bi-type');
    expect(getBlockIcon('table')).toBe('bi-table');
  });

  it('getBlockIcon returns default for unknown type', () => {
    expect(getBlockIcon('unknown')).toBe('bi-square');
  });
});
