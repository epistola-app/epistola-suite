import { describe, it, expect } from 'vitest';
import { BlockTree, createEditorStore } from './store';
import type { Block, Template } from './types';

describe('createEditorStore', () => {
  it('should create a store with initial template', () => {
    const template: Template = {
      id: 'test-1',
      name: 'Test Template',
      blocks: [],
    };
    const store = createEditorStore(template);

    expect(store.getTemplate()).toEqual(template);
    expect(store.getSelectedBlockId()).toBeNull();
  });

  it('should update template', () => {
    const template: Template = {
      id: 'test-1',
      name: 'Test Template',
      blocks: [],
    };
    const store = createEditorStore(template);

    const updated: Template = { ...template, name: 'Updated' };
    store.setTemplate(updated);

    expect(store.getTemplate().name).toBe('Updated');
  });

  it('should update selected block ID', () => {
    const template: Template = {
      id: 'test-1',
      name: 'Test Template',
      blocks: [],
    };
    const store = createEditorStore(template);

    store.setSelectedBlockId('block-1');
    expect(store.getSelectedBlockId()).toBe('block-1');

    store.setSelectedBlockId(null);
    expect(store.getSelectedBlockId()).toBeNull();
  });
});

describe('BlockTree', () => {
  describe('findBlock', () => {
    it('should find a block at root level', () => {
      const blocks: Block[] = [
        { id: 'block-1', type: 'text', content: 'Hello' },
        { id: 'block-2', type: 'text', content: 'World' },
      ];

      const result = BlockTree.findBlock(blocks, 'block-1');
      expect(result).toEqual(blocks[0]);
    });

    it('should find a nested block in children', () => {
      const blocks: Block[] = [
        {
          id: 'container-1',
          type: 'container',
          children: [{ id: 'nested-1', type: 'text', content: 'Nested' }],
        },
      ];

      const result = BlockTree.findBlock(blocks, 'nested-1');
      expect(result).toEqual({ id: 'nested-1', type: 'text', content: 'Nested' });
    });

    it('should find a block in columns', () => {
      const blocks: Block[] = [
        {
          id: 'columns-1',
          type: 'columns',
          columns: [
            {
              id: 'col-1',
              size: 1,
              children: [{ id: 'in-col-1', type: 'text', content: 'In column' }],
            },
          ],
        },
      ];

      const result = BlockTree.findBlock(blocks, 'in-col-1');
      expect(result).toEqual({ id: 'in-col-1', type: 'text', content: 'In column' });
    });

    it('should find a block in table cells', () => {
      const blocks: Block[] = [
        {
          id: 'table-1',
          type: 'table',
          rows: [
            {
              id: 'row-1',
              cells: [
                {
                  id: 'cell-1',
                  children: [{ id: 'in-cell-1', type: 'text', content: 'In cell' }],
                },
              ],
            },
          ],
        },
      ];

      const result = BlockTree.findBlock(blocks, 'in-cell-1');
      expect(result).toEqual({ id: 'in-cell-1', type: 'text', content: 'In cell' });
    });

    it('should return null for non-existent block', () => {
      const blocks: Block[] = [{ id: 'block-1', type: 'text', content: 'Hello' }];

      const result = BlockTree.findBlock(blocks, 'non-existent');
      expect(result).toBeNull();
    });
  });

  describe('findParent', () => {
    it('should return null for root-level block', () => {
      const blocks: Block[] = [{ id: 'block-1', type: 'text', content: 'Hello' }];

      const result = BlockTree.findParent(blocks, 'block-1');
      expect(result).toBeNull();
    });

    it('should find parent of nested block', () => {
      const container: Block = {
        id: 'container-1',
        type: 'container',
        children: [{ id: 'nested-1', type: 'text', content: 'Nested' }],
      };
      const blocks: Block[] = [container];

      const result = BlockTree.findParent(blocks, 'nested-1');
      expect(result).toEqual(container);
    });
  });

  describe('addBlock', () => {
    it('should add block at root level', () => {
      const blocks: Block[] = [{ id: 'block-1', type: 'text', content: 'First' }];
      const newBlock: Block = { id: 'block-2', type: 'text', content: 'Second' };

      const result = BlockTree.addBlock(blocks, newBlock, null, 1);

      expect(result).toHaveLength(2);
      expect(result[1]).toEqual(newBlock);
    });

    it('should add block at beginning', () => {
      const blocks: Block[] = [{ id: 'block-1', type: 'text', content: 'First' }];
      const newBlock: Block = { id: 'block-2', type: 'text', content: 'Before' };

      const result = BlockTree.addBlock(blocks, newBlock, null, 0);

      expect(result).toHaveLength(2);
      expect(result[0]).toEqual(newBlock);
    });

    it('should add block to container children', () => {
      const blocks: Block[] = [
        {
          id: 'container-1',
          type: 'container',
          children: [],
        },
      ];
      const newBlock: Block = { id: 'new-1', type: 'text', content: 'New' };

      const result = BlockTree.addBlock(blocks, newBlock, 'container-1', 0);

      const container = result[0] as { children: Block[] };
      expect(container.children).toHaveLength(1);
      expect(container.children[0]).toEqual(newBlock);
    });

    it('should add block to column', () => {
      const blocks: Block[] = [
        {
          id: 'columns-1',
          type: 'columns',
          columns: [{ id: 'col-1', size: 1, children: [] }],
        },
      ];
      const newBlock: Block = { id: 'new-1', type: 'text', content: 'New' };

      const result = BlockTree.addBlock(blocks, newBlock, 'col-1', 0);

      const columnsBlock = result[0] as { columns: { children: Block[] }[] };
      expect(columnsBlock.columns[0].children).toHaveLength(1);
      expect(columnsBlock.columns[0].children[0]).toEqual(newBlock);
    });

    it('should add block to table cell', () => {
      const blocks: Block[] = [
        {
          id: 'table-1',
          type: 'table',
          rows: [{ id: 'row-1', cells: [{ id: 'cell-1', children: [] }] }],
        },
      ];
      const newBlock: Block = { id: 'new-1', type: 'text', content: 'New' };

      const result = BlockTree.addBlock(blocks, newBlock, 'cell-1', 0);

      const tableBlock = result[0] as { rows: { cells: { children: Block[] }[] }[] };
      expect(tableBlock.rows[0].cells[0].children).toHaveLength(1);
      expect(tableBlock.rows[0].cells[0].children[0]).toEqual(newBlock);
    });
  });

  describe('removeBlock', () => {
    it('should remove block at root level', () => {
      const blocks: Block[] = [
        { id: 'block-1', type: 'text', content: 'First' },
        { id: 'block-2', type: 'text', content: 'Second' },
      ];

      const result = BlockTree.removeBlock(blocks, 'block-1');

      expect(result).toHaveLength(1);
      expect(result[0].id).toBe('block-2');
    });

    it('should remove nested block', () => {
      const blocks: Block[] = [
        {
          id: 'container-1',
          type: 'container',
          children: [
            { id: 'nested-1', type: 'text', content: 'Nested' },
            { id: 'nested-2', type: 'text', content: 'Stay' },
          ],
        },
      ];

      const result = BlockTree.removeBlock(blocks, 'nested-1');

      const container = result[0] as { children: Block[] };
      expect(container.children).toHaveLength(1);
      expect(container.children[0].id).toBe('nested-2');
    });

    it('should remove block from column', () => {
      const blocks: Block[] = [
        {
          id: 'columns-1',
          type: 'columns',
          columns: [
            {
              id: 'col-1',
              size: 1,
              children: [{ id: 'in-col', type: 'text', content: 'Remove me' }],
            },
          ],
        },
      ];

      const result = BlockTree.removeBlock(blocks, 'in-col');

      const columnsBlock = result[0] as { columns: { children: Block[] }[] };
      expect(columnsBlock.columns[0].children).toHaveLength(0);
    });
  });

  describe('updateBlock', () => {
    it('should update block at root level', () => {
      const blocks: Block[] = [{ id: 'block-1', type: 'text', content: 'Original' }];

      const result = BlockTree.updateBlock(blocks, 'block-1', { content: 'Updated' });

      expect((result[0] as { content: string }).content).toBe('Updated');
    });

    it('should update nested block', () => {
      const blocks: Block[] = [
        {
          id: 'container-1',
          type: 'container',
          children: [{ id: 'nested-1', type: 'text', content: 'Original' }],
        },
      ];

      const result = BlockTree.updateBlock(blocks, 'nested-1', { content: 'Updated' });

      const container = result[0] as { children: Block[] };
      expect((container.children[0] as { content: string }).content).toBe('Updated');
    });
  });

  describe('moveBlock', () => {
    it('should move block within root level', () => {
      const blocks: Block[] = [
        { id: 'block-1', type: 'text', content: 'First' },
        { id: 'block-2', type: 'text', content: 'Second' },
        { id: 'block-3', type: 'text', content: 'Third' },
      ];

      const result = BlockTree.moveBlock(blocks, 'block-3', null, 0);

      expect(result[0].id).toBe('block-3');
      expect(result[1].id).toBe('block-1');
      expect(result[2].id).toBe('block-2');
    });

    it('should move block from root into container', () => {
      const blocks: Block[] = [
        { id: 'block-1', type: 'text', content: 'Move me' },
        { id: 'container-1', type: 'container', children: [] },
      ];

      const result = BlockTree.moveBlock(blocks, 'block-1', 'container-1', 0);

      expect(result).toHaveLength(1);
      const container = result[0] as { children: Block[] };
      expect(container.children).toHaveLength(1);
      expect(container.children[0].id).toBe('block-1');
    });

    it('should move block from container to root', () => {
      const blocks: Block[] = [
        {
          id: 'container-1',
          type: 'container',
          children: [{ id: 'nested-1', type: 'text', content: 'Move me' }],
        },
      ];

      const result = BlockTree.moveBlock(blocks, 'nested-1', null, 1);

      expect(result).toHaveLength(2);
      expect(result[1].id).toBe('nested-1');
      const container = result[0] as { children: Block[] };
      expect(container.children).toHaveLength(0);
    });
  });

  describe('getChildCount', () => {
    it('should count root blocks', () => {
      const blocks: Block[] = [
        { id: 'block-1', type: 'text', content: 'First' },
        { id: 'block-2', type: 'text', content: 'Second' },
      ];

      const count = BlockTree.getChildCount(blocks, null);
      expect(count).toBe(2);
    });

    it('should count container children', () => {
      const blocks: Block[] = [
        {
          id: 'container-1',
          type: 'container',
          children: [
            { id: 'nested-1', type: 'text', content: 'One' },
            { id: 'nested-2', type: 'text', content: 'Two' },
          ],
        },
      ];

      const count = BlockTree.getChildCount(blocks, 'container-1');
      expect(count).toBe(2);
    });

    it('should return 0 for non-existent parent', () => {
      const blocks: Block[] = [];
      const count = BlockTree.getChildCount(blocks, 'non-existent');
      expect(count).toBe(0);
    });
  });
});
