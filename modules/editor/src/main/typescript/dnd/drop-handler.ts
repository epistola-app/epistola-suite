/**
 * Shared drop execution logic.
 *
 * Translates a DragData + target slot + index into an engine command
 * (InsertNode or MoveNode). Used by all DnD-capable panels (canvas, tree).
 */

import type { NodeId, SlotId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import { isPaletteDrag, isBlockDrag, type DragData } from './types.js'

export interface DropResult {
  ok: boolean
  error?: string
  insertedNodeId?: NodeId
}

/**
 * Execute a drop: dispatch InsertNode (palette) or MoveNode (block) to the engine.
 */
export function handleDrop(
  engine: EditorEngine,
  dragData: DragData,
  targetSlotId: SlotId,
  index: number,
): DropResult {
  if (isPaletteDrag(dragData)) {
    const { node, slots, extraNodes } = engine.registry.createNode(dragData.blockType)
    const result = engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId, index, _restoreNodes: extraNodes })
    if (!result.ok) {
      return { ok: false, error: result.error }
    }
    engine.selectNode(node.id)
    return { ok: true, insertedNodeId: node.id }
  } else if (isBlockDrag(dragData)) {
    const result = engine.dispatch({ type: 'MoveNode', nodeId: dragData.nodeId, targetSlotId, index })
    if (!result.ok) {
      return { ok: false, error: result.error }
    }
    return { ok: true }
  }

  return { ok: false, error: 'Unsupported drag source' }
}
