import { describe, it, expect, beforeEach } from 'vitest'
import { resolveDropOnBlockEdge, resolveDropOnEmptySlot, resolveDropInsideNode, canDropHere } from './drop-logic.js'
import type { DragData } from './types.js'
import { buildIndexes } from '../engine/indexes.js'
import {
  createTestDocumentWithChildren,
  testRegistry,
  resetCounter,
  nodeId,
  slotId,
} from '../engine/test-helpers.js'
import type { NodeId, SlotId, TemplateDocument, Node, Slot } from '../types/index.js'
import type { ComponentRegistry } from '../engine/registry.js'

beforeEach(() => {
  resetCounter()
})

// ---------------------------------------------------------------------------
// resolveDropOnBlockEdge
// ---------------------------------------------------------------------------

describe('resolveDropOnBlockEdge', () => {
  it('returns slot and index=blockIndex for top edge', () => {
    const { doc, rootSlotId, textNodeId } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)

    const result = resolveDropOnBlockEdge(textNodeId, 'top', doc, indexes)

    expect(result).toEqual({ targetSlotId: rootSlotId, index: 0 })
  })

  it('returns slot and index=blockIndex+1 for bottom edge', () => {
    const { doc, rootSlotId, textNodeId } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)

    const result = resolveDropOnBlockEdge(textNodeId, 'bottom', doc, indexes)

    expect(result).toEqual({ targetSlotId: rootSlotId, index: 1 })
  })

  it('returns correct index for second child (container)', () => {
    const { doc, rootSlotId, containerNodeId } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)

    const topResult = resolveDropOnBlockEdge(containerNodeId, 'top', doc, indexes)
    expect(topResult).toEqual({ targetSlotId: rootSlotId, index: 1 })

    const bottomResult = resolveDropOnBlockEdge(containerNodeId, 'bottom', doc, indexes)
    expect(bottomResult).toEqual({ targetSlotId: rootSlotId, index: 2 })
  })

  it('returns null for root node (no parent slot)', () => {
    const { doc, rootId } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)

    const result = resolveDropOnBlockEdge(rootId, 'top', doc, indexes)

    expect(result).toBeNull()
  })

  it('returns null for unknown node', () => {
    const { doc } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)

    const result = resolveDropOnBlockEdge('nonexistent' as NodeId, 'top', doc, indexes)

    expect(result).toBeNull()
  })
})

// ---------------------------------------------------------------------------
// resolveDropOnEmptySlot
// ---------------------------------------------------------------------------

describe('resolveDropOnEmptySlot', () => {
  it('returns index 0 for any slot', () => {
    const id = slotId('some-slot')
    expect(resolveDropOnEmptySlot(id)).toEqual({ targetSlotId: id, index: 0 })
  })
})

// ---------------------------------------------------------------------------
// resolveDropInsideNode
// ---------------------------------------------------------------------------

describe('resolveDropInsideNode', () => {
  it('returns first slot with index at end for container with children', () => {
    const { doc, containerNodeId, containerSlotId } = createTestDocumentWithChildren()

    // Add a child to the container slot so we can verify append index
    const childId = nodeId('child-in-container')
    doc.nodes[childId] = { id: childId, type: 'text', slots: [], props: { content: null } }
    doc.slots[containerSlotId].children.push(childId)

    const result = resolveDropInsideNode(containerNodeId, doc)

    expect(result).toEqual({ targetSlotId: containerSlotId, index: 1 })
  })

  it('returns first slot with index 0 for empty container', () => {
    const { doc, containerNodeId, containerSlotId } = createTestDocumentWithChildren()

    const result = resolveDropInsideNode(containerNodeId, doc)

    expect(result).toEqual({ targetSlotId: containerSlotId, index: 0 })
  })

  it('returns null for leaf node (no slots)', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren()

    const result = resolveDropInsideNode(textNodeId, doc)

    expect(result).toBeNull()
  })

  it('returns null for unknown node', () => {
    const { doc } = createTestDocumentWithChildren()

    const result = resolveDropInsideNode('nonexistent' as NodeId, doc)

    expect(result).toBeNull()
  })

  it('returns first slot when node has multiple slots', () => {
    const rootId = nodeId('root')
    const rSlotId = slotId('root-slot')
    const columnsId = nodeId('columns')
    const slot1Id = slotId('col-slot-1')
    const slot2Id = slotId('col-slot-2')
    const childId = nodeId('child-text')

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
    }

    const result = resolveDropInsideNode(columnsId, doc)

    // Should resolve to first slot, appended after existing child
    expect(result).toEqual({ targetSlotId: slot1Id, index: 1 })
  })
})

// ---------------------------------------------------------------------------
// canDropHere
// ---------------------------------------------------------------------------

describe('canDropHere', () => {
  let doc: TemplateDocument
  let rootSlotId: SlotId
  let containerNodeId: NodeId
  let containerSlotId: SlotId
  let registry: ComponentRegistry

  beforeEach(() => {
    registry = testRegistry()
    const setup = createTestDocumentWithChildren()
    doc = setup.doc
    rootSlotId = setup.rootSlotId
    containerNodeId = setup.containerNodeId
    containerSlotId = setup.containerSlotId
  })

  it('allows palette drag of valid type into root slot', () => {
    const indexes = buildIndexes(doc)
    const dragData: DragData = { source: 'palette', blockType: 'text' }

    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(true)
  })

  it('allows palette drag of container into root slot', () => {
    const indexes = buildIndexes(doc)
    const dragData: DragData = { source: 'palette', blockType: 'container' }

    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(true)
  })

  it('rejects palette drag of root type into any slot', () => {
    const indexes = buildIndexes(doc)
    const dragData: DragData = { source: 'palette', blockType: 'root' }

    // root type is denied by the default registry (root's denylist includes 'root')
    expect(canDropHere(dragData, rootSlotId, doc, indexes, registry)).toBe(false)
  })

  it('allows block drag to different slot', () => {
    const { doc: d, textNodeId, containerSlotId: cSlotId } = createTestDocumentWithChildren()
    const indexes = buildIndexes(d)
    const dragData: DragData = { source: 'block', nodeId: textNodeId, blockType: 'text' }

    expect(canDropHere(dragData, cSlotId, d, indexes, registry)).toBe(true)
  })

  it('rejects block drag into its own slot (self-containment)', () => {
    const indexes = buildIndexes(doc)
    const dragData: DragData = { source: 'block', nodeId: containerNodeId, blockType: 'container' }

    // containerSlotId is owned by containerNodeId, so dropping there means dropping into itself
    expect(canDropHere(dragData, containerSlotId, doc, indexes, registry)).toBe(false)
  })

  it('rejects block drag into a descendant slot', () => {
    // Create a deeper tree: root > container > innerContainer > innerSlot
    const rootId = nodeId('root')
    const rSlotId = slotId('root-slot')
    const outerId = nodeId('outer')
    const outerSlotId = slotId('outer-slot')
    const innerId = nodeId('inner')
    const innerSlotId = slotId('inner-slot')

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
    }

    const indexes = buildIndexes(deepDoc)
    // Try to drag outer into inner's slot (outer > inner, so inner's slot is a descendant)
    const dragData: DragData = { source: 'block', nodeId: outerId, blockType: 'container' }

    expect(canDropHere(dragData, innerSlotId, deepDoc, indexes, registry)).toBe(false)
  })

  it('returns false for nonexistent slot', () => {
    const indexes = buildIndexes(doc)
    const dragData: DragData = { source: 'palette', blockType: 'text' }

    expect(canDropHere(dragData, 'nonexistent' as SlotId, doc, indexes, registry)).toBe(false)
  })

  it('rejects text block type where containment is not allowed', () => {
    // text has allowedChildren: { mode: 'none' }, so nothing can go inside text
    // We need a text node with a slot to test — but text has no slots by default.
    // Instead, test with a custom registry where a type forbids children.
    const indexes = buildIndexes(doc)

    // The text node doesn't have slots, so we can't drop into it.
    // But let's verify canContain logic by checking the registry directly:
    // text type has allowedChildren: { mode: 'none' }
    expect(registry.canContain('text', 'text')).toBe(false)
    expect(registry.canContain('text', 'container')).toBe(false)
  })
})
