/**
 * Command definitions and execution for the editor engine.
 *
 * Each command:
 *  1. Validates preconditions
 *  2. Produces a new TemplateDocument (immutable update)
 *  3. Returns an inverse Command for undo
 */

import type { TemplateDocument, Node, Slot, NodeId, SlotId } from '../types/index.js'
import type { DocumentIndexes } from './indexes.js'
import { isAncestor } from './indexes.js'
import type { ComponentRegistry } from './registry.js'

// ---------------------------------------------------------------------------
// Command types
// ---------------------------------------------------------------------------

export type Command =
  | InsertNode
  | RemoveNode
  | MoveNode
  | UpdateNodeProps
  | UpdateNodeStyles
  | SetStylePreset
  | UpdateDocumentStyles
  | UpdatePageSettings

export interface InsertNode {
  type: 'InsertNode'
  /** The new node to insert (with pre-generated ID). */
  node: Node
  /** Slots that belong to this node. */
  slots: Slot[]
  /** Target slot to insert into. */
  targetSlotId: SlotId
  /** Index within the slot's children array. -1 = append. */
  index: number
}

export interface RemoveNode {
  type: 'RemoveNode'
  nodeId: NodeId
}

export interface MoveNode {
  type: 'MoveNode'
  nodeId: NodeId
  targetSlotId: SlotId
  index: number
}

export interface UpdateNodeProps {
  type: 'UpdateNodeProps'
  nodeId: NodeId
  props: Record<string, unknown>
}

export interface UpdateNodeStyles {
  type: 'UpdateNodeStyles'
  nodeId: NodeId
  styles: Record<string, unknown>
}

export interface SetStylePreset {
  type: 'SetStylePreset'
  nodeId: NodeId
  stylePreset: string | undefined
}

export interface UpdateDocumentStyles {
  type: 'UpdateDocumentStyles'
  styles: TemplateDocument['documentStylesOverride']
}

export interface UpdatePageSettings {
  type: 'UpdatePageSettings'
  settings: TemplateDocument['pageSettingsOverride']
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

export type CommandResult = CommandOk | CommandError

export interface CommandOk {
  ok: true
  doc: TemplateDocument
  inverse: Command | null
}

export interface CommandError {
  ok: false
  error: string
}

// ---------------------------------------------------------------------------
// Apply
// ---------------------------------------------------------------------------

export function applyCommand(
  doc: TemplateDocument,
  indexes: DocumentIndexes,
  command: Command,
  registry: ComponentRegistry,
): CommandResult {
  switch (command.type) {
    case 'InsertNode':
      return applyInsertNode(doc, indexes, command, registry)
    case 'RemoveNode':
      return applyRemoveNode(doc, indexes, command)
    case 'MoveNode':
      return applyMoveNode(doc, indexes, command, registry)
    case 'UpdateNodeProps':
      return applyUpdateNodeProps(doc, command)
    case 'UpdateNodeStyles':
      return applyUpdateNodeStyles(doc, command)
    case 'SetStylePreset':
      return applySetStylePreset(doc, command)
    case 'UpdateDocumentStyles':
      return applyUpdateDocumentStyles(doc, command)
    case 'UpdatePageSettings':
      return applyUpdatePageSettings(doc, command)
  }
}

// ---------------------------------------------------------------------------
// InsertNode
// ---------------------------------------------------------------------------

function applyInsertNode(
  doc: TemplateDocument,
  _indexes: DocumentIndexes,
  cmd: InsertNode,
  registry: ComponentRegistry,
): CommandResult {
  const targetSlot = doc.slots[cmd.targetSlotId]
  if (!targetSlot) return err(`Target slot ${cmd.targetSlotId} not found`)

  const parentNode = doc.nodes[targetSlot.nodeId]
  if (!parentNode) return err(`Parent node ${targetSlot.nodeId} not found`)

  // Validate child type allowed
  if (!registry.canContain(parentNode.type, cmd.node.type)) {
    return err(`Node type '${cmd.node.type}' cannot be placed in '${parentNode.type}'`)
  }

  // Validate node doesn't already exist
  if (doc.nodes[cmd.node.id]) {
    return err(`Node ${cmd.node.id} already exists`)
  }

  // Build new document
  const newNodes = { ...doc.nodes, [cmd.node.id]: cmd.node }
  const newSlots = { ...doc.slots }

  // Add the node's own slots
  for (const slot of cmd.slots) {
    newSlots[slot.id] = slot
  }

  // Update target slot's children
  const children = [...targetSlot.children]
  const insertIndex = cmd.index < 0 || cmd.index >= children.length
    ? children.length
    : cmd.index
  children.splice(insertIndex, 0, cmd.node.id)
  newSlots[cmd.targetSlotId] = { ...targetSlot, children }

  const inverse: RemoveNode = {
    type: 'RemoveNode',
    nodeId: cmd.node.id,
  }

  return ok({ ...doc, nodes: newNodes, slots: newSlots }, inverse)
}

// ---------------------------------------------------------------------------
// RemoveNode
// ---------------------------------------------------------------------------

function applyRemoveNode(
  doc: TemplateDocument,
  indexes: DocumentIndexes,
  cmd: RemoveNode,
): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)

  if (cmd.nodeId === doc.root) return err('Cannot remove root node')

  const parentSlotId = indexes.parentSlotByNodeId.get(cmd.nodeId)
  if (!parentSlotId) return err(`Node ${cmd.nodeId} has no parent slot`)

  const parentSlot = doc.slots[parentSlotId]
  if (!parentSlot) return err(`Parent slot ${parentSlotId} not found`)

  // Collect all node IDs and slot IDs to remove (subtree)
  const nodeIdsToRemove = new Set<NodeId>()
  const slotIdsToRemove = new Set<SlotId>()
  collectSubtree(doc, cmd.nodeId, nodeIdsToRemove, slotIdsToRemove)

  // Capture the removed subtree for undo
  const removedNodes: Node[] = []
  const removedSlots: Slot[] = []
  for (const id of nodeIdsToRemove) removedNodes.push(doc.nodes[id])
  for (const id of slotIdsToRemove) removedSlots.push(doc.slots[id])

  // Find index in parent slot
  const indexInParent = parentSlot.children.indexOf(cmd.nodeId)

  // Build new document without the subtree
  const newNodes: Record<NodeId, Node> = {}
  for (const [id, n] of Object.entries(doc.nodes)) {
    if (!nodeIdsToRemove.has(id as NodeId)) {
      newNodes[id as NodeId] = n
    }
  }

  const newSlots: Record<SlotId, Slot> = {}
  for (const [id, s] of Object.entries(doc.slots)) {
    if (!slotIdsToRemove.has(id as SlotId)) {
      newSlots[id as SlotId] = s
    }
  }

  // Update parent slot
  newSlots[parentSlotId] = {
    ...parentSlot,
    children: parentSlot.children.filter(id => id !== cmd.nodeId),
  }

  // Build inverse: re-insert the node (with its subtree) at original position
  const inverse: InsertNode = {
    type: 'InsertNode',
    node: node,
    slots: removedSlots,
    targetSlotId: parentSlotId,
    index: indexInParent,
  }

  return ok({ ...doc, nodes: newNodes, slots: newSlots }, inverse)
}

function collectSubtree(
  doc: TemplateDocument,
  nodeId: NodeId,
  nodeIds: Set<NodeId>,
  slotIds: Set<SlotId>,
): void {
  nodeIds.add(nodeId)
  const node = doc.nodes[nodeId]
  if (!node) return

  for (const slotId of node.slots) {
    slotIds.add(slotId)
    const slot = doc.slots[slotId]
    if (!slot) continue
    for (const childId of slot.children) {
      collectSubtree(doc, childId, nodeIds, slotIds)
    }
  }
}

// ---------------------------------------------------------------------------
// MoveNode
// ---------------------------------------------------------------------------

function applyMoveNode(
  doc: TemplateDocument,
  indexes: DocumentIndexes,
  cmd: MoveNode,
  registry: ComponentRegistry,
): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)

  if (cmd.nodeId === doc.root) return err('Cannot move root node')

  const targetSlot = doc.slots[cmd.targetSlotId]
  if (!targetSlot) return err(`Target slot ${cmd.targetSlotId} not found`)

  const targetParent = doc.nodes[targetSlot.nodeId]
  if (!targetParent) return err(`Target parent node ${targetSlot.nodeId} not found`)

  // Cycle detection: target cannot be a descendant of the node being moved
  if (isAncestor(targetSlot.nodeId, cmd.nodeId, indexes)) {
    return err('Cannot move a node into its own descendant (cycle)')
  }
  if (targetSlot.nodeId === cmd.nodeId) {
    return err('Cannot move a node into itself')
  }

  // Validate child type allowed
  if (!registry.canContain(targetParent.type, node.type)) {
    return err(`Node type '${node.type}' cannot be placed in '${targetParent.type}'`)
  }

  // Find current parent slot
  const currentSlotId = indexes.parentSlotByNodeId.get(cmd.nodeId)
  if (!currentSlotId) return err(`Node ${cmd.nodeId} has no parent slot`)

  const currentSlot = doc.slots[currentSlotId]
  if (!currentSlot) return err(`Current slot ${currentSlotId} not found`)

  const currentIndex = currentSlot.children.indexOf(cmd.nodeId)

  // Build inverse
  const inverse: MoveNode = {
    type: 'MoveNode',
    nodeId: cmd.nodeId,
    targetSlotId: currentSlotId,
    index: currentIndex,
  }

  // Build new document
  const newSlots = { ...doc.slots }

  if (currentSlotId === cmd.targetSlotId) {
    // Moving within the same slot
    const children = currentSlot.children.filter(id => id !== cmd.nodeId)
    const insertIndex = cmd.index < 0 || cmd.index >= children.length
      ? children.length
      : cmd.index
    children.splice(insertIndex, 0, cmd.nodeId)
    newSlots[currentSlotId] = { ...currentSlot, children }
  } else {
    // Moving between slots: remove from source, add to target
    newSlots[currentSlotId] = {
      ...currentSlot,
      children: currentSlot.children.filter(id => id !== cmd.nodeId),
    }

    const targetChildren = [...targetSlot.children]
    const insertIndex = cmd.index < 0 || cmd.index >= targetChildren.length
      ? targetChildren.length
      : cmd.index
    targetChildren.splice(insertIndex, 0, cmd.nodeId)
    newSlots[cmd.targetSlotId] = { ...targetSlot, children: targetChildren }
  }

  return ok({ ...doc, slots: newSlots }, inverse)
}

// ---------------------------------------------------------------------------
// UpdateNodeProps
// ---------------------------------------------------------------------------

function applyUpdateNodeProps(
  doc: TemplateDocument,
  cmd: UpdateNodeProps,
): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)

  const oldProps = node.props ?? {}
  const inverse: UpdateNodeProps = {
    type: 'UpdateNodeProps',
    nodeId: cmd.nodeId,
    props: structuredClone(oldProps),
  }

  const newNode = { ...node, props: structuredClone(cmd.props) }
  const newNodes = { ...doc.nodes, [cmd.nodeId]: newNode }

  return ok({ ...doc, nodes: newNodes }, inverse)
}

// ---------------------------------------------------------------------------
// UpdateNodeStyles
// ---------------------------------------------------------------------------

function applyUpdateNodeStyles(
  doc: TemplateDocument,
  cmd: UpdateNodeStyles,
): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)

  const oldStyles = node.styles ?? {}
  const inverse: UpdateNodeStyles = {
    type: 'UpdateNodeStyles',
    nodeId: cmd.nodeId,
    styles: structuredClone(oldStyles),
  }

  const newNode = { ...node, styles: structuredClone(cmd.styles) }
  const newNodes = { ...doc.nodes, [cmd.nodeId]: newNode }

  return ok({ ...doc, nodes: newNodes }, inverse)
}

// ---------------------------------------------------------------------------
// SetStylePreset
// ---------------------------------------------------------------------------

function applySetStylePreset(
  doc: TemplateDocument,
  cmd: SetStylePreset,
): CommandResult {
  const node = doc.nodes[cmd.nodeId]
  if (!node) return err(`Node ${cmd.nodeId} not found`)

  const inverse: SetStylePreset = {
    type: 'SetStylePreset',
    nodeId: cmd.nodeId,
    stylePreset: node.stylePreset,
  }

  const newNode = { ...node, stylePreset: cmd.stylePreset }
  const newNodes = { ...doc.nodes, [cmd.nodeId]: newNode }

  return ok({ ...doc, nodes: newNodes }, inverse)
}

// ---------------------------------------------------------------------------
// UpdateDocumentStyles
// ---------------------------------------------------------------------------

function applyUpdateDocumentStyles(
  doc: TemplateDocument,
  cmd: UpdateDocumentStyles,
): CommandResult {
  const inverse: UpdateDocumentStyles = {
    type: 'UpdateDocumentStyles',
    styles: doc.documentStylesOverride
      ? structuredClone(doc.documentStylesOverride)
      : undefined,
  }

  return ok(
    { ...doc, documentStylesOverride: cmd.styles ? structuredClone(cmd.styles) : undefined },
    inverse,
  )
}

// ---------------------------------------------------------------------------
// UpdatePageSettings
// ---------------------------------------------------------------------------

function applyUpdatePageSettings(
  doc: TemplateDocument,
  cmd: UpdatePageSettings,
): CommandResult {
  const inverse: UpdatePageSettings = {
    type: 'UpdatePageSettings',
    settings: doc.pageSettingsOverride
      ? structuredClone(doc.pageSettingsOverride)
      : undefined,
  }

  return ok(
    { ...doc, pageSettingsOverride: cmd.settings ? structuredClone(cmd.settings) : undefined },
    inverse,
  )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function ok(doc: TemplateDocument, inverse: Command | null): CommandOk {
  return { ok: true, doc, inverse }
}

function err(error: string): CommandError {
  return { ok: false, error }
}
