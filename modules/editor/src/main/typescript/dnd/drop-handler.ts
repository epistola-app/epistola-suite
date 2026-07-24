// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
      const result = await def.onBeforeInsert(engine, { targetSlotId });
      if (!result) return; // cancelled
      overrideProps = result;
    }

    const { node, slots, extraNodes } = engine.registry.createNode(
      dragData.blockType,
      overrideProps,
    );
    const result = engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId,
      index,
      _restoreNodes: extraNodes,
    });
    if (!result.ok) return;
    engine.selectNode(node.id);
  } else if (isBlockDrag(dragData)) {
    // The drop UI (`resolveDropOnBlockEdge` / `resolveDropInsideNode`) returns
    // the position in the *original* slot list. `applyMoveNode` expects the
    // index in the post-removal (filtered) list — i.e. the desired final slot
    // position. For same-slot moves these only differ when the drop position
    // is past the moving node's original index: the filtered list is one
    // shorter, so subtract one. Cross-slot moves don't need this adjustment.
    let resolvedIndex = index;
    const currentSlotId = engine.indexes.parentSlotByNodeId.get(dragData.nodeId);
    if (currentSlotId && currentSlotId === targetSlotId) {
      const currentSlot = engine.doc.slots[currentSlotId];
      const currentIndex = currentSlot?.children.indexOf(dragData.nodeId) ?? -1;
      if (currentIndex >= 0 && resolvedIndex > currentIndex) {
        resolvedIndex -= 1;
      }
    }
    engine.dispatch({
      type: 'MoveNode',
      nodeId: dragData.nodeId,
      targetSlotId,
      index: resolvedIndex,
    });
  }
}
