/**
 * Shared drop execution logic.
 *
 * Translates a DragData + target slot + index into an engine command
 * (InsertNode or MoveNode). Used by all DnD-capable panels (canvas, tree).
 */

import type { SlotId } from '../types/index.js';
import type { EditorEngine } from '../engine/EditorEngine.js';
import { isPaletteDrag, isBlockDrag, type DragData } from './types.js';

/**
 * Execute a drop: dispatch InsertNode (palette) or MoveNode (block) to the engine.
 *
 * For palette drops, delegates to the component's onBeforeInsert hook if present
 * (e.g., to open a picker dialog for stencils/images). The hook can return override
 * props or null to cancel.
 */
export async function handleDrop(
  engine: EditorEngine,
  dragData: DragData,
  targetSlotId: SlotId,
  index: number,
): Promise<void> {
  if (isPaletteDrag(dragData)) {
    // Call onBeforeInsert if defined (may open a dialog, return override props, or cancel)
    const def = engine.registry.get(dragData.blockType);
    let overrideProps: Record<string, unknown> | undefined;
    if (def?.onBeforeInsert) {
      const result = await def.onBeforeInsert(engine);
      if (!result) return; // cancelled
      overrideProps = result;
    }

    const { node, slots, extraNodes } = engine.registry.createNode(dragData.blockType, overrideProps);
    engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId,
      index,
      _restoreNodes: extraNodes,
    });
    engine.selectNode(node.id);
  } else if (isBlockDrag(dragData)) {
    engine.dispatch({ type: 'MoveNode', nodeId: dragData.nodeId, targetSlotId, index });
  }
}
