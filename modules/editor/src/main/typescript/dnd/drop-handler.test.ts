import { describe, expect, it } from "vitest";
import { handleDrop } from "./drop-handler.js";
import { EditorEngine } from "../engine/EditorEngine.js";
import {
  createTestDocument,
  createTestDocumentWithChildren,
  testRegistry,
} from "../engine/test-helpers.js";

describe("handleDrop", () => {
  it("inserts palette block and selects inserted node", () => {
    const registry = testRegistry();
    const doc = createTestDocument();
    const engine = new EditorEngine(doc, registry);
    const rootSlotId = doc.nodes[doc.root].slots[0];
    const previousChildren = engine.doc.slots[rootSlotId].children.length;

    handleDrop(engine, { source: "palette", blockType: "text" }, rootSlotId, -1);

    expect(engine.selectedNodeId).toBeTruthy();
    expect(engine.doc.slots[rootSlotId].children).toHaveLength(previousChildren + 1);
    expect(engine.doc.slots[rootSlotId].children).toContain(engine.selectedNodeId!);
  });

  it("keeps document unchanged when palette insert is rejected", () => {
    const registry = testRegistry();
    const doc = createTestDocument();
    const engine = new EditorEngine(doc, registry);
    const rootSlotId = doc.nodes[doc.root].slots[0];

    handleDrop(engine, { source: "palette", blockType: "pageheader" }, rootSlotId, -1);
    const headerCountAfterFirst = engine.doc.slots[rootSlotId].children
      .map((id) => engine.doc.nodes[id])
      .filter((node) => node?.type === "pageheader").length;
    expect(headerCountAfterFirst).toBe(1);

    handleDrop(engine, { source: "palette", blockType: "pageheader" }, rootSlotId, -1);
    const headerCountAfterSecond = engine.doc.slots[rootSlotId].children
      .map((id) => engine.doc.nodes[id])
      .filter((node) => node?.type === "pageheader").length;
    expect(headerCountAfterSecond).toBe(1);
  });

  it("moves block drag data between slots", () => {
    const registry = testRegistry();
    const { doc, textNodeId, rootSlotId, containerSlotId } = createTestDocumentWithChildren();
    const engine = new EditorEngine(doc, registry);

    handleDrop(
      engine,
      { source: "block", nodeId: textNodeId, blockType: "text" },
      containerSlotId,
      0,
    );

    expect(engine.doc.slots[rootSlotId].children).not.toContain(textNodeId);
    expect(engine.doc.slots[containerSlotId].children[0]).toBe(textNodeId);
  });

  it("keeps anchored page block in place when move is rejected", () => {
    const registry = testRegistry();
    const { doc, rootSlotId } = createTestDocumentWithChildren();
    const engine = new EditorEngine(doc, registry);

    const footer = registry.createNode("pagefooter");
    const insert = engine.dispatch({
      type: "InsertNode",
      node: footer.node,
      slots: footer.slots,
      targetSlotId: rootSlotId,
      index: -1,
    });
    expect(insert.ok).toBe(true);
    const childrenBeforeMove = [...engine.doc.slots[rootSlotId].children];

    handleDrop(
      engine,
      { source: "block", nodeId: footer.node.id, blockType: "pagefooter" },
      rootSlotId,
      0,
    );

    expect(engine.doc.slots[rootSlotId].children).toEqual(childrenBeforeMove);
    expect(
      engine.doc.slots[rootSlotId].children[engine.doc.slots[rootSlotId].children.length - 1],
    ).toBe(footer.node.id);
  });
});
