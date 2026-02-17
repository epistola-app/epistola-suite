import { describe, it, expect, beforeEach } from 'vitest'
import { EditorEngine } from '../../engine/EditorEngine.js'
import { createDefaultRegistry } from '../../engine/registry.js'
import { createTestDocument, resetCounter } from '../../engine/test-helpers.js'
import type { NodeId, SlotId } from '../../types/index.js'

beforeEach(() => {
  resetCounter()
})

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function setupDatatableEngine(columnCount = 3) {
  const registry = createDefaultRegistry()
  const doc = createTestDocument()
  const engine = new EditorEngine(doc, registry)
  const rootSlotId = doc.nodes[doc.root].slots[0]

  const { node, slots, extraNodes } = registry.createNode('datatable', { _columnCount: columnCount })
  engine.dispatch({
    type: 'InsertNode',
    node,
    slots,
    targetSlotId: rootSlotId,
    index: -1,
    _restoreNodes: extraNodes,
  })

  return { engine, registry, datatableNodeId: node.id, rootSlotId }
}

function getColumnsSlot(engine: EditorEngine, datatableNodeId: NodeId) {
  const node = engine.doc.nodes[datatableNodeId]
  const slotId = node.slots[0]
  return engine.doc.slots[slotId]
}

function getColumnNodes(engine: EditorEngine, datatableNodeId: NodeId) {
  const columnsSlot = getColumnsSlot(engine, datatableNodeId)
  return columnsSlot.children.map(id => engine.doc.nodes[id]).filter(Boolean)
}

// ---------------------------------------------------------------------------
// createNode subtree creation
// ---------------------------------------------------------------------------

describe('Datatable createNode subtree', () => {
  it('produces correct subtree with 3 columns', () => {
    const registry = createDefaultRegistry()
    const { node, slots, extraNodes } = registry.createNode('datatable', { _columnCount: 3 })

    expect(node.type).toBe('datatable')
    expect(node.slots).toHaveLength(1) // "columns" slot

    // Should have 3 extra nodes (column nodes)
    expect(extraNodes).toBeDefined()
    expect(extraNodes).toHaveLength(3)

    // Each extra node is a datatable-column with one slot
    for (const colNode of extraNodes!) {
      expect(colNode.type).toBe('datatable-column')
      expect(colNode.slots).toHaveLength(1)
    }

    // slots = 1 columns slot + 3 body slots = 4 total
    expect(slots).toHaveLength(4)
  })

  it('columns slot has 3 child node IDs', () => {
    const registry = createDefaultRegistry()
    const { slots, extraNodes } = registry.createNode('datatable', { _columnCount: 3 })

    // First slot is the "columns" slot
    const columnsSlot = slots[0]
    expect(columnsSlot.name).toBe('columns')
    expect(columnsSlot.children).toHaveLength(3)

    // Children should match the extraNodes IDs
    const extraNodeIds = extraNodes!.map(n => n.id)
    expect(columnsSlot.children).toEqual(extraNodeIds)
  })

  it('each column has a body slot', () => {
    const registry = createDefaultRegistry()
    const { slots, extraNodes } = registry.createNode('datatable', { _columnCount: 2 })

    for (const colNode of extraNodes!) {
      const bodySlotId = colNode.slots[0]
      const bodySlot = slots.find(s => s.id === bodySlotId)
      expect(bodySlot).toBeDefined()
      expect(bodySlot!.name).toBe('body')
      expect(bodySlot!.nodeId).toBe(colNode.id)
      expect(bodySlot!.children).toEqual([])
    }
  })

  it('column widths are evenly distributed', () => {
    const registry = createDefaultRegistry()
    const { extraNodes } = registry.createNode('datatable', { _columnCount: 4 })

    for (const colNode of extraNodes!) {
      expect(colNode.props?.width).toBe(25) // 100/4
    }
  })

  it('default column count is 3 when no _columnCount provided', () => {
    const registry = createDefaultRegistry()
    const { extraNodes } = registry.createNode('datatable')

    expect(extraNodes).toHaveLength(3)
  })
})

// ---------------------------------------------------------------------------
// InsertNode with subtree
// ---------------------------------------------------------------------------

describe('Datatable InsertNode', () => {
  it('adds all nodes and slots atomically', () => {
    const { engine, datatableNodeId } = setupDatatableEngine(3)

    // Datatable node exists
    expect(engine.doc.nodes[datatableNodeId]).toBeDefined()

    // 3 column nodes exist
    const columns = getColumnNodes(engine, datatableNodeId)
    expect(columns).toHaveLength(3)

    // Each column's body slot exists
    for (const col of columns) {
      const bodySlotId = col.slots[0]
      expect(engine.doc.slots[bodySlotId]).toBeDefined()
    }
  })

  it('RemoveNode removes entire subtree', () => {
    const { engine, datatableNodeId } = setupDatatableEngine(3)

    const columns = getColumnNodes(engine, datatableNodeId)
    const columnIds = columns.map(c => c.id)
    const bodySlotIds = columns.map(c => c.slots[0])

    engine.dispatch({ type: 'RemoveNode', nodeId: datatableNodeId })

    // Everything should be gone
    expect(engine.doc.nodes[datatableNodeId]).toBeUndefined()
    for (const id of columnIds) {
      expect(engine.doc.nodes[id]).toBeUndefined()
    }
    for (const id of bodySlotIds) {
      expect(engine.doc.slots[id]).toBeUndefined()
    }
  })

  it('undo of RemoveNode restores entire subtree', () => {
    const { engine, datatableNodeId } = setupDatatableEngine(3)

    const columns = getColumnNodes(engine, datatableNodeId)
    const columnIds = columns.map(c => c.id)

    engine.dispatch({ type: 'RemoveNode', nodeId: datatableNodeId })
    engine.undo()

    // Everything should be restored
    expect(engine.doc.nodes[datatableNodeId]).toBeDefined()
    for (const id of columnIds) {
      expect(engine.doc.nodes[id]).toBeDefined()
    }

    const restoredColumns = getColumnNodes(engine, datatableNodeId)
    expect(restoredColumns).toHaveLength(3)
  })
})

// ---------------------------------------------------------------------------
// Hidden flag
// ---------------------------------------------------------------------------

describe('Hidden flag', () => {
  it('datatable-column is not in insertable()', () => {
    const registry = createDefaultRegistry()
    const insertable = registry.insertable()
    const types = insertable.map(d => d.type)

    expect(types).not.toContain('datatable-column')
  })

  it('datatable IS in insertable()', () => {
    const registry = createDefaultRegistry()
    const insertable = registry.insertable()
    const types = insertable.map(d => d.type)

    expect(types).toContain('datatable')
  })
})

// ---------------------------------------------------------------------------
// canContain
// ---------------------------------------------------------------------------

describe('canContain', () => {
  it('datatable can contain datatable-column', () => {
    const registry = createDefaultRegistry()
    expect(registry.canContain('datatable', 'datatable-column')).toBe(true)
  })

  it('datatable cannot contain text', () => {
    const registry = createDefaultRegistry()
    expect(registry.canContain('datatable', 'text')).toBe(false)
  })

  it('datatable cannot contain container', () => {
    const registry = createDefaultRegistry()
    expect(registry.canContain('datatable', 'container')).toBe(false)
  })

  it('datatable-column can contain text', () => {
    const registry = createDefaultRegistry()
    expect(registry.canContain('datatable-column', 'text')).toBe(true)
  })

  it('datatable-column can contain container', () => {
    const registry = createDefaultRegistry()
    expect(registry.canContain('datatable-column', 'container')).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// Column management via standard commands
// ---------------------------------------------------------------------------

describe('Column management', () => {
  it('add column via InsertNode', () => {
    const { engine, registry, datatableNodeId } = setupDatatableEngine(2)

    const columnsSlot = getColumnsSlot(engine, datatableNodeId)
    expect(columnsSlot.children).toHaveLength(2)

    // Create a new column node manually
    const { node: colNode, slots: colSlots } = registry.createNode('datatable-column')
    engine.dispatch({
      type: 'InsertNode',
      node: colNode,
      slots: colSlots,
      targetSlotId: columnsSlot.id,
      index: -1,
    })

    const updatedSlot = getColumnsSlot(engine, datatableNodeId)
    expect(updatedSlot.children).toHaveLength(3)
  })

  it('remove column via RemoveNode', () => {
    const { engine, datatableNodeId } = setupDatatableEngine(3)

    const columns = getColumnNodes(engine, datatableNodeId)
    const lastColId = columns[2].id

    engine.dispatch({ type: 'RemoveNode', nodeId: lastColId as NodeId })

    const updatedSlot = getColumnsSlot(engine, datatableNodeId)
    expect(updatedSlot.children).toHaveLength(2)
  })

  it('undo of add column removes it', () => {
    const { engine, registry, datatableNodeId } = setupDatatableEngine(2)

    const columnsSlot = getColumnsSlot(engine, datatableNodeId)
    const { node: colNode, slots: colSlots } = registry.createNode('datatable-column')
    engine.dispatch({
      type: 'InsertNode',
      node: colNode,
      slots: colSlots,
      targetSlotId: columnsSlot.id,
      index: -1,
    })

    engine.undo()

    const updatedSlot = getColumnsSlot(engine, datatableNodeId)
    expect(updatedSlot.children).toHaveLength(2)
  })

  it('undo of remove column restores it', () => {
    const { engine, datatableNodeId } = setupDatatableEngine(3)

    const columns = getColumnNodes(engine, datatableNodeId)
    const lastColId = columns[2].id

    engine.dispatch({ type: 'RemoveNode', nodeId: lastColId as NodeId })
    engine.undo()

    const updatedSlot = getColumnsSlot(engine, datatableNodeId)
    expect(updatedSlot.children).toHaveLength(3)
  })

  it('reorder column via MoveNode', () => {
    const { engine, datatableNodeId } = setupDatatableEngine(3)

    const columns = getColumnNodes(engine, datatableNodeId)
    const firstColId = columns[0].id
    const columnsSlot = getColumnsSlot(engine, datatableNodeId)

    // Move first column to the end
    engine.dispatch({
      type: 'MoveNode',
      nodeId: firstColId as NodeId,
      targetSlotId: columnsSlot.id as SlotId,
      index: 2,
    })

    const updatedSlot = getColumnsSlot(engine, datatableNodeId)
    expect(updatedSlot.children[2]).toBe(firstColId)
  })

  it('inserting non-column type into datatable is rejected', () => {
    const { engine, registry, datatableNodeId } = setupDatatableEngine(2)
    const columnsSlot = getColumnsSlot(engine, datatableNodeId)

    const { node: textNode, slots: textSlots } = registry.createNode('text')
    const result = engine.dispatch({
      type: 'InsertNode',
      node: textNode,
      slots: textSlots,
      targetSlotId: columnsSlot.id,
      index: -1,
    })

    expect(result.ok).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// Content inside columns
// ---------------------------------------------------------------------------

describe('Content inside column body slots', () => {
  it('can insert text into a column body slot', () => {
    const { engine, registry, datatableNodeId } = setupDatatableEngine(2)

    const columns = getColumnNodes(engine, datatableNodeId)
    const bodySlotId = columns[0].slots[0]

    const { node: textNode, slots: textSlots } = registry.createNode('text')
    const result = engine.dispatch({
      type: 'InsertNode',
      node: textNode,
      slots: textSlots,
      targetSlotId: bodySlotId as SlotId,
      index: -1,
    })

    expect(result.ok).toBe(true)
    const bodySlot = engine.doc.slots[bodySlotId]
    expect(bodySlot.children).toHaveLength(1)
    expect(bodySlot.children[0]).toBe(textNode.id)
  })

  it('removing datatable also removes column body content', () => {
    const { engine, registry, datatableNodeId } = setupDatatableEngine(2)

    const columns = getColumnNodes(engine, datatableNodeId)
    const bodySlotId = columns[0].slots[0]

    const { node: textNode, slots: textSlots } = registry.createNode('text')
    engine.dispatch({
      type: 'InsertNode',
      node: textNode,
      slots: textSlots,
      targetSlotId: bodySlotId as SlotId,
      index: -1,
    })

    engine.dispatch({ type: 'RemoveNode', nodeId: datatableNodeId })

    expect(engine.doc.nodes[textNode.id]).toBeUndefined()
  })

  it('undo of datatable removal restores body content too', () => {
    const { engine, registry, datatableNodeId } = setupDatatableEngine(2)

    const columns = getColumnNodes(engine, datatableNodeId)
    const bodySlotId = columns[0].slots[0]

    const { node: textNode, slots: textSlots } = registry.createNode('text')
    engine.dispatch({
      type: 'InsertNode',
      node: textNode,
      slots: textSlots,
      targetSlotId: bodySlotId as SlotId,
      index: -1,
    })

    engine.dispatch({ type: 'RemoveNode', nodeId: datatableNodeId })
    engine.undo()

    expect(engine.doc.nodes[textNode.id]).toBeDefined()
    const restoredSlot = engine.doc.slots[bodySlotId]
    expect(restoredSlot.children).toContain(textNode.id)
  })
})
