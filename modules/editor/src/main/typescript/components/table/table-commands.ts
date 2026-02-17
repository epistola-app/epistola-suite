/**
 * Table-specific command types and their apply implementations.
 *
 * Each command mutates the document immutably and returns an inverse
 * command for undo, following the same pattern as AddColumnSlot/RemoveColumnSlot.
 */

import type { TemplateDocument, Node, Slot, NodeId, SlotId } from '../../types/index.js'
import type { CommandResult, CommandOk, CommandError } from '../../engine/commands.js'
import type { DocumentIndexes } from '../../engine/indexes.js'
import {
  cellSlotName,
  shiftMergesForRowInsert,
  shiftMergesForRowRemove,
  shiftMergesForColInsert,
  shiftMergesForColRemove,
  type CellMerge,
} from './table-utils.js'
import { nanoid } from 'nanoid'

// ---------------------------------------------------------------------------
// Command types
// ---------------------------------------------------------------------------

export interface AddTableRow {
  type: 'AddTableRow'
  nodeId: NodeId
  /** Row index to insert at. Existing rows at this index shift down. */
  position: number
  /** For undo: restored slots keyed by slot name. */
  _restoreSlots?: Slot[]
  /** For undo: restored nodes. */
  _restoreNodes?: Node[]
  /** For undo: restored child slots (slots owned by restored nodes). */
  _restoreChildSlots?: Slot[]
}

export interface RemoveTableRow {
  type: 'RemoveTableRow'
  nodeId: NodeId
  position: number
}

export interface AddTableColumn {
  type: 'AddTableColumn'
  nodeId: NodeId
  /** Column index to insert at. */
  position: number
  /** Width for the new column. */
  width: number
  /** For undo: restored slots. */
  _restoreSlots?: Slot[]
  _restoreNodes?: Node[]
  _restoreChildSlots?: Slot[]
}

export interface RemoveTableColumn {
  type: 'RemoveTableColumn'
  nodeId: NodeId
  position: number
}

export interface MergeTableCells {
  type: 'MergeTableCells'
  nodeId: NodeId
  startRow: number
  startCol: number
  endRow: number
  endCol: number
}

export interface UnmergeTableCells {
  type: 'UnmergeTableCells'
  nodeId: NodeId
  row: number
  col: number
}

export interface SetTableHeaderRows {
  type: 'SetTableHeaderRows'
  nodeId: NodeId
  headerRows: number
}

export type TableCommand =
  | AddTableRow
  | RemoveTableRow
  | AddTableColumn
  | RemoveTableColumn
  | MergeTableCells
  | UnmergeTableCells
  | SetTableHeaderRows

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function ok(doc: TemplateDocument, inverse: TableCommand | null, structureChanged: boolean): CommandOk {
  return { ok: true, doc, inverse, structureChanged }
}

function err(error: string): CommandError {
  return { ok: false, error }
}

function getTableProps(node: Node) {
  const props = node.props ?? {}
  return {
    rows: (props.rows as number) ?? 0,
    columns: (props.columns as number) ?? 0,
    columnWidths: (props.columnWidths as number[]) ?? [],
    merges: (props.merges as CellMerge[]) ?? [],
    headerRows: (props.headerRows as number) ?? 0,
    borderStyle: (props.borderStyle as string) ?? 'all',
  }
}

/** Build a map from slot name → slot for a table node. */
function slotsByName(node: Node, doc: TemplateDocument): Map<string, Slot> {
  const map = new Map<string, Slot>()
  for (const slotId of node.slots) {
    const slot = doc.slots[slotId]
    if (slot) map.set(slot.name, slot)
  }
  return map
}

/** Collect all nodes and slots in a subtree (used for undo restoration). */
function collectSubtree(
  doc: TemplateDocument,
  nodeId: NodeId,
  nodeIds: Set<NodeId>,
  slotIds: Set<SlotId>,
): void {
  nodeIds.add(nodeId)
  const node = doc.nodes[nodeId]
  if (!node) return
  for (const sid of node.slots) {
    slotIds.add(sid)
    const slot = doc.slots[sid]
    if (!slot) continue
    for (const childId of slot.children) {
      collectSubtree(doc, childId, nodeIds, slotIds)
    }
  }
}

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

export function applyTableCommand(
  doc: TemplateDocument,
  _indexes: DocumentIndexes,
  command: TableCommand,
): CommandResult {
  switch (command.type) {
    case 'AddTableRow':
      return applyAddTableRow(doc, command)
    case 'RemoveTableRow':
      return applyRemoveTableRow(doc, command)
    case 'AddTableColumn':
      return applyAddTableColumn(doc, command)
    case 'RemoveTableColumn':
      return applyRemoveTableColumn(doc, command)
    case 'MergeTableCells':
      return applyMergeTableCells(doc, command)
    case 'UnmergeTableCells':
      return applyUnmergeTableCells(doc, command)
    case 'SetTableHeaderRows':
      return applySetTableHeaderRows(doc, command)
  }
}

// ---------------------------------------------------------------------------
// AddTableRow
// ---------------------------------------------------------------------------

function applyAddTableRow(doc: TemplateDocument, cmd: AddTableRow): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)
  if (node.type !== 'table') return err('AddTableRow only applies to table nodes')

  const tp = getTableProps(node)
  if (cmd.position < 0 || cmd.position > tp.rows) {
    return err(`Invalid row position ${cmd.position}`)
  }

  const slotsMap = slotsByName(node, doc)
  const newSlots: Record<SlotId, Slot> = { ...doc.slots }
  const newNodes: Record<NodeId, Node> = { ...doc.nodes }
  const newSlotIds = [...node.slots]

  // Rename existing slots for rows >= position (shift down)
  // We need to go top-to-bottom to avoid conflicts, so rename from highest row first
  for (let r = tp.rows - 1; r >= cmd.position; r--) {
    for (let c = 0; c < tp.columns; c++) {
      const oldName = cellSlotName(r, c)
      const newName = cellSlotName(r + 1, c)
      const slot = slotsMap.get(oldName)
      if (slot) {
        newSlots[slot.id] = { ...slot, name: newName }
      }
    }
  }

  // Create new cell slots for the inserted row (or restore from undo)
  if (cmd._restoreSlots) {
    for (const slot of cmd._restoreSlots) {
      newSlots[slot.id] = structuredClone(slot)
      if (!newSlotIds.includes(slot.id)) {
        newSlotIds.push(slot.id)
      }
    }
    if (cmd._restoreNodes) {
      for (const n of cmd._restoreNodes) newNodes[n.id] = structuredClone(n)
    }
    if (cmd._restoreChildSlots) {
      for (const s of cmd._restoreChildSlots) newSlots[s.id] = structuredClone(s)
    }
  } else {
    for (let c = 0; c < tp.columns; c++) {
      const slotId = nanoid() as SlotId
      newSlotIds.push(slotId)
      newSlots[slotId] = {
        id: slotId,
        nodeId: cmd.nodeId,
        name: cellSlotName(cmd.position, c),
        children: [],
      }
    }
  }

  // Update merges
  const newMerges = shiftMergesForRowInsert(tp.merges, cmd.position)

  newNodes[cmd.nodeId] = {
    ...node,
    slots: newSlotIds,
    props: {
      ...node.props,
      rows: tp.rows + 1,
      merges: newMerges,
    },
  }

  const inverse: RemoveTableRow = {
    type: 'RemoveTableRow',
    nodeId: cmd.nodeId,
    position: cmd.position,
  }

  return ok({ ...doc, nodes: newNodes, slots: newSlots }, inverse, true)
}

// ---------------------------------------------------------------------------
// RemoveTableRow
// ---------------------------------------------------------------------------

function applyRemoveTableRow(doc: TemplateDocument, cmd: RemoveTableRow): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)
  if (node.type !== 'table') return err('RemoveTableRow only applies to table nodes')

  const tp = getTableProps(node)
  if (tp.rows <= 1) return err('Cannot remove the last row')
  if (cmd.position < 0 || cmd.position >= tp.rows) {
    return err(`Invalid row position ${cmd.position}`)
  }

  const slotsMap = slotsByName(node, doc)
  const newSlots: Record<SlotId, Slot> = { ...doc.slots }
  const newNodes: Record<NodeId, Node> = { ...doc.nodes }

  // Collect slots and subtrees to remove for the target row
  const removedSlots: Slot[] = []
  const removedNodes: Node[] = []
  const removedChildSlots: Slot[] = []
  const slotsToRemove = new Set<SlotId>()

  for (let c = 0; c < tp.columns; c++) {
    const slotName = cellSlotName(cmd.position, c)
    const slot = slotsMap.get(slotName)
    if (slot) {
      removedSlots.push(slot)
      slotsToRemove.add(slot.id)

      // Collect subtree of children for undo
      for (const childId of slot.children) {
        const childNodeIds = new Set<NodeId>()
        const childSlotIds = new Set<SlotId>()
        collectSubtree(doc, childId, childNodeIds, childSlotIds)
        for (const nid of childNodeIds) {
          removedNodes.push(doc.nodes[nid])
          delete newNodes[nid]
        }
        for (const sid of childSlotIds) {
          removedChildSlots.push(doc.slots[sid])
          delete newSlots[sid]
        }
      }

      delete newSlots[slot.id]
    }
  }

  // Rename remaining slots: rows > position shift up
  for (let r = cmd.position + 1; r < tp.rows; r++) {
    for (let c = 0; c < tp.columns; c++) {
      const oldName = cellSlotName(r, c)
      const newName = cellSlotName(r - 1, c)
      const slot = slotsMap.get(oldName)
      if (slot && !slotsToRemove.has(slot.id)) {
        newSlots[slot.id] = { ...newSlots[slot.id], name: newName }
      }
    }
  }

  // Remove slot IDs from node
  const newSlotIds = node.slots.filter(id => !slotsToRemove.has(id))

  // Update merges
  const newMerges = shiftMergesForRowRemove(tp.merges, cmd.position)

  // Adjust headerRows if needed
  let newHeaderRows = tp.headerRows
  if (cmd.position < tp.headerRows) {
    newHeaderRows = Math.max(0, tp.headerRows - 1)
  }

  newNodes[cmd.nodeId] = {
    ...node,
    slots: newSlotIds,
    props: {
      ...node.props,
      rows: tp.rows - 1,
      merges: newMerges,
      headerRows: newHeaderRows,
    },
  }

  const inverse: AddTableRow = {
    type: 'AddTableRow',
    nodeId: cmd.nodeId,
    position: cmd.position,
    _restoreSlots: removedSlots,
    _restoreNodes: removedNodes.length > 0 ? removedNodes : undefined,
    _restoreChildSlots: removedChildSlots.length > 0 ? removedChildSlots : undefined,
  }

  return ok({ ...doc, nodes: newNodes, slots: newSlots }, inverse, true)
}

// ---------------------------------------------------------------------------
// AddTableColumn
// ---------------------------------------------------------------------------

function applyAddTableColumn(doc: TemplateDocument, cmd: AddTableColumn): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)
  if (node.type !== 'table') return err('AddTableColumn only applies to table nodes')

  const tp = getTableProps(node)
  if (cmd.position < 0 || cmd.position > tp.columns) {
    return err(`Invalid column position ${cmd.position}`)
  }

  const slotsMap = slotsByName(node, doc)
  const newSlots: Record<SlotId, Slot> = { ...doc.slots }
  const newNodes: Record<NodeId, Node> = { ...doc.nodes }
  const newSlotIds = [...node.slots]

  // Rename existing slots for columns >= position (shift right)
  for (let r = 0; r < tp.rows; r++) {
    for (let c = tp.columns - 1; c >= cmd.position; c--) {
      const oldName = cellSlotName(r, c)
      const newName = cellSlotName(r, c + 1)
      const slot = slotsMap.get(oldName)
      if (slot) {
        newSlots[slot.id] = { ...slot, name: newName }
      }
    }
  }

  // Create new cell slots for the inserted column (or restore)
  if (cmd._restoreSlots) {
    for (const slot of cmd._restoreSlots) {
      newSlots[slot.id] = structuredClone(slot)
      if (!newSlotIds.includes(slot.id)) {
        newSlotIds.push(slot.id)
      }
    }
    if (cmd._restoreNodes) {
      for (const n of cmd._restoreNodes) newNodes[n.id] = structuredClone(n)
    }
    if (cmd._restoreChildSlots) {
      for (const s of cmd._restoreChildSlots) newSlots[s.id] = structuredClone(s)
    }
  } else {
    for (let r = 0; r < tp.rows; r++) {
      const slotId = nanoid() as SlotId
      newSlotIds.push(slotId)
      newSlots[slotId] = {
        id: slotId,
        nodeId: cmd.nodeId,
        name: cellSlotName(r, cmd.position),
        children: [],
      }
    }
  }

  // Update column widths
  const newColumnWidths = [...tp.columnWidths]
  newColumnWidths.splice(cmd.position, 0, cmd.width)

  // Update merges
  const newMerges = shiftMergesForColInsert(tp.merges, cmd.position)

  newNodes[cmd.nodeId] = {
    ...node,
    slots: newSlotIds,
    props: {
      ...node.props,
      columns: tp.columns + 1,
      columnWidths: newColumnWidths,
      merges: newMerges,
    },
  }

  const inverse: RemoveTableColumn = {
    type: 'RemoveTableColumn',
    nodeId: cmd.nodeId,
    position: cmd.position,
  }

  return ok({ ...doc, nodes: newNodes, slots: newSlots }, inverse, true)
}

// ---------------------------------------------------------------------------
// RemoveTableColumn
// ---------------------------------------------------------------------------

function applyRemoveTableColumn(doc: TemplateDocument, cmd: RemoveTableColumn): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)
  if (node.type !== 'table') return err('RemoveTableColumn only applies to table nodes')

  const tp = getTableProps(node)
  if (tp.columns <= 1) return err('Cannot remove the last column')
  if (cmd.position < 0 || cmd.position >= tp.columns) {
    return err(`Invalid column position ${cmd.position}`)
  }

  const slotsMap = slotsByName(node, doc)
  const newSlots: Record<SlotId, Slot> = { ...doc.slots }
  const newNodes: Record<NodeId, Node> = { ...doc.nodes }

  const removedSlots: Slot[] = []
  const removedNodes: Node[] = []
  const removedChildSlots: Slot[] = []
  const slotsToRemove = new Set<SlotId>()
  const removedWidth = tp.columnWidths[cmd.position] ?? 50

  for (let r = 0; r < tp.rows; r++) {
    const slotName = cellSlotName(r, cmd.position)
    const slot = slotsMap.get(slotName)
    if (slot) {
      removedSlots.push(slot)
      slotsToRemove.add(slot.id)

      for (const childId of slot.children) {
        const childNodeIds = new Set<NodeId>()
        const childSlotIds = new Set<SlotId>()
        collectSubtree(doc, childId, childNodeIds, childSlotIds)
        for (const nid of childNodeIds) {
          removedNodes.push(doc.nodes[nid])
          delete newNodes[nid]
        }
        for (const sid of childSlotIds) {
          removedChildSlots.push(doc.slots[sid])
          delete newSlots[sid]
        }
      }

      delete newSlots[slot.id]
    }
  }

  // Rename remaining slots: columns > position shift left
  for (let r = 0; r < tp.rows; r++) {
    for (let c = cmd.position + 1; c < tp.columns; c++) {
      const oldName = cellSlotName(r, c)
      const newName = cellSlotName(r, c - 1)
      const slot = slotsMap.get(oldName)
      if (slot && !slotsToRemove.has(slot.id)) {
        newSlots[slot.id] = { ...newSlots[slot.id], name: newName }
      }
    }
  }

  const newSlotIds = node.slots.filter(id => !slotsToRemove.has(id))
  const newColumnWidths = [...tp.columnWidths]
  newColumnWidths.splice(cmd.position, 1)
  const newMerges = shiftMergesForColRemove(tp.merges, cmd.position)

  newNodes[cmd.nodeId] = {
    ...node,
    slots: newSlotIds,
    props: {
      ...node.props,
      columns: tp.columns - 1,
      columnWidths: newColumnWidths,
      merges: newMerges,
    },
  }

  const inverse: AddTableColumn = {
    type: 'AddTableColumn',
    nodeId: cmd.nodeId,
    position: cmd.position,
    width: removedWidth,
    _restoreSlots: removedSlots,
    _restoreNodes: removedNodes.length > 0 ? removedNodes : undefined,
    _restoreChildSlots: removedChildSlots.length > 0 ? removedChildSlots : undefined,
  }

  return ok({ ...doc, nodes: newNodes, slots: newSlots }, inverse, true)
}

// ---------------------------------------------------------------------------
// MergeTableCells
// ---------------------------------------------------------------------------

function applyMergeTableCells(doc: TemplateDocument, cmd: MergeTableCells): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)
  if (node.type !== 'table') return err('MergeTableCells only applies to table nodes')

  const tp = getTableProps(node)
  const rowSpan = cmd.endRow - cmd.startRow + 1
  const colSpan = cmd.endCol - cmd.startCol + 1
  if (rowSpan <= 1 && colSpan <= 1) return err('Cannot merge a single cell')

  const newMerge: CellMerge = {
    row: cmd.startRow,
    col: cmd.startCol,
    rowSpan,
    colSpan,
  }

  // Remove any merges fully contained within the selection (they get absorbed)
  const newMerges = tp.merges.filter(m => {
    const mEndRow = m.row + m.rowSpan - 1
    const mEndCol = m.col + m.colSpan - 1
    const fullyContained =
      m.row >= cmd.startRow && mEndRow <= cmd.endRow &&
      m.col >= cmd.startCol && mEndCol <= cmd.endCol
    return !fullyContained
  })

  newMerges.push(newMerge)

  // Move content from covered cells to the primary cell
  const slotsMap = slotsByName(node, doc)
  const newSlots: Record<SlotId, Slot> = { ...doc.slots }
  const primarySlotName = cellSlotName(cmd.startRow, cmd.startCol)
  const primarySlot = slotsMap.get(primarySlotName)

  if (primarySlot) {
    const additionalChildren: NodeId[] = []

    for (let r = cmd.startRow; r <= cmd.endRow; r++) {
      for (let c = cmd.startCol; c <= cmd.endCol; c++) {
        if (r === cmd.startRow && c === cmd.startCol) continue
        const coveredSlot = slotsMap.get(cellSlotName(r, c))
        if (coveredSlot && coveredSlot.children.length > 0) {
          additionalChildren.push(...coveredSlot.children)
          newSlots[coveredSlot.id] = { ...coveredSlot, children: [] }
        }
      }
    }

    if (additionalChildren.length > 0) {
      newSlots[primarySlot.id] = {
        ...primarySlot,
        children: [...primarySlot.children, ...additionalChildren],
      }
    }
  }

  const newNodes: Record<NodeId, Node> = {
    ...doc.nodes,
    [cmd.nodeId]: {
      ...node,
      props: { ...node.props, merges: newMerges },
    },
  }

  const inverse: UnmergeTableCells = {
    type: 'UnmergeTableCells',
    nodeId: cmd.nodeId,
    row: cmd.startRow,
    col: cmd.startCol,
  }

  return ok({ ...doc, nodes: newNodes, slots: newSlots }, inverse, false)
}

// ---------------------------------------------------------------------------
// UnmergeTableCells
// ---------------------------------------------------------------------------

function applyUnmergeTableCells(doc: TemplateDocument, cmd: UnmergeTableCells): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)
  if (node.type !== 'table') return err('UnmergeTableCells only applies to table nodes')

  const tp = getTableProps(node)
  const mergeIndex = tp.merges.findIndex(m => m.row === cmd.row && m.col === cmd.col)
  if (mergeIndex === -1) return err(`No merge at (${cmd.row}, ${cmd.col})`)

  const removedMerge = tp.merges[mergeIndex]
  const newMerges = [...tp.merges]
  newMerges.splice(mergeIndex, 1)

  const newNodes: Record<NodeId, Node> = {
    ...doc.nodes,
    [cmd.nodeId]: {
      ...node,
      props: { ...node.props, merges: newMerges },
    },
  }

  const inverse: MergeTableCells = {
    type: 'MergeTableCells',
    nodeId: cmd.nodeId,
    startRow: removedMerge.row,
    startCol: removedMerge.col,
    endRow: removedMerge.row + removedMerge.rowSpan - 1,
    endCol: removedMerge.col + removedMerge.colSpan - 1,
  }

  return ok({ ...doc, nodes: newNodes, slots: doc.slots }, inverse, false)
}

// ---------------------------------------------------------------------------
// SetTableHeaderRows
// ---------------------------------------------------------------------------

function applySetTableHeaderRows(doc: TemplateDocument, cmd: SetTableHeaderRows): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)
  if (node.type !== 'table') return err('SetTableHeaderRows only applies to table nodes')

  const tp = getTableProps(node)
  const inverse: SetTableHeaderRows = {
    type: 'SetTableHeaderRows',
    nodeId: cmd.nodeId,
    headerRows: tp.headerRows,
  }

  const newNodes: Record<NodeId, Node> = {
    ...doc.nodes,
    [cmd.nodeId]: {
      ...node,
      props: { ...node.props, headerRows: cmd.headerRows },
    },
  }

  return ok({ ...doc, nodes: newNodes, slots: doc.slots }, inverse, false)
}
