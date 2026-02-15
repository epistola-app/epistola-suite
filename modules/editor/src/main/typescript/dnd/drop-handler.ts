/**
 * Shared drop execution logic.
 *
 * Translates a DragData + target slot + index into an engine command
 * (InsertNode or MoveNode). Used by all DnD-capable panels (canvas, tree).
 */

import type { SlotId } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import { isPaletteDrag, isBlockDrag, type DragData } from './types.js'

/**
 * Execute a drop: dispatch InsertNode (palette) or MoveNode (block) to the engine.
 */
export function handleDrop(
  engine: EditorEngine,
  dragData: DragData,
  targetSlotId: SlotId,
  index: number,
): void {
  if (isPaletteDrag(dragData)) {
    const { node, slots } = engine.registry.createNode(dragData.blockType)
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId, index })
    engine.selectNode(node.id)
  } else if (isBlockDrag(dragData)) {
    engine.dispatch({ type: 'MoveNode', nodeId: dragData.nodeId, targetSlotId, index })
  }
}
