import { create } from 'zustand';
import { immer } from 'zustand/middleware/immer';
import type { Template, Block, PreviewOverrides, DocumentStyles, PageSettings } from '../types/template';
import { v4 as uuidv4 } from 'uuid';

interface EditorState {
  template: Template;
  selectedBlockId: string | null;
  testData: Record<string, unknown>;
  previewOverrides: PreviewOverrides;
}

interface EditorActions {
  setTemplate: (template: Template) => void;
  selectBlock: (id: string | null) => void;
  updateBlock: (id: string, updates: Partial<Block>) => void;
  addBlock: (block: Block, parentId: string | null, index: number) => void;
  moveBlock: (id: string, newParentId: string | null, newIndex: number) => void;
  deleteBlock: (id: string) => void;
  setTestData: (data: Record<string, unknown>) => void;
  setPreviewOverride: (type: 'conditionals' | 'loops', id: string, value: unknown) => void;
  updateDocumentStyles: (styles: Partial<DocumentStyles>) => void;
  updatePageSettings: (settings: Partial<PageSettings>) => void;
}

type EditorStore = EditorState & EditorActions;

const defaultTemplate: Template = {
  id: uuidv4(),
  name: 'Untitled Template',
  version: 1,
  pageSettings: {
    format: 'A4',
    orientation: 'portrait',
    margins: { top: 20, right: 20, bottom: 20, left: 20 },
  },
  blocks: [],
};

const defaultTestData = {
  customer: {
    name: 'John Doe',
    email: 'john@example.com',
    address: '123 Main Street',
  },
  invoice: {
    number: 'INV-001',
    date: '2025-01-15',
    total: 1250.00,
  },
  items: [
    { name: 'Product A', quantity: 2, price: 500 },
    { name: 'Product B', quantity: 1, price: 250 },
  ],
};

// Helper to find and update a block in a nested structure
function findAndUpdateBlock(
  blocks: Block[],
  id: string,
  updater: (block: Block) => Block | null
): Block[] {
  return blocks.reduce<Block[]>((acc, block) => {
    if (block.id === id) {
      const updated = updater(block);
      if (updated) acc.push(updated);
      return acc;
    }

    const updatedBlock = { ...block };

    if ('children' in updatedBlock && Array.isArray(updatedBlock.children)) {
      updatedBlock.children = findAndUpdateBlock(updatedBlock.children, id, updater);
    }

    // Handle columns block - recursively search within column children
    if (updatedBlock.type === 'columns' && 'columns' in updatedBlock) {
      updatedBlock.columns = updatedBlock.columns.map((column: any) => ({
        ...column,
        children: findAndUpdateBlock(column.children, id, updater),
      }));
    }

    // Handle table block - recursively search within cell children
    if (updatedBlock.type === 'table' && 'rows' in updatedBlock) {
      updatedBlock.rows = updatedBlock.rows.map((row: any) => ({
        ...row,
        cells: row.cells.map((cell: any) => ({
          ...cell,
          children: findAndUpdateBlock(cell.children, id, updater),
        })),
      }));
    }

    acc.push(updatedBlock as Block);
    return acc;
  }, []);
}

export const useEditorStore = create<EditorStore>()(
  immer((set) => ({
    template: defaultTemplate,
    selectedBlockId: null,
    testData: defaultTestData,
    previewOverrides: {
      conditionals: {},
      loops: {},
    },

    setTemplate: (template) =>
      set((state) => {
        state.template = template;
      }),

    selectBlock: (id) =>
      set((state) => {
        state.selectedBlockId = id;
      }),

    updateBlock: (id, updates) =>
      set((state) => {
        state.template.blocks = findAndUpdateBlock(
          state.template.blocks,
          id,
          (block) => ({ ...block, ...updates } as Block)
        );
      }),

    addBlock: (block, parentId, index) =>
      set((state) => {
        if (parentId === null) {
          state.template.blocks.splice(index, 0, block);
        } else if (parentId.includes('::')) {
          const parts = parentId.split('::');
          if (parts.length === 2) {
            // Handle composite ID for columns (blockId::columnId)
            const [blockId, columnId] = parts;
            state.template.blocks = findAndUpdateBlock(
              state.template.blocks,
              blockId,
              (parent) => {
                if (parent.type === 'columns' && 'columns' in parent) {
                  const updatedParent = { ...parent };
                  updatedParent.columns = updatedParent.columns.map((col: any) => {
                    if (col.id === columnId) {
                      return {
                        ...col,
                        children: [...col.children.slice(0, index), block, ...col.children.slice(index)],
                      };
                    }
                    return col;
                  });
                  return updatedParent;
                }
                return parent;
              }
            );
          } else if (parts.length === 3) {
            // Handle composite ID for table cells (blockId::rowId::cellId)
            const [blockId, rowId, cellId] = parts;
            state.template.blocks = findAndUpdateBlock(
              state.template.blocks,
              blockId,
              (parent) => {
                if (parent.type === 'table' && 'rows' in parent) {
                  const updatedParent = { ...parent };
                  updatedParent.rows = updatedParent.rows.map((row: any) => {
                    if (row.id === rowId) {
                      return {
                        ...row,
                        cells: row.cells.map((cell: any) => {
                          if (cell.id === cellId) {
                            return {
                              ...cell,
                              children: [...cell.children.slice(0, index), block, ...cell.children.slice(index)],
                            };
                          }
                          return cell;
                        }),
                      };
                    }
                    return row;
                  });
                  return updatedParent;
                }
                return parent;
              }
            );
          }
        } else {
          state.template.blocks = findAndUpdateBlock(
            state.template.blocks,
            parentId,
            (parent) => {
              if ('children' in parent && Array.isArray(parent.children)) {
                parent.children.splice(index, 0, block);
              }
              return parent;
            }
          );
        }
      }),

    moveBlock: (id, newParentId, newIndex) =>
      set((state) => {
        // Find and remove the block from its current location
        let movedBlock: Block | null = null;
        state.template.blocks = findAndUpdateBlock(
          state.template.blocks,
          id,
          (block) => {
            movedBlock = block;
            return null; // Remove from current location
          }
        );

        if (!movedBlock) return;

        // Add to new location
        if (newParentId === null) {
          state.template.blocks.splice(newIndex, 0, movedBlock);
        } else if (newParentId.includes('::')) {
          const parts = newParentId.split('::');
          if (parts.length === 2) {
            // Handle composite ID for columns (blockId::columnId)
            const [blockId, columnId] = parts;
            state.template.blocks = findAndUpdateBlock(
              state.template.blocks,
              blockId,
              (parent) => {
                if (parent.type === 'columns' && 'columns' in parent) {
                  const updatedParent = { ...parent };
                  updatedParent.columns = updatedParent.columns.map((col: any) => {
                    if (col.id === columnId) {
                      return {
                        ...col,
                        children: [...col.children.slice(0, newIndex), movedBlock!, ...col.children.slice(newIndex)],
                      };
                    }
                    return col;
                  });
                  return updatedParent;
                }
                return parent;
              }
            );
          } else if (parts.length === 3) {
            // Handle composite ID for table cells (blockId::rowId::cellId)
            const [blockId, rowId, cellId] = parts;
            state.template.blocks = findAndUpdateBlock(
              state.template.blocks,
              blockId,
              (parent) => {
                if (parent.type === 'table' && 'rows' in parent) {
                  const updatedParent = { ...parent };
                  updatedParent.rows = updatedParent.rows.map((row: any) => {
                    if (row.id === rowId) {
                      return {
                        ...row,
                        cells: row.cells.map((cell: any) => {
                          if (cell.id === cellId) {
                            return {
                              ...cell,
                              children: [...cell.children.slice(0, newIndex), movedBlock!, ...cell.children.slice(newIndex)],
                            };
                          }
                          return cell;
                        }),
                      };
                    }
                    return row;
                  });
                  return updatedParent;
                }
                return parent;
              }
            );
          }
        } else {
          state.template.blocks = findAndUpdateBlock(
            state.template.blocks,
            newParentId,
            (parent) => {
              if ('children' in parent && Array.isArray(parent.children)) {
                parent.children.splice(newIndex, 0, movedBlock!);
              }
              return parent;
            }
          );
        }
      }),

    deleteBlock: (id) =>
      set((state) => {
        state.template.blocks = findAndUpdateBlock(
          state.template.blocks,
          id,
          () => null
        );
        if (state.selectedBlockId === id) {
          state.selectedBlockId = null;
        }
      }),

    setTestData: (data) =>
      set((state) => {
        state.testData = data;
      }),

    setPreviewOverride: (type, id, value) =>
      set((state) => {
        (state.previewOverrides[type] as Record<string, unknown>)[id] = value;
      }),

    updateDocumentStyles: (styles) =>
      set((state) => {
        state.template.documentStyles = {
          ...state.template.documentStyles,
          ...styles,
        };
      }),

    updatePageSettings: (settings) =>
      set((state) => {
        state.template.pageSettings = {
          ...state.template.pageSettings,
          ...settings,
        };
      }),
  }))
);
