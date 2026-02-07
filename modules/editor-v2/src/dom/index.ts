/**
 * DOM rendering layer.
 *
 * Provides:
 * - Block selection management
 * - Native HTML5 drag-and-drop
 * - State-to-DOM rendering
 */

// Selection
export {
  createSelectionManager,
  SELECTION_CLASSES,
  applySelectionClasses,
  handleSelectionClick,
} from "./selection.ts";
export type {
  SelectionState,
  SelectionListener,
  SelectionManager,
} from "./selection.ts";

// Drag-and-drop
export {
  createDndManager,
  DND_CLASSES,
  createDragHandle,
  calculateInsertIndex,
} from "./dnd.ts";
export type {
  DropPosition,
  DropTarget,
  DragData,
  DropCallback,
  DndManager,
} from "./dnd.ts";

// Renderer
export {
  createRenderer,
  RENDERER_CLASSES,
  focusBlock,
  getBlockIdFromEvent,
} from "./renderer.ts";
export type {
  RenderMode,
  EditorRenderContext,
  EditorRenderer,
} from "./renderer.ts";
