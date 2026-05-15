import { describe, expect, it } from 'vitest';
import { handleDrop } from './drop-handler.js';
import { EditorEngine } from '../engine/EditorEngine.js';
import {
  createTestDocument,
  createTestDocumentWithChildren,
  testRegistry,
} from '../engine/test-helpers.js';

describe('handleDrop', () => {
  it('inserts palette block and selects inserted node', () => {
    const registry = testRegistry();
    const doc = createTestDocument();
    const engine = new EditorEngine(doc, registry);
    const rootSlotId = doc.nodes[doc.root].slots[0];
    const previousChildren = engine.doc.slots[rootSlotId].children.length;

    handleDrop(engine, { source: 'palette', blockType: 'text' }, rootSlotId, -1);

    expect(engine.selectedNodeId).toBeTruthy();
    expect(engine.doc.slots[rootSlotId].children).toHaveLength(previousChildren + 1);
    expect(engine.doc.slots[rootSlotId].children).toContain(engine.selectedNodeId!);
  });

  it('keeps document unchanged when palette insert is rejected', () => {
    const registry = testRegistry();
    const doc = createTestDocument();
    const engine = new EditorEngine(doc, registry);
    const rootSlotId = doc.nodes[doc.root].slots[0];

    // First and second pageheader drops both succeed (max = 2 for the first-page variant).
    handleDrop(engine, { source: 'palette', blockType: 'pageheader' }, rootSlotId, -1);
    handleDrop(engine, { source: 'palette', blockType: 'pageheader' }, rootSlotId, -1);
    const headerCountAfterTwo = engine.doc.slots[rootSlotId].children
      .map((id) => engine.doc.nodes[id])
      .filter((node) => node?.type === 'pageheader').length;
    expect(headerCountAfterTwo).toBe(2);

    // A third pageheader drop is rejected.
    handleDrop(engine, { source: 'palette', blockType: 'pageheader' }, rootSlotId, -1);
    const headerCountAfterThird = engine.doc.slots[rootSlotId].children
      .map((id) => engine.doc.nodes[id])
      .filter((node) => node?.type === 'pageheader').length;
    expect(headerCountAfterThird).toBe(2);
  });

  it('moves block drag data between slots', () => {
    const registry = testRegistry();
    const { doc, textNodeId, rootSlotId, containerSlotId } = createTestDocumentWithChildren();
    const engine = new EditorEngine(doc, registry);

    handleDrop(
      engine,
      { source: 'block', nodeId: textNodeId, blockType: 'text' },
      containerSlotId,
      0,
    );

    expect(engine.doc.slots[rootSlotId].children).not.toContain(textNodeId);
    expect(engine.doc.slots[containerSlotId].children[0]).toBe(textNodeId);
  });

  it('swaps two page headers when the first is dropped past the second via DnD', () => {
    const registry = testRegistry();
    const doc = createTestDocument();
    const engine = new EditorEngine(doc, registry);
    const rootSlotId = doc.nodes[doc.root].slots[0];

    // Add two pageheaders via palette (each lands at the end of the header zone).
    handleDrop(engine, { source: 'palette', blockType: 'pageheader' }, rootSlotId, -1);
    handleDrop(engine, { source: 'palette', blockType: 'pageheader' }, rootSlotId, -1);

    const childrenBefore = engine.doc.slots[rootSlotId].children;
    const header1 = childrenBefore[0]!; // first-page variant
    const header2 = childrenBefore[1]!; // running variant

    // Drop header1 just past header2. The drop UI uses original-list coords:
    // header2 is at index 1, so the "after header2" edge yields index 2.
    // handleDrop must convert this to the filtered-list index expected by
    // applyMoveNode, producing the swap.
    handleDrop(
      engine,
      { source: 'block', nodeId: header1, blockType: 'pageheader' },
      rootSlotId,
      2,
    );

    const childrenAfter = engine.doc.slots[rootSlotId].children;
    expect(childrenAfter[0]).toBe(header2);
    expect(childrenAfter[1]).toBe(header1);
  });

  it('keeps anchored page block in place when move is rejected', () => {
    const registry = testRegistry();
    const { doc, rootSlotId } = createTestDocumentWithChildren();
    const engine = new EditorEngine(doc, registry);

    const footer = registry.createNode('pagefooter');
    const insert = engine.dispatch({
      type: 'InsertNode',
      node: footer.node,
      slots: footer.slots,
      targetSlotId: rootSlotId,
      index: -1,
    });
    expect(insert.ok).toBe(true);
    const childrenBeforeMove = [...engine.doc.slots[rootSlotId].children];

    handleDrop(
      engine,
      { source: 'block', nodeId: footer.node.id, blockType: 'pagefooter' },
      rootSlotId,
      0,
    );

    expect(engine.doc.slots[rootSlotId].children).toEqual(childrenBeforeMove);
    expect(
      engine.doc.slots[rootSlotId].children[engine.doc.slots[rootSlotId].children.length - 1],
    ).toBe(footer.node.id);
  });
});
