/**
 * Drag-and-drop data protocol for the editor.
 *
 * Two drag source types:
 * - palette: dragging a new block type from the palette
 * - block: dragging an existing block from the canvas
 */

import type { NodeId } from '../types/index.js'

export type DragData =
  | { source: 'palette'; blockType: string }
  | { source: 'block'; nodeId: NodeId; blockType: string }

export function isPaletteDrag(data: Record<string, unknown>): data is DragData & { source: 'palette' } {
  return data.source === 'palette' && typeof data.blockType === 'string'
}

export function isBlockDrag(data: Record<string, unknown>): data is DragData & { source: 'block' } {
  return data.source === 'block' && typeof data.nodeId === 'string' && typeof data.blockType === 'string'
}

export function isDragData(data: Record<string, unknown>): data is DragData {
  return isPaletteDrag(data) || isBlockDrag(data)
}
