import { describe, it, expect, vi } from 'vitest';
import { TemplateEditor } from './editor';
import type { Template, Block } from './types';

describe('TemplateEditor', () => {
  describe('initialization', () => {
    it('should create with default template', () => {
      const editor = new TemplateEditor();
      const state = editor.getState();

      expect(state.template.blocks).toEqual([]);
      expect(state.selectedBlockId).toBeNull();
    });

    it('should create with provided template', () => {
      const template: Template = {
        id: 'custom-1',
        name: 'Custom Template',
        blocks: [{ id: 'block-1', type: 'text', content: 'Hello' }],
      };
      const editor = new TemplateEditor({ template });

      expect(editor.getTemplate()).toEqual(template);
    });
  });

  describe('addBlock', () => {
    it('should add a text block at root', () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock('text');

      expect(block).not.toBeNull();
      expect(block?.type).toBe('text');
      expect(editor.getTemplate().blocks).toHaveLength(1);
    });

    it('should add block to container', () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock('container')!;
      const child = editor.addBlock('text', container.id);

      expect(child).not.toBeNull();
      const updated = editor.findBlock(container.id) as { children: Block[] };
      expect(updated.children).toHaveLength(1);
    });

    it('should reject adding to non-container', () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const text = editor.addBlock('text')!;

      const result = editor.addBlock('text', text.id);

      expect(result).toBeNull();
      expect(onError).toHaveBeenCalled();
    });

    it('should reject unknown block type', () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });

      const result = editor.addBlock('unknown');

      expect(result).toBeNull();
      expect(onError).toHaveBeenCalled();
    });

    it('should respect pagebreak root-only constraint', () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const container = editor.addBlock('container')!;

      const result = editor.addBlock('pagebreak', container.id);

      expect(result).toBeNull();
      expect(onError).toHaveBeenCalled();
    });

    it('should allow pagebreak at root', () => {
      const editor = new TemplateEditor();
      const pagebreak = editor.addBlock('pagebreak');

      expect(pagebreak).not.toBeNull();
      expect(pagebreak?.type).toBe('pagebreak');
    });
  });

  describe('updateBlock', () => {
    it('should update block properties', () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock('text')!;

      editor.updateBlock(block.id, { content: 'Updated' });

      const updated = editor.findBlock(block.id) as { content: string };
      expect(updated.content).toBe('Updated');
    });
  });

  describe('deleteBlock', () => {
    it('should delete a block', () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock('text')!;

      const result = editor.deleteBlock(block.id);

      expect(result).toBe(true);
      expect(editor.getTemplate().blocks).toHaveLength(0);
    });

    it('should clear selection when deleted block was selected', () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock('text')!;
      editor.selectBlock(block.id);

      editor.deleteBlock(block.id);

      expect(editor.getState().selectedBlockId).toBeNull();
    });
  });

  describe('moveBlock', () => {
    it('should move block to new position', () => {
      const editor = new TemplateEditor();
      editor.addBlock('text'); // block-1
      editor.addBlock('text'); // block-2

      const blocks = editor.getTemplate().blocks;
      const block1Id = blocks[0].id;
      const block2Id = blocks[1].id;

      editor.moveBlock(block2Id, null, 0);

      const reordered = editor.getTemplate().blocks;
      expect(reordered[0].id).toBe(block2Id);
      expect(reordered[1].id).toBe(block1Id);
    });
  });

  describe('selection', () => {
    it('should select a block', () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock('text')!;

      editor.selectBlock(block.id);

      expect(editor.getState().selectedBlockId).toBe(block.id);
      expect(editor.getSelectedBlock()).toEqual(block);
    });

    it('should clear selection', () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock('text')!;
      editor.selectBlock(block.id);

      editor.selectBlock(null);

      expect(editor.getState().selectedBlockId).toBeNull();
    });
  });

  describe('undo/redo', () => {
    it('should undo block addition', () => {
      const editor = new TemplateEditor();
      editor.addBlock('text');

      expect(editor.canUndo()).toBe(true);

      editor.undo();

      expect(editor.getTemplate().blocks).toHaveLength(0);
    });

    it('should redo after undo', () => {
      const editor = new TemplateEditor();
      editor.addBlock('text');
      editor.undo();

      expect(editor.canRedo()).toBe(true);

      editor.redo();

      expect(editor.getTemplate().blocks).toHaveLength(1);
    });

    it('should not undo when no history', () => {
      const editor = new TemplateEditor();

      const result = editor.undo();

      expect(result).toBe(false);
    });
  });

  describe('subscribe', () => {
    it('should notify on template change', () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();
      editor.subscribe(callback);

      editor.addBlock('text');

      expect(callback).toHaveBeenCalled();
    });

    it('should notify on selection change', () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock('text')!;
      const callback = vi.fn();
      editor.subscribe(callback);

      editor.selectBlock(block.id);

      expect(callback).toHaveBeenCalled();
    });

    it('should unsubscribe correctly', () => {
      const editor = new TemplateEditor();
      const callback = vi.fn();
      const unsubscribe = editor.subscribe(callback);

      unsubscribe();
      editor.addBlock('text');

      // Only called once from the addBlock before unsubscribe
      // Actually callback is called during subscribe setup, so check timing
      const callCountAfterUnsubscribe = callback.mock.calls.length;
      editor.addBlock('text');

      expect(callback.mock.calls.length).toBe(callCountAfterUnsubscribe);
    });
  });

  describe('validation', () => {
    it('should validate template', () => {
      const editor = new TemplateEditor();
      editor.addBlock('text');

      const result = editor.validateTemplate();

      expect(result.valid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });
  });

  describe('serialization', () => {
    it('should export to JSON', () => {
      const editor = new TemplateEditor();
      editor.addBlock('text');

      const json = editor.exportJSON();
      const parsed = JSON.parse(json);

      expect(parsed.blocks).toHaveLength(1);
    });

    it('should import from JSON', () => {
      const editor = new TemplateEditor();
      const template: Template = {
        id: 'imported',
        name: 'Imported Template',
        blocks: [{ id: 'block-1', type: 'text', content: 'Imported' }],
      };

      editor.importJSON(JSON.stringify(template));

      expect(editor.getTemplate().name).toBe('Imported Template');
    });
  });

  describe('drag and drop', () => {
    it('should check if block can be dragged', () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock('text')!;

      expect(editor.canDrag(block.id)).toBe(true);
    });

    it('should check if block can be dropped', () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock('container')!;
      const text = editor.addBlock('text')!;

      // Can drop text inside container
      expect(editor.canDrop(text.id, container.id, 'inside')).toBe(true);

      // Cannot drop on itself
      expect(editor.canDrop(text.id, text.id, 'inside')).toBe(false);
    });

    it('should prevent dropping pagebreak inside container', () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock('container')!;
      const pagebreak = editor.addBlock('pagebreak')!;

      expect(editor.canDrop(pagebreak.id, container.id, 'inside')).toBe(false);
    });

    it('should get drop zones', () => {
      const editor = new TemplateEditor();
      const container = editor.addBlock('container')!;
      const text = editor.addBlock('text')!;

      const zones = editor.getDropZones(text.id);

      // Should have zones for root, container, etc.
      expect(zones.length).toBeGreaterThan(0);
      expect(zones.some((z) => z.targetId === container.id && z.position === 'inside')).toBe(true);
    });

    it('should provide DragDropPort interface', () => {
      const editor = new TemplateEditor();
      const port = editor.getDragDropPort();

      expect(typeof port.canDrag).toBe('function');
      expect(typeof port.canDrop).toBe('function');
      expect(typeof port.getDropZones).toBe('function');
      expect(typeof port.drop).toBe('function');
    });
  });

  describe('columns operations', () => {
    it('should create columns block with 2 default columns', () => {
      const editor = new TemplateEditor();
      const columns = editor.addBlock('columns')!;

      expect(columns.type).toBe('columns');
      expect((columns as { columns: unknown[] }).columns).toHaveLength(2);
    });

    it('should add column to columns block', () => {
      const editor = new TemplateEditor();
      const columns = editor.addBlock('columns')!;

      editor.addColumn(columns.id);

      const updated = editor.findBlock(columns.id) as { columns: unknown[] };
      expect(updated.columns).toHaveLength(3);
    });

    it('should limit to 6 columns', () => {
      const onError = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onError } });
      const columns = editor.addBlock('columns')!;

      // Add 4 more columns (start with 2)
      editor.addColumn(columns.id);
      editor.addColumn(columns.id);
      editor.addColumn(columns.id);
      editor.addColumn(columns.id);

      // Try to add 7th column
      editor.addColumn(columns.id);

      expect(onError).toHaveBeenCalled();
      const updated = editor.findBlock(columns.id) as { columns: unknown[] };
      expect(updated.columns).toHaveLength(6);
    });
  });

  describe('table operations', () => {
    it('should create table block with default structure', () => {
      const editor = new TemplateEditor();
      const table = editor.addBlock('table')!;

      expect(table.type).toBe('table');
      const tableBlock = table as { rows: { cells: unknown[] }[] };
      expect(tableBlock.rows).toHaveLength(3); // header + 2 data rows
      expect(tableBlock.rows[0].cells).toHaveLength(3);
    });

    it('should add row to table', () => {
      const editor = new TemplateEditor();
      const table = editor.addBlock('table')!;

      editor.addRow(table.id);

      const updated = editor.findBlock(table.id) as { rows: unknown[] };
      expect(updated.rows).toHaveLength(4);
    });
  });

  describe('block registry', () => {
    it('should list all block types', () => {
      const editor = new TemplateEditor();
      const types = editor.getBlockTypes();

      expect(types).toContain('text');
      expect(types).toContain('container');
      expect(types).toContain('columns');
      expect(types).toContain('table');
      expect(types).toContain('pagebreak');
    });

    it('should get block definition', () => {
      const editor = new TemplateEditor();
      const def = editor.getBlockDefinition('text');

      expect(def).toBeDefined();
      expect(def?.type).toBe('text');
    });
  });
});
