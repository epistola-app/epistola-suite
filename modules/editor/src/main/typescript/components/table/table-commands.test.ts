import { describe, it, expect, beforeEach } from 'vitest'
import { EditorEngine } from '../../engine/EditorEngine.js'
import { createDefaultRegistry } from '../../engine/registry.js'
import { createTestDocument, resetCounter } from '../../engine/test-helpers.js'
import type { NodeId, SlotId } from '../../types/index.js'
import { cellSlotName } from './table-utils.js'

beforeEach(() => {
  resetCounter()
})

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function setupTableEngine(overrideProps?: Record<string, unknown>) {
  const registry = createDefaultRegistry()
  const doc = createTestDocument()
  const engine = new EditorEngine(doc, registry)
  const rootSlotId = doc.nodes[doc.root].slots[0]

  const { node: tableNode, slots: tableSlots } = registry.createNode('table', overrideProps)
  engine.dispatch({
    type: 'InsertNode',
    node: tableNode,
    slots: tableSlots,
    targetSlotId: rootSlotId,
    index: -1,
  })

  return { engine, registry, tableNodeId: tableNode.id, rootSlotId }
}

function getSlotByName(engine: EditorEngine, nodeId: NodeId, slotName: string) {
  const node = engine.doc.nodes[nodeId]
  for (const slotId of node.slots) {
    const slot = engine.doc.slots[slotId]
    if (slot?.name === slotName) return slot
  }
  return undefined
}

function getTableProps(engine: EditorEngine, nodeId: NodeId) {
  const node = engine.doc.nodes[nodeId]
  const props = node.props ?? {}
  return {
    rows: (props.rows as number) ?? 0,
    columns: (props.columns as number) ?? 0,
    columnWidths: (props.columnWidths as number[]) ?? [],
    merges: (props.merges as unknown[]) ?? [],
    headerRows: (props.headerRows as number) ?? 0,
  }
}

// ---------------------------------------------------------------------------
// Table creation
// ---------------------------------------------------------------------------

describe('Table creation', () => {
  it('creates a 2x2 table with default props', () => {
    const { engine, tableNodeId } = setupTableEngine()
    const tp = getTableProps(engine, tableNodeId)

    expect(tp.rows).toBe(2)
    expect(tp.columns).toBe(2)
    expect(tp.columnWidths).toEqual([50, 50])
    expect(tp.merges).toEqual([])
    expect(tp.headerRows).toBe(0)

    // Verify 4 cell slots exist
    for (let r = 0; r < 2; r++) {
      for (let c = 0; c < 2; c++) {
        expect(getSlotByName(engine, tableNodeId, cellSlotName(r, c))).toBeDefined()
      }
    }
  })

  it('creates a table with custom dimensions', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 3,
      columns: 4,
      columnWidths: [25, 25, 25, 25],
    })
    const tp = getTableProps(engine, tableNodeId)

    expect(tp.rows).toBe(3)
    expect(tp.columns).toBe(4)

    // 12 cell slots
    const node = engine.doc.nodes[tableNodeId]
    expect(node.slots).toHaveLength(12)
  })
})

// ---------------------------------------------------------------------------
// AddTableRow
// ---------------------------------------------------------------------------

describe('AddTableRow', () => {
  it('adds a row at the end', () => {
    const { engine, tableNodeId } = setupTableEngine()

    const result = engine.dispatch({
      type: 'AddTableRow',
      nodeId: tableNodeId,
      position: 2,
    })

    expect(result.ok).toBe(true)
    const tp = getTableProps(engine, tableNodeId)
    expect(tp.rows).toBe(3)

    // New cells exist
    expect(getSlotByName(engine, tableNodeId, 'cell-2-0')).toBeDefined()
    expect(getSlotByName(engine, tableNodeId, 'cell-2-1')).toBeDefined()
  })

  it('adds a row at position 0 (top)', () => {
    const { engine, tableNodeId } = setupTableEngine()

    const result = engine.dispatch({
      type: 'AddTableRow',
      nodeId: tableNodeId,
      position: 0,
    })

    expect(result.ok).toBe(true)
    const tp = getTableProps(engine, tableNodeId)
    expect(tp.rows).toBe(3)

    // Row 0 is the new row, old row 0 is now row 1
    expect(getSlotByName(engine, tableNodeId, 'cell-0-0')).toBeDefined()
    expect(getSlotByName(engine, tableNodeId, 'cell-0-1')).toBeDefined()
    expect(getSlotByName(engine, tableNodeId, 'cell-1-0')).toBeDefined()
    expect(getSlotByName(engine, tableNodeId, 'cell-2-0')).toBeDefined()
  })

  it('adds a row in the middle', () => {
    const { engine, tableNodeId } = setupTableEngine()

    const result = engine.dispatch({
      type: 'AddTableRow',
      nodeId: tableNodeId,
      position: 1,
    })

    expect(result.ok).toBe(true)
    const tp = getTableProps(engine, tableNodeId)
    expect(tp.rows).toBe(3)

    // All 3 rows * 2 cols = 6 slots
    const node = engine.doc.nodes[tableNodeId]
    expect(node.slots).toHaveLength(6)
  })

  it('undo removes the added row', () => {
    const { engine, tableNodeId } = setupTableEngine()

    engine.dispatch({
      type: 'AddTableRow',
      nodeId: tableNodeId,
      position: 2,
    })
    expect(getTableProps(engine, tableNodeId).rows).toBe(3)

    engine.undo()
    expect(getTableProps(engine, tableNodeId).rows).toBe(2)
  })

  it('undo/redo round-trip preserves state', () => {
    const { engine, tableNodeId } = setupTableEngine()

    engine.dispatch({
      type: 'AddTableRow',
      nodeId: tableNodeId,
      position: 1,
    })

    engine.undo()
    engine.redo()

    expect(getTableProps(engine, tableNodeId).rows).toBe(3)
  })
})

// ---------------------------------------------------------------------------
// RemoveTableRow
// ---------------------------------------------------------------------------

describe('RemoveTableRow', () => {
  it('removes the last row', () => {
    const { engine, tableNodeId } = setupTableEngine({ rows: 3, columns: 2, columnWidths: [50, 50] })

    const result = engine.dispatch({
      type: 'RemoveTableRow',
      nodeId: tableNodeId,
      position: 2,
    })

    expect(result.ok).toBe(true)
    expect(getTableProps(engine, tableNodeId).rows).toBe(2)
    expect(getSlotByName(engine, tableNodeId, 'cell-2-0')).toBeUndefined()
  })

  it('removes the first row and shifts others up', () => {
    const { engine, tableNodeId } = setupTableEngine({ rows: 3, columns: 2, columnWidths: [50, 50] })

    engine.dispatch({
      type: 'RemoveTableRow',
      nodeId: tableNodeId,
      position: 0,
    })

    expect(getTableProps(engine, tableNodeId).rows).toBe(2)
    // Old row 1 is now row 0
    expect(getSlotByName(engine, tableNodeId, 'cell-0-0')).toBeDefined()
    expect(getSlotByName(engine, tableNodeId, 'cell-1-0')).toBeDefined()
  })

  it('rejects removing the last remaining row', () => {
    const { engine, tableNodeId } = setupTableEngine({ rows: 1, columns: 2, columnWidths: [50, 50] })

    const result = engine.dispatch({
      type: 'RemoveTableRow',
      nodeId: tableNodeId,
      position: 0,
    })

    expect(result.ok).toBe(false)
  })

  it('undo restores removed row with children', () => {
    const { engine, registry, tableNodeId } = setupTableEngine()

    // Insert a text node into cell-1-0
    const cellSlot = getSlotByName(engine, tableNodeId, 'cell-1-0')!
    const { node: textNode, slots: textSlots } = registry.createNode('text')
    engine.dispatch({
      type: 'InsertNode',
      node: textNode,
      slots: textSlots,
      targetSlotId: cellSlot.id,
      index: -1,
    })
    expect(engine.doc.nodes[textNode.id]).toBeDefined()

    // Remove row 1
    engine.dispatch({
      type: 'RemoveTableRow',
      nodeId: tableNodeId,
      position: 1,
    })
    expect(engine.doc.nodes[textNode.id]).toBeUndefined()

    // Undo restores
    engine.undo()
    expect(getTableProps(engine, tableNodeId).rows).toBe(2)
    expect(engine.doc.nodes[textNode.id]).toBeDefined()
  })
})

// ---------------------------------------------------------------------------
// AddTableColumn
// ---------------------------------------------------------------------------

describe('AddTableColumn', () => {
  it('adds a column at the end', () => {
    const { engine, tableNodeId } = setupTableEngine()

    const result = engine.dispatch({
      type: 'AddTableColumn',
      nodeId: tableNodeId,
      position: 2,
      width: 30,
    })

    expect(result.ok).toBe(true)
    const tp = getTableProps(engine, tableNodeId)
    expect(tp.columns).toBe(3)
    expect(tp.columnWidths).toEqual([50, 50, 30])

    // New cells exist
    expect(getSlotByName(engine, tableNodeId, 'cell-0-2')).toBeDefined()
    expect(getSlotByName(engine, tableNodeId, 'cell-1-2')).toBeDefined()
  })

  it('adds a column at the start', () => {
    const { engine, tableNodeId } = setupTableEngine()

    engine.dispatch({
      type: 'AddTableColumn',
      nodeId: tableNodeId,
      position: 0,
      width: 25,
    })

    const tp = getTableProps(engine, tableNodeId)
    expect(tp.columns).toBe(3)
    expect(tp.columnWidths).toEqual([25, 50, 50])
  })

  it('undo removes the added column', () => {
    const { engine, tableNodeId } = setupTableEngine()

    engine.dispatch({
      type: 'AddTableColumn',
      nodeId: tableNodeId,
      position: 2,
      width: 30,
    })

    engine.undo()

    const tp = getTableProps(engine, tableNodeId)
    expect(tp.columns).toBe(2)
    expect(tp.columnWidths).toEqual([50, 50])
  })
})

// ---------------------------------------------------------------------------
// RemoveTableColumn
// ---------------------------------------------------------------------------

describe('RemoveTableColumn', () => {
  it('removes the last column', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 2, columns: 3, columnWidths: [30, 40, 30],
    })

    engine.dispatch({
      type: 'RemoveTableColumn',
      nodeId: tableNodeId,
      position: 2,
    })

    const tp = getTableProps(engine, tableNodeId)
    expect(tp.columns).toBe(2)
    expect(tp.columnWidths).toEqual([30, 40])
  })

  it('rejects removing the last remaining column', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 2, columns: 1, columnWidths: [100],
    })

    const result = engine.dispatch({
      type: 'RemoveTableColumn',
      nodeId: tableNodeId,
      position: 0,
    })

    expect(result.ok).toBe(false)
  })

  it('undo restores removed column', () => {
    const { engine, tableNodeId } = setupTableEngine()

    engine.dispatch({
      type: 'RemoveTableColumn',
      nodeId: tableNodeId,
      position: 1,
    })
    expect(getTableProps(engine, tableNodeId).columns).toBe(1)

    engine.undo()
    expect(getTableProps(engine, tableNodeId).columns).toBe(2)
    expect(getTableProps(engine, tableNodeId).columnWidths).toEqual([50, 50])
  })
})

// ---------------------------------------------------------------------------
// MergeTableCells / UnmergeTableCells
// ---------------------------------------------------------------------------

describe('MergeTableCells', () => {
  it('merges a 2x2 region', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 3, columns: 3, columnWidths: [33, 34, 33],
    })

    const result = engine.dispatch({
      type: 'MergeTableCells',
      nodeId: tableNodeId,
      startRow: 0,
      startCol: 0,
      endRow: 1,
      endCol: 1,
    })

    expect(result.ok).toBe(true)
    const tp = getTableProps(engine, tableNodeId)
    expect(tp.merges).toEqual([
      { row: 0, col: 0, rowSpan: 2, colSpan: 2 },
    ])
  })

  it('rejects merging a single cell', () => {
    const { engine, tableNodeId } = setupTableEngine()

    const result = engine.dispatch({
      type: 'MergeTableCells',
      nodeId: tableNodeId,
      startRow: 0,
      startCol: 0,
      endRow: 0,
      endCol: 0,
    })

    expect(result.ok).toBe(false)
  })

  it('undo (unmerge) removes the merge', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 3, columns: 3, columnWidths: [33, 34, 33],
    })

    engine.dispatch({
      type: 'MergeTableCells',
      nodeId: tableNodeId,
      startRow: 0,
      startCol: 0,
      endRow: 1,
      endCol: 1,
    })

    engine.undo()
    const tp = getTableProps(engine, tableNodeId)
    expect(tp.merges).toEqual([])
  })
})

describe('UnmergeTableCells', () => {
  it('removes a merge', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 3, columns: 3, columnWidths: [33, 34, 33],
    })

    engine.dispatch({
      type: 'MergeTableCells',
      nodeId: tableNodeId,
      startRow: 0,
      startCol: 0,
      endRow: 1,
      endCol: 1,
    })

    engine.dispatch({
      type: 'UnmergeTableCells',
      nodeId: tableNodeId,
      row: 0,
      col: 0,
    })

    const tp = getTableProps(engine, tableNodeId)
    expect(tp.merges).toEqual([])
  })

  it('rejects unmerging non-existent merge', () => {
    const { engine, tableNodeId } = setupTableEngine()

    const result = engine.dispatch({
      type: 'UnmergeTableCells',
      nodeId: tableNodeId,
      row: 0,
      col: 0,
    })

    expect(result.ok).toBe(false)
  })

  it('undo restores the merge', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 3, columns: 3, columnWidths: [33, 34, 33],
    })

    engine.dispatch({
      type: 'MergeTableCells',
      nodeId: tableNodeId,
      startRow: 0,
      startCol: 0,
      endRow: 1,
      endCol: 1,
    })

    engine.dispatch({
      type: 'UnmergeTableCells',
      nodeId: tableNodeId,
      row: 0,
      col: 0,
    })

    engine.undo()
    const tp = getTableProps(engine, tableNodeId)
    expect(tp.merges).toHaveLength(1)
    expect(tp.merges[0]).toEqual({ row: 0, col: 0, rowSpan: 2, colSpan: 2 })
  })
})

// ---------------------------------------------------------------------------
// SetTableHeaderRows
// ---------------------------------------------------------------------------

describe('SetTableHeaderRows', () => {
  it('sets header rows', () => {
    const { engine, tableNodeId } = setupTableEngine()

    engine.dispatch({
      type: 'SetTableHeaderRows',
      nodeId: tableNodeId,
      headerRows: 1,
    })

    expect(getTableProps(engine, tableNodeId).headerRows).toBe(1)
  })

  it('undo restores previous header rows', () => {
    const { engine, tableNodeId } = setupTableEngine()

    engine.dispatch({
      type: 'SetTableHeaderRows',
      nodeId: tableNodeId,
      headerRows: 2,
    })

    engine.undo()
    expect(getTableProps(engine, tableNodeId).headerRows).toBe(0)
  })
})

// ---------------------------------------------------------------------------
// Row/column operations with merges
// ---------------------------------------------------------------------------

describe('Row/column operations shift merges correctly', () => {
  it('adding a row shifts merges below the insertion point', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 3, columns: 3, columnWidths: [33, 34, 33],
    })

    // Create a merge at row 2
    engine.dispatch({
      type: 'MergeTableCells',
      nodeId: tableNodeId,
      startRow: 2,
      startCol: 0,
      endRow: 2,
      endCol: 1,
    })

    // Insert row at position 1
    engine.dispatch({
      type: 'AddTableRow',
      nodeId: tableNodeId,
      position: 1,
    })

    const tp = getTableProps(engine, tableNodeId)
    // Merge should have shifted from row 2 to row 3
    expect(tp.merges).toEqual([
      { row: 3, col: 0, rowSpan: 1, colSpan: 2 },
    ])
  })

  it('removing a header row adjusts headerRows count', () => {
    const { engine, tableNodeId } = setupTableEngine({
      rows: 3, columns: 2, columnWidths: [50, 50],
    })

    engine.dispatch({
      type: 'SetTableHeaderRows',
      nodeId: tableNodeId,
      headerRows: 2,
    })

    engine.dispatch({
      type: 'RemoveTableRow',
      nodeId: tableNodeId,
      position: 0,
    })

    expect(getTableProps(engine, tableNodeId).headerRows).toBe(1)
  })
})
