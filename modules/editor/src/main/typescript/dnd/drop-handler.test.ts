import { describe, expect, it } from 'vitest'
import { handleDrop } from './drop-handler.js'
import { EditorEngine } from '../engine/EditorEngine.js'
import { createTestDocument, createTestDocumentWithChildren, testRegistry } from '../engine/test-helpers.js'

describe('handleDrop', () => {
  it('inserts palette block and selects inserted node', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    const result = handleDrop(engine, { source: 'palette', blockType: 'text' }, rootSlotId, -1)

    expect(result.ok).toBe(true)
    expect(result.insertedNodeId).toBeDefined()
    if (result.insertedNodeId) {
      expect(engine.selectedNodeId).toBe(result.insertedNodeId)
      expect(engine.doc.slots[rootSlotId].children).toContain(result.insertedNodeId)
    }
  })

  it('returns error when palette insert is rejected', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    const first = handleDrop(engine, { source: 'palette', blockType: 'pageheader' }, rootSlotId, -1)
    expect(first.ok).toBe(true)

    const second = handleDrop(engine, { source: 'palette', blockType: 'pageheader' }, rootSlotId, -1)
    expect(second.ok).toBe(false)
    expect(second.error).toContain("Only 1 'pageheader' block allowed per document")
  })

  it('moves block drag data between slots', () => {
    const registry = testRegistry()
    const { doc, textNodeId, rootSlotId, containerSlotId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const result = handleDrop(
      engine,
      { source: 'block', nodeId: textNodeId, blockType: 'text' },
      containerSlotId,
      0,
    )

    expect(result.ok).toBe(true)
    expect(engine.doc.slots[rootSlotId].children).not.toContain(textNodeId)
    expect(engine.doc.slots[containerSlotId].children[0]).toBe(textNodeId)
  })

  it('returns error when moving anchored page block', () => {
    const registry = testRegistry()
    const { doc, rootSlotId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const footer = registry.createNode('pagefooter')
    const insert = engine.dispatch({
      type: 'InsertNode',
      node: footer.node,
      slots: footer.slots,
      targetSlotId: rootSlotId,
      index: -1,
    })
    expect(insert.ok).toBe(true)

    const result = handleDrop(
      engine,
      { source: 'block', nodeId: footer.node.id, blockType: 'pagefooter' },
      rootSlotId,
      0,
    )

    expect(result.ok).toBe(false)
    expect(result.error).toContain('cannot be moved')
  })
})
