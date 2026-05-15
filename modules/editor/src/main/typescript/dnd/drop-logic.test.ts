import { describe, it, expect, beforeEach } from 'vitest';
import {
  resolveDropOnBlockEdge,
  resolveDropOnEmptySlot,
  resolveDropInsideNode,
  canDropHere,
} from './drop-logic.js';
import type { DragData } from './types.js';
import { buildIndexes } from '../engine/indexes.js';
import {
  createTestDocumentWithChildren,
  testRegistry,
  resetCounter,
  nodeId,
  slotId,
} from '../engine/test-helpers.js';
import type { NodeId, SlotId, TemplateDocument, Node, Slot } from '../types/index.js';
import type { ComponentRegistry } from '../engine/registry.js';

beforeEach(() => {
  resetCounter();
});

// ---------------------------------------------------------------------------
// resolveDropOnBlockEdge
// ---------------------------------------------------------------------------

describe('resolveDropOnBlockEdge', () => {
  it('returns slot and index=blockIndex for top edge', () => {
    const { doc, rootSlotId, textNodeId } = createTestDocumentWithChildren();
    const indexes = buildIndexes(doc);

    const result = resolveDropOnBlockEdge(textNodeId, 'top', doc, indexes);

    expect(result).toEqual({ targetSlotId: rootSlotId, index: 0 });
  });

  it('returns slot and index=blockIndex+1 for bottom edge', () => {
    const { doc, rootSlotId, textNodeId } = createTestDocumentWithChildren();
    const indexes = buildIndexes(doc);

    const result = resolveDropOnBlockEdge(textNodeId, 'bottom', doc, indexes);

    expect(result).toEqual({ targetSlotId: rootSlotId, index: 1 });
  });

  it('returns correct index for second child (container)', () => {
    const { doc, rootSlotId, containerNodeId } = createTestDocumentWithChildren();
    const indexes = buildIndexes(doc);

    const topResult = resolveDropOnBlockEdge(containerNodeId, 'top', doc, indexes);
    expect(topResult).toEqual({ targetSlotId: rootSlotId, index: 1 });

    const bottomResult = resolveDropOnBlockEdge(containerNodeId, 'bottom', doc, indexes);
    expect(bottomResult).toEqual({ targetSlotId: rootSlotId, index: 2 });
  });

  it('returns null for root node (no parent slot)', () => {
    const { doc, rootId } = createTestDocumentWithChildren();
    const indexes = buildIndexes(doc);

    const result = resolveDropOnBlockEdge(rootId, 'top', doc, indexes);

    expect(result).toBeNull();
  });

  it('returns null for unknown node', () => {
    const { doc } = createTestDocumentWithChildren();
    const indexes = buildIndexes(doc);

    const result = resolveDropOnBlockEdge('nonexistent' as NodeId, 'top', doc, indexes);

    expect(result).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// resolveDropOnEmptySlot
// ---------------------------------------------------------------------------

describe('resolveDropOnEmptySlot', () => {
  it('returns index 0 for any slot', () => {
    const id = slotId('some-slot');
    expect(resolveDropOnEmptySlot(id)).toEqual({ targetSlotId: id, index: 0 });
  });
});

// ---------------------------------------------------------------------------
// resolveDropInsideNode
// ---------------------------------------------------------------------------

describe('resolveDropInsideNode', () => {
  it('returns first slot with index at end for container with children', () => {
    const { doc, containerNodeId, containerSlotId } = createTestDocumentWithChildren();

    // Add a child to the container slot so we can verify append index
    const childId = nodeId('child-in-container');
    doc.nodes[childId] = { id: childId, type: 'text', slots: [], props: { content: null } };
    doc.slots[containerSlotId].children.push(childId);

    const result = resolveDropInsideNode(containerNodeId, doc);

    expect(result).toEqual({ targetSlotId: containerSlotId, index: 1 });
  });

  it('returns first slot with index 0 for empty container', () => {
    const { doc, containerNodeId, containerSlotId } = createTestDocumentWithChildren();

    const result = resolveDropInsideNode(containerNodeId, doc);

    expect(result).toEqual({ targetSlotId: containerSlotId, index: 0 });
  });

  it('returns null for leaf node (no slots)', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren();

    const result = resolveDropInsideNode(textNodeId, doc);

    expect(result).toBeNull();
  });

  it('returns null for unknown node', () => {
    const { doc } = createTestDocumentWithChildren();

    const result = resolveDropInsideNode('nonexistent' as NodeId, doc);

    expect(result).toBeNull();
  });

  it('returns first slot when node has multiple slots', () => {
    const rootId = nodeId('root');
    const rSlotId = slotId('root-slot');
    const columnsId = nodeId('columns');
    const slot1Id = slotId('col-slot-1');
    const slot2Id = slotId('col-slot-2');
    const childId = nodeId('child-text');

    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rSlotId] },
        [columnsId]: { id: columnsId, type: 'columns', slots: [slot1Id, slot2Id] },
        [childId]: { id: childId, type: 'text', slots: [], props: { content: null } },
      },
      slots: {
        [rSlotId]: { id: rSlotId, nodeId: rootId, name: 'children', children: [columnsId] },
        [slot1Id]: { id: slot1Id, nodeId: columnsId, name: 'column-1', children: [childId] },
        [slot2Id]: { id: slot2Id, nodeId: columnsId, name: 'column-2', children: [] },
      },
      themeRef: { type: 'inherit' },
    };

    const result = resolveDropInsideNode(columnsId, doc);

    // Should resolve to first slot, appended after existing child
    expect(result).toEqual({ targetSlotId: slot1Id, index: 1 });
  });
});

// ---------------------------------------------------------------------------
// canDropHere
// ---------------------------------------------------------------------------

describe('canDropHere', () => {
  let doc: TemplateDocument;
  let rootSlotId: SlotId;
  let containerNodeId: NodeId;
  let containerSlotId: SlotId;
  let registry: ComponentRegistry;

  beforeEach(() => {
    registry = testRegistry();
    const setup = createTestDocumentWithChildren();
    doc = setup.doc;
    rootSlotId = setup.rootSlotId;
    containerNodeId = setup.containerNodeId;
    containerSlotId = setup.containerSlotId;
  });

  it('allows palette drag of valid type into root slot', () => {
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'text' };

    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(true);
  });

  it('allows palette drag of container into root slot', () => {
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'container' };

    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(true);
  });

  it('rejects palette drag of root type into any slot', () => {
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'root' };

    // root type is denied by the default registry (root's denylist includes 'root')
    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(false);
  });

  it('rejects palette drag of page header into non-root slot', () => {
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'pageheader' };

    expect(canDropHere(dragData, containerSlotId, doc, indexes, registry)).toBe(false);
  });

  it('rejects palette drag of page header when two already exist', () => {
    const header1 = registry.createNode('pageheader');
    const header2 = registry.createNode('pageheader');
    for (const header of [header1, header2]) {
      doc.nodes[header.node.id] = header.node;
      for (const slot of header.slots) {
        doc.slots[slot.id] = slot;
      }
      doc.slots[rootSlotId].children.unshift(header.node.id);
    }

    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'pageheader' };

    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(false);
  });

  it('allows palette drag of a second page header when only one exists', () => {
    const header = registry.createNode('pageheader');
    doc.nodes[header.node.id] = header.node;
    for (const slot of header.slots) {
      doc.slots[slot.id] = slot;
    }
    doc.slots[rootSlotId].children.unshift(header.node.id);

    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'pageheader' };

    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(true);
  });

  it('allows block drag to different slot', () => {
    const { doc: d, textNodeId, containerSlotId: cSlotId } = createTestDocumentWithChildren();
    const indexes = buildIndexes(d);
    const dragData: DragData = { source: 'block', nodeId: textNodeId, blockType: 'text' };

    expect(canDropHere(dragData, cSlotId, d, indexes, registry)).toBe(true);
  });

  it('rejects block drag into its own slot (self-containment)', () => {
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'block', nodeId: containerNodeId, blockType: 'container' };

    // containerSlotId is owned by containerNodeId, so dropping there means dropping into itself
    expect(canDropHere(dragData, containerSlotId, doc, indexes, registry)).toBe(false);
  });

  it('allows block drag of page header into the root slot (reorder)', () => {
    const header = registry.createNode('pageheader');
    doc.nodes[header.node.id] = header.node;
    for (const slot of header.slots) {
      doc.slots[slot.id] = slot;
    }
    doc.slots[rootSlotId].children.unshift(header.node.id);

    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'block', nodeId: header.node.id, blockType: 'pageheader' };

    // Reordering within the root slot is allowed; the move command enforces that the
    // header lands within the header zone.
    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(true);
  });

  it('rejects block drag of page header into a non-root slot', () => {
    const header = registry.createNode('pageheader');
    doc.nodes[header.node.id] = header.node;
    for (const slot of header.slots) {
      doc.slots[slot.id] = slot;
    }
    doc.slots[rootSlotId].children.unshift(header.node.id);

    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'block', nodeId: header.node.id, blockType: 'pageheader' };

    expect(canDropHere(dragData, containerSlotId, doc, indexes, registry)).toBe(false);
  });

  it('rejects block drag into a descendant slot', () => {
    // Create a deeper tree: root > container > innerContainer > innerSlot
    const rootId = nodeId('root');
    const rSlotId = slotId('root-slot');
    const outerId = nodeId('outer');
    const outerSlotId = slotId('outer-slot');
    const innerId = nodeId('inner');
    const innerSlotId = slotId('inner-slot');

    const deepDoc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rSlotId] },
        [outerId]: { id: outerId, type: 'container', slots: [outerSlotId] },
        [innerId]: { id: innerId, type: 'container', slots: [innerSlotId] },
      },
      slots: {
        [rSlotId]: { id: rSlotId, nodeId: rootId, name: 'children', children: [outerId] },
        [outerSlotId]: { id: outerSlotId, nodeId: outerId, name: 'children', children: [innerId] },
        [innerSlotId]: { id: innerSlotId, nodeId: innerId, name: 'children', children: [] },
      },
      themeRef: { type: 'inherit' },
    };

    const indexes = buildIndexes(deepDoc);
    // Try to drag outer into inner's slot (outer > inner, so inner's slot is a descendant)
    const dragData: DragData = { source: 'block', nodeId: outerId, blockType: 'container' };

    expect(canDropHere(dragData, innerSlotId, deepDoc, indexes, registry)).toBe(false);
  });

  it('returns false for nonexistent slot', () => {
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'text' };

    expect(canDropHere(dragData, 'nonexistent' as SlotId, doc, indexes, registry)).toBe(false);
  });

  it('rejects text block type where containment is not allowed', () => {
    // text has allowedChildren: { mode: 'none' }, so nothing can go inside text
    // We need a text node with a slot to test — but text has no slots by default.
    // Instead, test with a custom registry where a type forbids children.
    const indexes = buildIndexes(doc);

    // The text node doesn't have slots, so we can't drop into it.
    // But let's verify canContain logic by checking the registry directly:
    // text type has allowedChildren: { mode: 'none' }
    expect(registry.canContain('text', 'text')).toBe(false);
    expect(registry.canContain('text', 'container')).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// canDropHere — placeholder + stencil structural rules
// ---------------------------------------------------------------------------

describe('canDropHere — placeholder/stencil rules', () => {
  let registry: ComponentRegistry;

  beforeEach(() => {
    // Stencil is normally registered by lib.ts, not createDefaultRegistry().
    // Tests need it registered to exercise the placeholder/stencil rules.
    // Mirror the production `locked` predicate so locked-stencil drop tests
    // see a realistic registration.
    registry = testRegistry();
    registry.register({
      type: 'stencil',
      label: 'Stencil',
      icon: 'puzzle',
      category: 'layout',
      slots: [
        {
          name: 'children',
          locked: (node) =>
            (node.props?.stencilId as string | null | undefined) != null &&
            !((node.props?.isDraft as boolean | undefined) ?? false),
        },
      ],
      allowedChildren: { mode: 'denylist', types: ['stencil'] },
      applicableStyles: 'all',
      inspector: [],
      defaultProps: {},
    });
  });

  /**
   * Build a doc: root → stencil(stencilA, draft) → children slot.
   *
   * Stencil is `isDraft: true` by default so the layout is editable — that's
   * the realistic shape for tests that exercise "drop X into a stencil's
   * children." Published-stencil locks have their own dedicated tests below.
   */
  function docWithOneStencil(
    stencilId = 'stencil-A',
    isDraft = true,
  ): {
    doc: TemplateDocument;
    rootSlotId: SlotId;
    stencilNodeId: NodeId;
    stencilSlotId: SlotId;
  } {
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const stencilNodeId = nodeId('stencil-1');
    const stencilSlotId = slotId('stencil-1-slot');
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
        [stencilNodeId]: {
          id: stencilNodeId,
          type: 'stencil',
          slots: [stencilSlotId],
          props: { stencilId, version: 1, isDraft },
        },
      },
      slots: {
        [rootSlotId]: {
          id: rootSlotId,
          nodeId: rootId,
          name: 'children',
          children: [stencilNodeId],
        },
        [stencilSlotId]: {
          id: stencilSlotId,
          nodeId: stencilNodeId,
          name: 'children',
          children: [],
        },
      },
      themeRef: { type: 'inherit' },
    };
    return { doc, rootSlotId, stencilNodeId, stencilSlotId };
  }

  it('rejects dropping a placeholder into a bare template root (no stencil ancestor)', () => {
    const { doc, rootSlotId } = createTestDocumentWithChildren();
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'placeholder' };
    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(false);
  });

  it('accepts dropping a placeholder into a stencil', () => {
    const { doc, stencilSlotId } = docWithOneStencil();
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'placeholder' };
    expect(canDropHere(dragData, stencilSlotId, doc, indexes, registry)).toBe(true);
  });

  it('rejects dropping a placeholder inside another placeholder fill at definition level', () => {
    // root → stencil → placeholder(p1) → fill slot (target)
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const stencilNodeId = nodeId('stencil');
    const stencilSlotId = slotId('stencil-slot');
    const phNodeId = nodeId('ph');
    const phFillId = slotId('ph-fill');
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
        [stencilNodeId]: {
          id: stencilNodeId,
          type: 'stencil',
          slots: [stencilSlotId],
          props: { stencilId: 'A', version: 1 },
        },
        [phNodeId]: {
          id: phNodeId,
          type: 'placeholder',
          slots: [phFillId],
          props: { name: 'body' },
        },
      },
      slots: {
        [rootSlotId]: {
          id: rootSlotId,
          nodeId: rootId,
          name: 'children',
          children: [stencilNodeId],
        },
        [stencilSlotId]: {
          id: stencilSlotId,
          nodeId: stencilNodeId,
          name: 'children',
          children: [phNodeId],
        },
        [phFillId]: { id: phFillId, nodeId: phNodeId, name: 'fill', children: [] },
      },
      themeRef: { type: 'inherit' },
    };
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'placeholder' };
    expect(canDropHere(dragData, phFillId, doc, indexes, registry)).toBe(false);
  });

  it('rejects moving a stencil into its own ancestor stencil chain (recursion)', () => {
    // root → stencil-outer(A) → fill → stencil-inner(A) — but we will simulate
    // moving the *outer* stencil A into a nested fill of the inner stencil A.
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const outerId = nodeId('outer');
    const outerSlot = slotId('outer-slot');
    const innerId = nodeId('inner');
    const innerSlot = slotId('inner-slot');
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
        [outerId]: {
          id: outerId,
          type: 'stencil',
          slots: [outerSlot],
          // Draft mode so the lock check doesn't fire before the recursion check.
          props: { stencilId: 'A', version: 1, isDraft: true },
        },
        [innerId]: {
          id: innerId,
          type: 'stencil',
          slots: [innerSlot],
          props: { stencilId: 'B', version: 1, isDraft: true },
        },
      },
      slots: {
        [rootSlotId]: { id: rootSlotId, nodeId: rootId, name: 'children', children: [outerId] },
        [outerSlot]: { id: outerSlot, nodeId: outerId, name: 'children', children: [innerId] },
        [innerSlot]: { id: innerSlot, nodeId: innerId, name: 'children', children: [] },
      },
      themeRef: { type: 'inherit' },
    };

    // Try to move a *different* node (would-be stencil A) into innerSlot.
    // Since the outer chain has stencilId 'A', dropping another A would recurse.
    // We simulate by creating a separate phantom block drag carrying stencilId 'A'.
    const phantomA = 'phantomA' as NodeId;
    doc.nodes[phantomA] = {
      id: phantomA,
      type: 'stencil',
      slots: [],
      props: { stencilId: 'A', version: 1, isDraft: true },
    };
    doc.slots[rootSlotId].children.push(phantomA);
    const indexes2 = buildIndexes(doc);

    const dragData: DragData = {
      source: 'block',
      nodeId: phantomA,
      blockType: 'stencil',
    };
    // Inner slot's ancestors: inner(B), outer(A), root. So dropping A here recurses.
    expect(canDropHere(dragData, innerSlot, doc, indexes2, registry)).toBe(false);
  });

  it('rejects palette drops into a published stencils children slot (locked layout)', () => {
    const { doc, stencilSlotId } = docWithOneStencil('A');
    // Mark the stencil as published (not draft).
    doc.nodes['stencil-1'] = {
      ...doc.nodes['stencil-1'],
      props: { ...doc.nodes['stencil-1'].props, isDraft: false },
    };
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'text' };
    expect(canDropHere(dragData, stencilSlotId, doc, indexes, registry)).toBe(false);
  });

  it('accepts palette drops into a draft stencils children slot', () => {
    const { doc, stencilSlotId } = docWithOneStencil('A');
    doc.nodes['stencil-1'] = {
      ...doc.nodes['stencil-1'],
      props: { ...doc.nodes['stencil-1'].props, isDraft: true },
    };
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'text' };
    expect(canDropHere(dragData, stencilSlotId, doc, indexes, registry)).toBe(true);
  });

  it('rejects drops deeper inside a published stencils layout (e.g. into a column inside the stencil)', () => {
    // root → stencil(published) → columns → column-0 (target).
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const stencilNodeId = nodeId('stencil');
    const stencilSlotId = slotId('stencil-slot');
    const columnsId = nodeId('columns');
    const colSlotId = slotId('col-0');
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
        [stencilNodeId]: {
          id: stencilNodeId,
          type: 'stencil',
          slots: [stencilSlotId],
          props: { stencilId: 'A', version: 1, isDraft: false },
        },
        [columnsId]: { id: columnsId, type: 'columns', slots: [colSlotId], props: {} },
      },
      slots: {
        [rootSlotId]: {
          id: rootSlotId,
          nodeId: rootId,
          name: 'children',
          children: [stencilNodeId],
        },
        [stencilSlotId]: {
          id: stencilSlotId,
          nodeId: stencilNodeId,
          name: 'children',
          children: [columnsId],
        },
        [colSlotId]: { id: colSlotId, nodeId: columnsId, name: 'column-0', children: [] },
      },
      themeRef: { type: 'inherit' },
    };
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'text' };
    expect(canDropHere(dragData, colSlotId, doc, indexes, registry)).toBe(false);
  });

  it('accepts drops inside a placeholder fill of a published stencil', () => {
    // root → stencil(published) → placeholder(body) → fill (target).
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const stencilNodeId = nodeId('stencil');
    const stencilSlotId = slotId('stencil-slot');
    const phNodeId = nodeId('ph');
    const phFillId = slotId('ph-fill');
    const doc: TemplateDocument = {
      modelVersion: 1,
      root: rootId,
      nodes: {
        [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
        [stencilNodeId]: {
          id: stencilNodeId,
          type: 'stencil',
          slots: [stencilSlotId],
          props: { stencilId: 'A', version: 1, isDraft: false },
        },
        [phNodeId]: {
          id: phNodeId,
          type: 'placeholder',
          slots: [phFillId],
          props: { name: 'body' },
        },
      },
      slots: {
        [rootSlotId]: {
          id: rootSlotId,
          nodeId: rootId,
          name: 'children',
          children: [stencilNodeId],
        },
        [stencilSlotId]: {
          id: stencilSlotId,
          nodeId: stencilNodeId,
          name: 'children',
          children: [phNodeId],
        },
        [phFillId]: { id: phFillId, nodeId: phNodeId, name: 'fill', children: [] },
      },
      themeRef: { type: 'inherit' },
    };
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'text' };
    expect(canDropHere(dragData, phFillId, doc, indexes, registry)).toBe(true);
  });

  it('accepts moving a stencil into a placeholder fill (non-recursive)', () => {
    // root → stencil A → placeholder(body) → fill (target).
    // Then create stencil B at root and try to drop it into the placeholder fill.
    const { doc, rootSlotId, stencilSlotId } = docWithOneStencil('A');

    const phNodeId = nodeId('ph-body');
    const phFillId = slotId('ph-body-fill');
    doc.nodes[phNodeId] = {
      id: phNodeId,
      type: 'placeholder',
      slots: [phFillId],
      props: { name: 'body' },
    };
    doc.slots[phFillId] = { id: phFillId, nodeId: phNodeId, name: 'fill', children: [] };
    doc.slots[stencilSlotId].children.push(phNodeId);

    const sB = nodeId('stencil-B');
    const sBSlot = slotId('stencil-B-slot');
    doc.nodes[sB] = {
      id: sB,
      type: 'stencil',
      slots: [sBSlot],
      props: { stencilId: 'B', version: 1 },
    };
    doc.slots[sBSlot] = { id: sBSlot, nodeId: sB, name: 'children', children: [] };
    doc.slots[rootSlotId].children.push(sB);
    const indexes = buildIndexes(doc);

    const dragData: DragData = { source: 'block', nodeId: sB, blockType: 'stencil' };
    expect(canDropHere(dragData, phFillId, doc, indexes, registry)).toBe(true);
  });
});
