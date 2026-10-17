// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
import { createStencilDefinition } from '../components/stencil/stencil-registration.js';
import { StencilEditingState } from '../components/stencil/editing-state.js';

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

  function addBand(type: 'pageheader' | 'pagefooter', target: SlotId) {
    const band = registry.createNode(type);
    doc.nodes[band.node.id] = band.node;
    for (const slot of band.slots) {
      doc.slots[slot.id] = slot;
    }
    doc.slots[target].children.unshift(band.node.id);
    return band;
  }

  it('allows palette drags of page headers and footers into any slot, however many exist', () => {
    addBand('pageheader', rootSlotId);
    addBand('pageheader', rootSlotId);
    addBand('pagefooter', rootSlotId);
    const indexes = buildIndexes(doc);

    for (const blockType of ['pageheader', 'pagefooter']) {
      const dragData: DragData = { source: 'palette', blockType };
      expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(true);
      expect(canDropHere(dragData, containerSlotId, doc, indexes, registry)).toBe(true);
    }
  });

  it('rejects palette drags of page headers and footers into a page header or footer', () => {
    const footer = addBand('pagefooter', rootSlotId);
    const indexes = buildIndexes(doc);

    for (const blockType of ['pageheader', 'pagefooter']) {
      const dragData: DragData = { source: 'palette', blockType };
      expect(canDropHere(dragData, footer.slots[0].id, doc, indexes, registry)).toBe(false);
    }
    // Ordinary content still goes into a footer.
    expect(
      canDropHere(
        { source: 'palette', blockType: 'text' },
        footer.slots[0].id,
        doc,
        indexes,
        registry,
      ),
    ).toBe(true);
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

  it('allows block drags of page headers and footers anywhere, including the footer', () => {
    const header = addBand('pageheader', rootSlotId);
    const footer = addBand('pagefooter', rootSlotId);
    const indexes = buildIndexes(doc);

    for (const band of [header, footer]) {
      const dragData: DragData = {
        source: 'block',
        nodeId: band.node.id,
        blockType: band.node.type,
      };
      expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(true);
      expect(canDropHere(dragData, containerSlotId, doc, indexes, registry)).toBe(true);
    }
  });

  it('rejects a block drag that would put a page header or footer inside another', () => {
    const header = addBand('pageheader', rootSlotId);
    const footer = addBand('pagefooter', containerSlotId);
    const indexes = buildIndexes(doc);

    const footerDrag: DragData = {
      source: 'block',
      nodeId: footer.node.id,
      blockType: 'pagefooter',
    };
    const containerDrag: DragData = {
      source: 'block',
      nodeId: containerNodeId,
      blockType: 'container',
    };
    expect(canDropHere(footerDrag, header.slots[0].id, doc, indexes, registry)).toBe(false);
    expect(canDropHere(containerDrag, header.slots[0].id, doc, indexes, registry)).toBe(false);
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
  let editing: StencilEditingState;

  beforeEach(() => {
    // Stencil is normally registered by lib.ts, not createDefaultRegistry().
    // An instance's content is locked unless the author edits the stencil
    // (`editing`), exactly as in production.
    registry = testRegistry();
    editing = new StencilEditingState();
    registry.register(createStencilDefinition({ callbacks: null, editing }));
  });

  /**
   * Build a doc: root → stencil(stencilA, being edited) → children slot.
   *
   * The stencil is being edited by default so the layout is editable — that's
   * the realistic shape for tests that exercise "drop X into a stencil's
   * children." Locks of a used stencil have their own dedicated tests below.
   */
  function docWithOneStencil(
    stencilKey = 'stencil-A',
    beingEdited = true,
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
          props: { stencilKey },
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
    if (beingEdited) editing.start(stencilNodeId);
    return { doc, rootSlotId, stencilNodeId, stencilSlotId };
  }

  /**
   * root → stencil-0 → … → stencil-(depth-1) → container (target slot), every
   * stencil being edited. The target is the innermost stencil's own content,
   * so it is lexically inside all [depth] stencils. (A placeholder fill would
   * not be: a fill is written outside the stencil that owns the placeholder.)
   */
  function docWithStencilDepth(depth: number): {
    doc: TemplateDocument;
    targetFillSlotId: SlotId;
  } {
    const rootId = nodeId('root');
    const rootSlotId = slotId('root-slot');
    const targetContainerId = nodeId('target-container');
    const targetFillSlotId = slotId('target-slot');
    const nodes: Record<NodeId, Node> = {
      [rootId]: { id: rootId, type: 'root', slots: [rootSlotId] },
      [targetContainerId]: { id: targetContainerId, type: 'container', slots: [targetFillSlotId] },
    };
    const slots: Record<SlotId, Slot> = {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootId,
        name: 'children',
        children: [nodeId('stencil-0')],
      },
      [targetFillSlotId]: {
        id: targetFillSlotId,
        nodeId: targetContainerId,
        name: 'children',
        children: [],
      },
    };

    for (let index = 0; index < depth; index += 1) {
      const stencilNodeId = nodeId(`stencil-${index}`);
      const stencilSlotId = slotId(`stencil-${index}-slot`);
      const nextChild = index === depth - 1 ? targetContainerId : nodeId(`stencil-${index + 1}`);
      nodes[stencilNodeId] = {
        id: stencilNodeId,
        type: 'stencil',
        slots: [stencilSlotId],
        props: { stencilKey: `stencil-${index}` },
      };
      editing.start(stencilNodeId);
      slots[stencilSlotId] = {
        id: stencilSlotId,
        nodeId: stencilNodeId,
        name: 'children',
        children: [nextChild],
      };
    }

    return {
      doc: {
        modelVersion: 1,
        root: rootId,
        nodes,
        slots,
        themeRef: { type: 'inherit' },
      },
      targetFillSlotId,
    };
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
          props: { stencilKey: 'A' },
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
          // Being edited so the lock check doesn't fire before the recursion check.
          props: { stencilKey: 'A' },
        },
        [innerId]: {
          id: innerId,
          type: 'stencil',
          slots: [innerSlot],
          props: { stencilKey: 'B' },
        },
      },
      slots: {
        [rootSlotId]: { id: rootSlotId, nodeId: rootId, name: 'children', children: [outerId] },
        [outerSlot]: { id: outerSlot, nodeId: outerId, name: 'children', children: [innerId] },
        [innerSlot]: { id: innerSlot, nodeId: innerId, name: 'children', children: [] },
      },
      themeRef: { type: 'inherit' },
    };

    editing.start(outerId);
    editing.start(innerId);
    // Try to move a *different* node (an instance of A) into innerSlot.
    // innerSlot is the content of B inside the content of A, so another A would recurse.
    const phantomA = 'phantomA' as NodeId;
    doc.nodes[phantomA] = {
      id: phantomA,
      type: 'stencil',
      slots: [],
      props: { stencilKey: 'A' },
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

  it('rejects palette drops into a used stencils children slot (locked layout)', () => {
    const { doc, stencilSlotId } = docWithOneStencil('A', false);
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'text' };
    expect(canDropHere(dragData, stencilSlotId, doc, indexes, registry)).toBe(false);
  });

  it('accepts palette drops into the children slot of a stencil being edited', () => {
    const { doc, stencilSlotId } = docWithOneStencil('A');
    const indexes = buildIndexes(doc);
    const dragData: DragData = { source: 'palette', blockType: 'text' };
    expect(canDropHere(dragData, stencilSlotId, doc, indexes, registry)).toBe(true);
  });

  it('rejects drops deeper inside a used stencils layout (e.g. into a column inside the stencil)', () => {
    // root → stencil(used) → columns → column-0 (target).
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
          props: { stencilKey: 'A' },
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

  it('accepts drops inside a placeholder fill of a used stencil', () => {
    // root → stencil(used) → placeholder(body) → fill (target).
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
          props: { stencilKey: 'A' },
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
      props: { stencilKey: 'B' },
    };
    doc.slots[sBSlot] = { id: sBSlot, nodeId: sB, name: 'children', children: [] };
    doc.slots[rootSlotId].children.push(sB);
    const indexes = buildIndexes(doc);

    const dragData: DragData = { source: 'block', nodeId: sB, blockType: 'stencil' };
    expect(canDropHere(dragData, phFillId, doc, indexes, registry)).toBe(true);
  });

  it('allows inserting the fifth stencil level', () => {
    const { doc, targetFillSlotId } = docWithStencilDepth(4);
    const dragData: DragData = { source: 'palette', blockType: 'stencil' };

    expect(canDropHere(dragData, targetFillSlotId, doc, buildIndexes(doc), registry)).toBe(true);
  });

  it('rejects inserting a sixth stencil level', () => {
    const { doc, targetFillSlotId } = docWithStencilDepth(5);
    const dragData: DragData = { source: 'palette', blockType: 'stencil' };

    expect(canDropHere(dragData, targetFillSlotId, doc, buildIndexes(doc), registry)).toBe(false);
  });

  it('rejects moving a two-level stencil subtree below four stencil ancestors', () => {
    const { doc, targetFillSlotId } = docWithStencilDepth(4);
    const rootSlotId = doc.nodes[doc.root].slots[0];
    const outerId = nodeId('moving-outer');
    const outerSlotId = slotId('moving-outer-slot');
    const innerId = nodeId('moving-inner');
    const innerSlotId = slotId('moving-inner-slot');
    doc.nodes[outerId] = {
      id: outerId,
      type: 'stencil',
      slots: [outerSlotId],
      props: { stencilKey: 'moving-outer' },
    };
    doc.nodes[innerId] = {
      id: innerId,
      type: 'stencil',
      slots: [innerSlotId],
      props: { stencilKey: 'moving-inner' },
    };
    doc.slots[outerSlotId] = {
      id: outerSlotId,
      nodeId: outerId,
      name: 'children',
      children: [innerId],
    };
    doc.slots[innerSlotId] = {
      id: innerSlotId,
      nodeId: innerId,
      name: 'children',
      children: [],
    };
    doc.slots[rootSlotId].children.push(outerId);
    const dragData: DragData = { source: 'block', nodeId: outerId, blockType: 'stencil' };

    expect(canDropHere(dragData, targetFillSlotId, doc, buildIndexes(doc), registry)).toBe(false);
  });
});
