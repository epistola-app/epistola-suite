import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EpistolaEditor } from './EpistolaEditor.js';
import {
  createTestDocumentWithChildren,
  nodeId,
  resetCounter,
  slotId,
  testRegistry,
} from '../engine/test-helpers.js';
import type { TemplateDocument, NodeId, SlotId } from '../types/index.js';
import {
  BLOCK_CLIPBOARD_MIME,
  extractBlockSubtree,
  serializeBlockClipboard,
} from './block-clipboard.js';
import type { DataExample, JsonSchema } from '../data-contract/types.js';

function createClipboardData(store = new Map<string, string>()): DataTransfer {
  return {
    setData: (type: string, value: string) => {
      store.set(type, value);
    },
    getData: (type: string) => store.get(type) ?? '',
  } as unknown as DataTransfer;
}

function createMultiSlotDocument(): {
  doc: TemplateDocument;
  textNodeId: NodeId;
  columnsNodeId: NodeId;
  leftSlotId: SlotId;
  rightSlotId: SlotId;
} {
  const rootId = nodeId('root');
  const rootSlotId = slotId('root-slot');
  const textNodeId = nodeId('text1');
  const columnsNodeId = nodeId('columns1');
  const leftSlotId = slotId('column-left');
  const rightSlotId = slotId('column-right');

  const doc: TemplateDocument = {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
      [textNodeId]: { id: textNodeId, type: 'text', slots: [], props: { content: null } },
      [columnsNodeId]: { id: columnsNodeId, type: 'columns', slots: [leftSlotId, rightSlotId] },
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootId,
        name: 'children',
        children: [textNodeId, columnsNodeId],
      },
      [leftSlotId]: { id: leftSlotId, nodeId: columnsNodeId, name: 'left', children: [] },
      [rightSlotId]: { id: rightSlotId, nodeId: columnsNodeId, name: 'right', children: [] },
    },
    themeRef: { type: 'inherit' },
  };

  return { doc, textNodeId, columnsNodeId, leftSlotId, rightSlotId };
}

beforeEach(() => {
  resetCounter();
});

describe('EpistolaEditor block clipboard', () => {
  it('writes the selected block to clipboard data', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _isCopyPasteEventInsideEditor: () => boolean;
      _handleCopy: (e: ClipboardEvent) => void;
    };
    editorAny._selectedNodeId = textNodeId;
    editorAny._isCopyPasteEventInsideEditor = () => true;

    const store = new Map<string, string>();
    let prevented = false;
    const eventTarget = { closest: () => null } as unknown as EventTarget;

    editorAny._handleCopy({
      target: eventTarget,
      clipboardData: createClipboardData(store),
      preventDefault: () => {
        prevented = true;
      },
    } as ClipboardEvent);

    expect(prevented).toBe(true);
    expect(store.has(BLOCK_CLIPBOARD_MIME)).toBe(true);
  });

  it('ignores block copy when the event is outside the editor', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _handleCopy: (e: ClipboardEvent) => void;
    };
    editorAny._selectedNodeId = textNodeId;

    const store = new Map<string, string>();
    let prevented = false;

    editorAny._handleCopy({
      target: { closest: () => null } as unknown as EventTarget,
      clipboardData: createClipboardData(store),
      preventDefault: () => {
        prevented = true;
      },
    } as ClipboardEvent);

    expect(prevented).toBe(false);
    expect(store.has(BLOCK_CLIPBOARD_MIME)).toBe(false);
  });

  it('ignores block copy when an editable target is focused', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _isCopyPasteEventInsideEditor: () => boolean;
      _handleCopy: (e: ClipboardEvent) => void;
    };
    editorAny._selectedNodeId = textNodeId;
    editorAny._isCopyPasteEventInsideEditor = (): boolean => true;

    const store = new Map<string, string>();
    let prevented = false;
    const editableTarget = {
      nodeType: 1,
      closest: () => ({}),
      parentElement: null,
    } as unknown as EventTarget;

    editorAny._handleCopy({
      target: editableTarget,
      clipboardData: createClipboardData(store),
      preventDefault: () => {
        prevented = true;
      },
    } as ClipboardEvent);

    expect(prevented).toBe(false);
    expect(store.has(BLOCK_CLIPBOARD_MIME)).toBe(false);
  });

  it('opens the paste dialog when editor clipboard data is present', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _pasteDialogOpen: boolean;
      _isCopyPasteEventInsideEditor: () => boolean;
      _handlePaste: (e: ClipboardEvent) => void;
    };
    editorAny._isCopyPasteEventInsideEditor = () => true;

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const store = new Map<string, string>([
      [BLOCK_CLIPBOARD_MIME, serializeBlockClipboard(subtree!)],
    ]);
    let prevented = false;
    const eventTarget = { closest: () => null } as unknown as EventTarget;

    editorAny._handlePaste({
      target: eventTarget,
      clipboardData: createClipboardData(store),
      preventDefault: () => {
        prevented = true;
      },
    } as ClipboardEvent);

    expect(prevented).toBe(true);
    expect(editorAny._pasteDialogOpen).toBe(true);
  });

  it('ignores paste when clipboard data is not an editor block payload', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _pasteDialogOpen: boolean;
      _isCopyPasteEventInsideEditor: () => boolean;
      _handlePaste: (e: ClipboardEvent) => void;
    };
    editorAny._isCopyPasteEventInsideEditor = (): boolean => true;

    let prevented = false;
    editorAny._handlePaste({
      target: { closest: () => null } as unknown as EventTarget,
      clipboardData: createClipboardData(new Map([['text/plain', 'hello']])),
      preventDefault: () => {
        prevented = true;
      },
    } as ClipboardEvent);

    expect(prevented).toBe(false);
    expect(editorAny._pasteDialogOpen).toBe(false);
  });

  it('ignores paste when an editable target is focused', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _pasteDialogOpen: boolean;
      _isCopyPasteEventInsideEditor: () => boolean;
      _handlePaste: (e: ClipboardEvent) => void;
    };
    editorAny._isCopyPasteEventInsideEditor = (): boolean => true;

    let prevented = false;
    const editableTarget = {
      nodeType: 1,
      closest: () => ({}),
      parentElement: null,
    } as unknown as EventTarget;
    editorAny._handlePaste({
      target: editableTarget,
      clipboardData: createClipboardData(
        new Map([[BLOCK_CLIPBOARD_MIME, serializeBlockClipboard(subtree!)]]),
      ),
      preventDefault: () => {
        prevented = true;
      },
    } as ClipboardEvent);

    expect(prevented).toBe(false);
    expect(editorAny._pasteDialogOpen).toBe(false);
  });

  it('renders numbered after, before, and inside paste actions', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _renderPasteDialog: () => unknown;
    };
    editorAny._openPasteDialog(subtree!);

    const renderSource = String(editorAny._renderPasteDialog);

    expect(renderSource).toContain('data-testid="paste-after"');
    expect(renderSource).toContain('data-testid="paste-before"');
    expect(renderSource).toContain('data-testid="paste-inside"');
    expect(renderSource).toContain('Paste Block');
    expect(renderSource).toContain('After');
    expect(renderSource).toContain('Before');
    expect(renderSource).toContain('Inside');
  });

  it('opens a slot picker when inside is chosen on a multi-slot block', () => {
    const { doc, textNodeId, columnsNodeId } = createMultiSlotDocument();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _pasteDialogMode: 'placement' | 'slot';
      _pasteDialogSlotOptions: Array<{ slotId: string; label: string }>;
    };
    editorAny._selectedNodeId = columnsNodeId;
    editorAny._openPasteDialog(subtree!);

    editorAny._handlePastePlacement('inside');

    expect(editorAny._pasteDialogMode).toBe('slot');
    expect(editorAny._pasteDialogSlotOptions).toHaveLength(2);
  });

  it('returns to placement mode on escape from the slot picker', () => {
    const { doc, textNodeId, columnsNodeId } = createMultiSlotDocument();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _handlePasteDialogKeydown: (event: KeyboardEvent) => void;
      _pasteDialogMode: 'placement' | 'slot';
      _pasteDialogOpen: boolean;
    };
    editorAny._selectedNodeId = columnsNodeId;
    editorAny._openPasteDialog(subtree!);
    editorAny._handlePastePlacement('inside');

    let prevented = false;
    editorAny._handlePasteDialogKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
    } as KeyboardEvent);

    expect(prevented).toBe(true);
    expect(editorAny._pasteDialogMode).toBe('placement');
    expect(editorAny._pasteDialogOpen).toBe(true);
  });

  it('closes the paste dialog on escape from placement mode', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePasteDialogKeydown: (event: KeyboardEvent) => void;
      _pasteDialogOpen: boolean;
    };
    editorAny._openPasteDialog(subtree!);

    editorAny._handlePasteDialogKeydown({
      key: 'Escape',
      preventDefault: () => {},
    } as KeyboardEvent);

    expect(editorAny._pasteDialogOpen).toBe(false);
  });

  it('sets an error for an invalid slot number in slot mode', () => {
    const { doc, textNodeId, columnsNodeId } = createMultiSlotDocument();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _handlePasteDialogKeydown: (event: KeyboardEvent) => void;
      _pasteDialogError: string;
    };
    editorAny._selectedNodeId = columnsNodeId;
    editorAny._openPasteDialog(subtree!);
    editorAny._handlePastePlacement('inside');

    editorAny._handlePasteDialogKeydown({
      key: '9',
      preventDefault: () => {},
    } as KeyboardEvent);

    expect(editorAny._pasteDialogError).toBe('Invalid slot number');
  });

  it('sets an error when inside paste has no valid slots', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _pasteDialogError: string;
      _selectedNodeId: string | null;
    };
    editorAny._selectedNodeId = textNodeId;
    editorAny._openPasteDialog(subtree!);

    editorAny._handlePastePlacement('inside');

    expect(editorAny._pasteDialogError).toBe('No valid inside slot for selected block');
  });

  it('sets an error when before or after paste has no valid target', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _getPasteTargetForPlacement: () => null;
      _pasteDialogError: string;
    };
    editorAny._openPasteDialog(subtree!);
    editorAny._getPasteTargetForPlacement = () => null;

    editorAny._handlePastePlacement('after');
    expect(editorAny._pasteDialogError).toBe('No valid after target');

    editorAny._handlePastePlacement('before');
    expect(editorAny._pasteDialogError).toBe('No valid before target');
  });

  it('sets an error when a chosen inside slot cannot produce a target', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePasteSlotSelection: (slotId: string) => void;
      _buildInsideTargetFromSlot: () => null;
      _pasteDialogError: string;
    };
    editorAny._openPasteDialog(subtree!);
    editorAny._buildInsideTargetFromSlot = () => null;

    editorAny._handlePasteSlotSelection('slot-1');

    expect(editorAny._pasteDialogError).toBe('Cannot paste into selected slot');
  });

  it('sets an error when inserting a pasted subtree fails', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _insertPastedSubtreeAtTarget: (target: { slotId: SlotId; index: number }) => boolean;
      _pasteDialogError: string;
      _engine: { dispatch: () => { ok: false; error: string } };
    };
    editorAny._openPasteDialog(subtree!);
    editorAny._engine.dispatch = () => ({ ok: false, error: 'Insert failed' });

    const inserted = editorAny._insertPastedSubtreeAtTarget({
      slotId: doc.nodes[doc.root].slots[0],
      index: 0,
    });

    expect(inserted).toBe(false);
    expect(editorAny._pasteDialogError).toBe('Insert failed');
  });

  it('pastes into the chosen slot after the slot picker opens', () => {
    const { doc, textNodeId, columnsNodeId, rightSlotId } = createMultiSlotDocument();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _handlePasteSlotSelection: (slotId: string) => void;
      _engine: { doc: TemplateDocument; selectedNodeId: string | null };
      _pasteDialogOpen: boolean;
    };
    editorAny._selectedNodeId = columnsNodeId;
    editorAny._openPasteDialog(subtree!);
    editorAny._handlePastePlacement('inside');

    editorAny._handlePasteSlotSelection(rightSlotId);

    expect(editorAny._engine.doc.slots[rightSlotId].children).toHaveLength(1);
    expect(editorAny._engine.doc.slots[rightSlotId].children[0]).not.toBe(textNodeId);
    expect(editorAny._engine.selectedNodeId).toBe(
      editorAny._engine.doc.slots[rightSlotId].children[0],
    );
    expect(editorAny._pasteDialogOpen).toBe(false);
  });

  it('pastes inside immediately when only one valid slot exists', () => {
    const { doc, textNodeId, containerNodeId, containerSlotId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _pasteDialogMode: 'placement' | 'slot';
      _pasteDialogOpen: boolean;
      _engine: { doc: TemplateDocument; selectedNodeId: string | null };
    };
    editorAny._selectedNodeId = containerNodeId;
    editorAny._openPasteDialog(subtree!);

    editorAny._handlePastePlacement('inside');

    expect(editorAny._pasteDialogMode).toBe('placement');
    expect(editorAny._pasteDialogOpen).toBe(false);
    expect(editorAny._engine.doc.slots[containerSlotId].children).toHaveLength(1);
    expect(editorAny._engine.doc.slots[containerSlotId].children[0]).not.toBe(textNodeId);
    expect(editorAny._engine.selectedNodeId).toBe(
      editorAny._engine.doc.slots[containerSlotId].children[0],
    );
  });

  it('pastes at document end when no block is selected', () => {
    const { doc, textNodeId } = createMultiSlotDocument();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const rootSlotId = doc.nodes[doc.root].slots[0];
    const originalChildren = [...doc.slots[rootSlotId].children];
    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _engine: { doc: TemplateDocument; selectedNodeId: string | null };
    };
    editorAny._selectedNodeId = null;
    editorAny._openPasteDialog(subtree!);

    editorAny._handlePastePlacement('after');

    const nextChildren = editorAny._engine.doc.slots[rootSlotId].children;
    expect(nextChildren).toHaveLength(originalChildren.length + 1);
    expect(nextChildren.slice(0, originalChildren.length)).toEqual(originalChildren);
    expect(nextChildren[nextChildren.length - 1]).not.toBe(textNodeId);
    expect(editorAny._engine.selectedNodeId).toBe(nextChildren[nextChildren.length - 1]);
  });

  it('pastes at document start when no block is selected', () => {
    const { doc, textNodeId } = createMultiSlotDocument();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const rootSlotId = doc.nodes[doc.root].slots[0];
    const originalChildren = [...doc.slots[rootSlotId].children];
    const editorAny = editor as unknown as {
      _selectedNodeId: string | null;
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
      _engine: { doc: TemplateDocument; selectedNodeId: string | null };
    };
    editorAny._selectedNodeId = null;
    editorAny._openPasteDialog(subtree!);

    editorAny._handlePastePlacement('before');

    const nextChildren = editorAny._engine.doc.slots[rootSlotId].children;
    expect(nextChildren).toHaveLength(originalChildren.length + 1);
    expect(nextChildren[0]).not.toBe(textNodeId);
    expect(nextChildren.slice(1)).toEqual(originalChildren);
    expect(editorAny._engine.selectedNodeId).toBe(nextChildren[0]);
  });

  it('routes placement hotkeys to the matching paste actions', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const calls: Array<'after' | 'before' | 'inside'> = [];
    const editorAny = editor as unknown as {
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePasteDialogKeydown: (event: KeyboardEvent) => void;
      _handlePastePlacement: (placement: 'after' | 'before' | 'inside') => void;
    };
    editorAny._openPasteDialog(subtree!);
    editorAny._handlePastePlacement = (placement: 'after' | 'before' | 'inside'): void => {
      calls.push(placement);
    };

    for (const key of ['1', '2', '3']) {
      editorAny._handlePasteDialogKeydown({
        key,
        preventDefault: (): void => {},
      } as KeyboardEvent);
    }

    expect(calls).toEqual(['after', 'before', 'inside']);
  });

  it('closes the paste dialog when the backdrop is clicked', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const subtree = extractBlockSubtree(doc, textNodeId);
    expect(subtree).not.toBeNull();

    const editorAny = editor as unknown as {
      _openPasteDialog: (subtree: NonNullable<typeof subtree>) => void;
      _handlePasteDialogBackdropClick: () => void;
      _pasteDialogOpen: boolean;
    };
    editorAny._openPasteDialog(subtree!);

    editorAny._handlePasteDialogBackdropClick();

    expect(editorAny._pasteDialogOpen).toBe(false);
  });
});

describe('EpistolaEditor table cell-mode exit', () => {
  type CellSelection = { startRow: number; startCol: number; endRow: number; endCol: number };

  type EditorHandle = {
    _handleKeydown: (event: KeyboardEvent) => void;
    _engine: {
      selectedNodeId: string | null;
      selectNode: (id: string | null) => void;
      getComponentState: <T>(key: string) => T | undefined;
      setComponentState: (key: string, value: unknown) => void;
    };
  };

  function createDocWithTable(): { doc: TemplateDocument; tableNodeId: NodeId } {
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const tableNodeId = nodeId('table1');
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
        [tableNodeId]: {
          id: tableNodeId,
          type: 'table',
          slots: [],
          props: { rows: 2, columns: 2 },
        },
      },
      slots: {
        [rootSlotId]: {
          id: rootSlotId,
          nodeId: rootId,
          name: 'children',
          children: [tableNodeId],
        },
      },
      themeRef: { type: 'inherit' },
    };
    return { doc, tableNodeId };
  }

  function escapeEvent(overrides: Partial<KeyboardEvent> = {}): {
    event: KeyboardEvent;
    wasPrevented: () => boolean;
    wasStopped: () => boolean;
  } {
    let prevented = false;
    let stopped = false;
    const event = {
      key: 'Escape',
      code: 'Escape',
      ctrlKey: false,
      metaKey: false,
      altKey: false,
      shiftKey: false,
      preventDefault: () => {
        prevented = true;
      },
      stopPropagation: () => {
        stopped = true;
      },
      ...overrides,
    } as unknown as KeyboardEvent;
    return {
      event,
      wasPrevented: () => prevented,
      wasStopped: () => stopped,
    };
  }

  it('Escape clears an active cell selection without deselecting the table', () => {
    const { doc, tableNodeId } = createDocWithTable();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as EditorHandle;
    editorAny._engine.selectNode(tableNodeId);
    const selection: CellSelection = { startRow: 0, startCol: 0, endRow: 0, endCol: 0 };
    editorAny._engine.setComponentState('table:cellSelection', selection);

    const { event, wasPrevented, wasStopped } = escapeEvent();
    editorAny._handleKeydown(event);

    expect(editorAny._engine.getComponentState('table:cellSelection')).toBeNull();
    expect(editorAny._engine.selectedNodeId).toBe(tableNodeId);
    expect(wasPrevented()).toBe(true);
    expect(wasStopped()).toBe(true);
  });

  it('Escape with no cell selection falls through to the existing deselect shortcut', () => {
    const { doc, tableNodeId } = createDocWithTable();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as EditorHandle;
    editorAny._engine.selectNode(tableNodeId);
    expect(editorAny._engine.getComponentState('table:cellSelection')).toBeNull();

    const { event } = escapeEvent();
    editorAny._handleKeydown(event);

    // My interceptor must skip (no cell selection), letting the existing
    // 'deselectSelectedBlock' shortcut deselect to document level.
    expect(editorAny._engine.selectedNodeId).toBeNull();
  });

  it('modified Escape (e.g. Shift+Escape) bypasses the cell-mode exit branch', () => {
    const { doc, tableNodeId } = createDocWithTable();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as EditorHandle;
    editorAny._engine.selectNode(tableNodeId);
    const selection: CellSelection = { startRow: 1, startCol: 2, endRow: 1, endCol: 2 };
    editorAny._engine.setComponentState('table:cellSelection', selection);

    const { event } = escapeEvent({ shiftKey: true });
    editorAny._handleKeydown(event);

    // If the interceptor fired for Shift+Escape, the table would stay
    // selected (it calls stopPropagation before the shortcut resolver runs).
    // Skipping it lets the existing deselect shortcut clear the selection.
    expect(editorAny._engine.selectedNodeId).toBeNull();
  });

  it('Escape on a non-table node with stale cell-selection state does not clear the selection', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as EditorHandle;
    editorAny._engine.selectNode(textNodeId);
    // Spurious cell-selection state on a non-table node — the interceptor
    // must not act on this and must let the deselect shortcut run as usual.
    editorAny._engine.setComponentState('table:cellSelection', {
      startRow: 0,
      startCol: 0,
      endRow: 0,
      endCol: 0,
    } satisfies CellSelection);

    const { event } = escapeEvent();
    editorAny._handleKeydown(event);

    expect(editorAny._engine.selectedNodeId).toBeNull();
  });
});

describe('EpistolaEditor data contract integration', () => {
  function createDataContractOptions() {
    return {
      initialSchema: { type: 'object', properties: { name: { type: 'string' } } } as JsonSchema,
      initialExamples: [
        { id: 'ex-1', name: 'Example 1', data: { name: 'Alice' } },
        { id: 'ex-2', name: 'Example 2', data: { name: 'Bob' } },
      ] as DataExample[],
      callbacks: {
        onSaveSchema: vi.fn(async () => ({ success: true })),
        onSaveDataExamples: vi.fn(async () => ({ success: true })),
        onUpdateDataExample: vi.fn(async () => ({ success: true })),
        onDeleteDataExample: vi.fn(async () => ({ success: true })),
      },
    };
  }

  it('opens modal on toolbar event and closes it on Escape', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      dataContractOptions: unknown;
      _dataContractOpen: boolean;
      _mountDataContractEditor: () => void;
      _handleOpenDataContract: () => void;
      _handleKeydown: (event: KeyboardEvent) => void;
    };
    editorAny.dataContractOptions = createDataContractOptions();
    editorAny._mountDataContractEditor = () => {};

    editorAny._handleOpenDataContract();
    expect(editorAny._dataContractOpen).toBe(true);

    let prevented = false;
    let stopped = false;
    editorAny._handleKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
      stopPropagation: () => {
        stopped = true;
      },
    } as KeyboardEvent);

    expect(editorAny._dataContractOpen).toBe(false);
    expect(prevented).toBe(true);
    expect(stopped).toBe(true);
  });

  it('ignores open-data-contract when options are missing', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _dataContractOpen: boolean;
      _handleOpenDataContract: () => void;
    };

    editorAny._handleOpenDataContract();
    expect(editorAny._dataContractOpen).toBe(false);
  });

  it('closes modal via explicit close handler', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _dataContractOpen: boolean;
      _closeDataContract: () => void;
    };
    editorAny._dataContractOpen = true;

    editorAny._closeDataContract();
    expect(editorAny._dataContractOpen).toBe(false);
  });

  it('does not mount editor host when options are missing', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _dataContractMounted: boolean;
      _mountDataContractEditor: () => void;
    };

    editorAny._mountDataContractEditor();
    expect(editorAny._dataContractMounted).toBe(false);
  });

  it('does not remount when already mounted', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      dataContractOptions: unknown;
      _dataContractMounted: boolean;
      _mountDataContractEditor: () => void;
      querySelector: (selector: string) => HTMLElement | null;
    };
    editorAny.dataContractOptions = createDataContractOptions();
    editorAny._dataContractMounted = true;

    const appendChild = vi.fn();
    editorAny.querySelector = () => ({ appendChild }) as unknown as HTMLElement;

    editorAny._mountDataContractEditor();
    expect(appendChild).not.toHaveBeenCalled();
  });

  it('does not mount when host is missing', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      dataContractOptions: unknown;
      _dataContractMounted: boolean;
      _mountDataContractEditor: () => void;
      querySelector: (selector: string) => HTMLElement | null;
    };
    editorAny.dataContractOptions = createDataContractOptions();
    editorAny.querySelector = () => null;

    editorAny._mountDataContractEditor();
    expect(editorAny._dataContractMounted).toBe(false);
  });

  it('onSaveSchema callback updates engine data context on success', async () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const options = createDataContractOptions();
    const editorAny = editor as unknown as {
      dataContractOptions: typeof options;
      _createDataContractCallbacks: () => {
        onSaveSchema?: (
          schema: JsonSchema | null,
          forceUpdate?: boolean,
          dataExamples?: DataExample[],
        ) => Promise<{ success: boolean }>;
      };
      _engine: {
        setDataContext: (options: { dataModel?: object | null; dataExamples?: object[] }) => void;
      };
    };

    const setDataContext = vi.fn();
    editorAny._engine.setDataContext = setDataContext;
    editorAny.dataContractOptions = options;
    const callbacks = editorAny._createDataContractCallbacks();
    const nextSchema = { type: 'object', properties: { age: { type: 'number' } } } as JsonSchema;
    const nextExamples: DataExample[] = [{ id: 'ex-3', name: 'Example 3', data: { age: 42 } }];

    await callbacks.onSaveSchema?.(nextSchema, false, nextExamples);

    expect(setDataContext).toHaveBeenCalledWith({
      dataModel: nextSchema,
      dataExamples: nextExamples,
    });
  });

  it('onSaveSchema does not update engine data context on failure', async () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const options = createDataContractOptions();
    options.callbacks.onSaveSchema = vi.fn(async () => ({ success: false, error: 'nope' }));

    const editorAny = editor as unknown as {
      dataContractOptions: typeof options;
      _createDataContractCallbacks: () => {
        onSaveSchema?: (
          schema: JsonSchema | null,
          forceUpdate?: boolean,
          dataExamples?: DataExample[],
        ) => Promise<{ success: boolean }>;
      };
      _engine: {
        setDataContext: (options: { dataModel?: object | null; dataExamples?: object[] }) => void;
      };
    };

    const setDataContext = vi.fn();
    editorAny._engine.setDataContext = setDataContext;
    editorAny.dataContractOptions = options;
    const callbacks = editorAny._createDataContractCallbacks();
    const nextSchema = { type: 'object', properties: { age: { type: 'number' } } } as JsonSchema;

    await callbacks.onSaveSchema?.(nextSchema, false, []);
    expect(setDataContext).not.toHaveBeenCalled();
  });

  it('onSaveDataExamples updates engine examples on success only', async () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const options = createDataContractOptions();
    const editorAny = editor as unknown as {
      dataContractOptions: typeof options;
      _createDataContractCallbacks: () => {
        onSaveDataExamples?: (examples: DataExample[]) => Promise<{ success: boolean }>;
      };
      _engine: { setDataExamples: (examples: DataExample[]) => void };
    };
    const setDataExamples = vi.fn();
    editorAny._engine.setDataExamples = setDataExamples;
    editorAny.dataContractOptions = options;

    const callbacks = editorAny._createDataContractCallbacks();
    const nextExamples: DataExample[] = [{ id: 'ex-9', name: 'N', data: { n: 1 } }];

    await callbacks.onSaveDataExamples?.(nextExamples);
    expect(setDataExamples).toHaveBeenCalledWith(nextExamples);

    setDataExamples.mockClear();
    options.callbacks.onSaveDataExamples = vi.fn(async () => ({ success: false, error: 'bad' }));
    editorAny.dataContractOptions = options;
    const callbacksFail = editorAny._createDataContractCallbacks();
    await callbacksFail.onSaveDataExamples?.(nextExamples);
    expect(setDataExamples).not.toHaveBeenCalled();
  });

  it('onUpdateDataExample and onDeleteDataExample update engine examples on success', async () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    const options = createDataContractOptions();
    editor.initEngine(doc, testRegistry(), { dataExamples: options.initialExamples });

    options.callbacks.onUpdateDataExample = vi.fn(async () => ({
      success: true,
      example: { id: 'ex-2', name: 'Renamed', data: { name: 'Bobby' } },
    }));

    const editorAny = editor as unknown as {
      dataContractOptions: typeof options;
      _createDataContractCallbacks: () => {
        onUpdateDataExample?: (
          exampleId: string,
          updates: { name?: string; data?: Record<string, unknown> },
          forceUpdate?: boolean,
        ) => Promise<{ success: boolean; example?: DataExample }>;
        onDeleteDataExample?: (exampleId: string) => Promise<{ success: boolean }>;
      };
      _engine: {
        dataExamples?: DataExample[];
        setDataExamples: (examples: DataExample[]) => void;
      };
    };

    const setDataExamples = vi.fn();
    editorAny._engine.setDataExamples = setDataExamples;
    editorAny.dataContractOptions = options;
    const callbacks = editorAny._createDataContractCallbacks();

    await callbacks.onUpdateDataExample?.('ex-2', { name: 'Renamed' }, false);
    expect(setDataExamples).toHaveBeenCalledWith([
      options.initialExamples[0],
      { id: 'ex-2', name: 'Renamed', data: { name: 'Bobby' } },
    ]);

    setDataExamples.mockClear();
    await callbacks.onDeleteDataExample?.('ex-1');
    expect(setDataExamples).toHaveBeenCalledWith([
      { id: 'ex-2', name: 'Example 2', data: { name: 'Bob' } },
    ]);
  });

  it('onUpdateDataExample does not update engine when callback fails or returns no example', async () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    const options = createDataContractOptions();
    editor.initEngine(doc, testRegistry(), { dataExamples: options.initialExamples });

    const editorAny = editor as unknown as {
      dataContractOptions: typeof options;
      _createDataContractCallbacks: () => {
        onUpdateDataExample?: (
          exampleId: string,
          updates: { name?: string; data?: Record<string, unknown> },
          forceUpdate?: boolean,
        ) => Promise<{ success: boolean; example?: DataExample }>;
      };
      _engine: { setDataExamples: (examples: DataExample[]) => void };
    };

    const setDataExamples = vi.fn();
    editorAny._engine.setDataExamples = setDataExamples;

    options.callbacks.onUpdateDataExample = vi.fn(async () => ({ success: false }));
    editorAny.dataContractOptions = options;
    const callbacksFail = editorAny._createDataContractCallbacks();
    await callbacksFail.onUpdateDataExample?.('ex-2', { name: 'Renamed' }, false);
    expect(setDataExamples).not.toHaveBeenCalled();

    options.callbacks.onUpdateDataExample = vi.fn(async () => ({ success: true }));
    editorAny.dataContractOptions = options;
    const callbacksNoExample = editorAny._createDataContractCallbacks();
    await callbacksNoExample.onUpdateDataExample?.('ex-2', { name: 'Renamed' }, false);
    expect(setDataExamples).not.toHaveBeenCalled();
  });

  it('onDeleteDataExample does not update engine when callback fails', async () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    const options = createDataContractOptions();
    editor.initEngine(doc, testRegistry(), { dataExamples: options.initialExamples });

    options.callbacks.onDeleteDataExample = vi.fn(async () => ({ success: false }));

    const editorAny = editor as unknown as {
      dataContractOptions: typeof options;
      _createDataContractCallbacks: () => {
        onDeleteDataExample?: (exampleId: string) => Promise<{ success: boolean }>;
      };
      _engine: { setDataExamples: (examples: DataExample[]) => void };
    };

    const setDataExamples = vi.fn();
    editorAny._engine.setDataExamples = setDataExamples;
    editorAny.dataContractOptions = options;

    const callbacks = editorAny._createDataContractCallbacks();
    await callbacks.onDeleteDataExample?.('ex-1');
    expect(setDataExamples).not.toHaveBeenCalled();
  });

  it('creates empty callback object when data contract callbacks are omitted', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      dataContractOptions: {
        initialSchema: JsonSchema;
        initialExamples: DataExample[];
        callbacks: Record<string, unknown>;
      };
      _createDataContractCallbacks: () => {
        onSaveSchema?: unknown;
        onSaveDataExamples?: unknown;
        onUpdateDataExample?: unknown;
        onDeleteDataExample?: unknown;
      };
    };

    editorAny.dataContractOptions = {
      initialSchema: { type: 'object', properties: {} },
      initialExamples: [],
      callbacks: {},
    };

    const callbacks = editorAny._createDataContractCallbacks();
    expect(callbacks.onSaveSchema).toBeUndefined();
    expect(callbacks.onSaveDataExamples).toBeUndefined();
    expect(callbacks.onUpdateDataExample).toBeUndefined();
    expect(callbacks.onDeleteDataExample).toBeUndefined();
  });

  it('returns a template result when options are absent', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      _renderDataContractModal: () => unknown;
    };

    const modal = editorAny._renderDataContractModal();
    expect(modal).toBeDefined();
  });

  it('renders modal template with open state and accessibility attributes', () => {
    const { doc } = createTestDocumentWithChildren();
    const editor = new EpistolaEditor();
    editor.initEngine(doc, testRegistry());

    const editorAny = editor as unknown as {
      dataContractOptions: unknown;
      _dataContractOpen: boolean;
      _renderDataContractModal: () => unknown;
    };
    editorAny.dataContractOptions = createDataContractOptions();
    editorAny._dataContractOpen = true;

    const modal = editorAny._renderDataContractModal();
    expect(modal).toBeDefined();

    const renderSource = String(editorAny._renderDataContractModal);
    expect(renderSource).toContain('role="dialog"');
    expect(renderSource).toContain('aria-modal');
    expect(renderSource).toContain('aria-labelledby="editor-data-contract-title"');
  });
});
