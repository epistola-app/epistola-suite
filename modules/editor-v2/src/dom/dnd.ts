/**
 * Drag-and-drop for blocks using SortableJS.
 *
 * Provides drag-and-drop functionality with smooth animations and
 * proper handling of nested sortable containers.
 */

import Sortable, { type SortableEvent } from "sortablejs";

// ============================================================================
// Types
// ============================================================================

/**
 * Drop position relative to a target block.
 */
export type DropPosition = "before" | "after" | "inside";

/**
 * Information about a drop target.
 */
export interface DropTarget {
  /** The target block ID */
  blockId: string;

  /** Position relative to the target */
  position: DropPosition;

  /** For multi-container blocks, the container ID */
  containerId?: string | null;
}

/**
 * Drag data transferred during drag operations.
 */
export interface DragData {
  /** The dragged block ID (for reordering existing blocks) */
  blockId?: string;

  /** The block type (for adding new blocks from palette) */
  blockType?: string;

  /** Source information */
  source: "editor" | "palette";
}

/**
 * Callback when a block is moved.
 */
export type MoveCallback = (
  blockId: string,
  newParentId: string | null,
  newIndex: number,
) => void;

/**
 * Callback when a new block is added from palette.
 */
export type AddCallback = (
  blockType: string,
  parentId: string | null,
  index: number,
) => void;

/**
 * Drag-and-drop manager interface.
 */
export interface DndManager {
  /** Create a sortable container for blocks */
  makeSortable(
    element: HTMLElement,
    parentId: string | null,
    containerId?: string | null,
  ): () => void;

  /** Make a palette grid container sortable (items can be dragged out as clones) */
  makePaletteContainer(element: HTMLElement): () => void;

  /** Set the move callback */
  onMove(callback: MoveCallback): void;

  /** Set the add callback */
  onAdd(callback: AddCallback): void;

  /** Dispose of the manager */
  dispose(): void;
}

// ============================================================================
// Constants
// ============================================================================

export const DND_CLASSES = {
  dragging: "ev2-block--dragging",
  ghost: "ev2-block--ghost",
  chosen: "ev2-block--chosen",
  drag: "ev2-block--drag",
  fallback: "ev2-block--fallback",
} as const;

const SORTABLE_GROUP = "ev2-blocks";

// ============================================================================
// Implementation
// ============================================================================

/**
 * Create a drag-and-drop manager using SortableJS.
 */
export function createDndManager(): DndManager {
  let moveCallback: MoveCallback | null = null;
  let addCallback: AddCallback | null = null;
  const sortableInstances: Sortable[] = [];
  const cleanupFunctions: Array<() => void> = [];

  /**
   * Get the parent ID for a sortable container.
   */
  function getParentId(element: HTMLElement): string | null {
    const parentId = element.dataset.parentId;
    const containerId = element.dataset.containerId;

    if (!parentId) return null;
    if (containerId) return `${parentId}::${containerId}`;
    return parentId;
  }

  return {
    makeSortable(
      element: HTMLElement,
      parentId: string | null,
      containerId: string | null = null,
    ): () => void {
      // Store parent info on element for later retrieval
      element.dataset.parentId = parentId ?? "";
      if (containerId) {
        element.dataset.containerId = containerId;
      }
      element.dataset.sortable = "true";

      const sortable = Sortable.create(element, {
        group: {
          name: SORTABLE_GROUP,
          pull: true,
          put: true,
        },
        animation: 150,
        fallbackOnBody: true,
        swapThreshold: 0.65,
        dragClass: DND_CLASSES.drag,
        ghostClass: DND_CLASSES.ghost,
        chosenClass: DND_CLASSES.chosen,
        fallbackClass: DND_CLASSES.fallback,
        draggable: ".ev2-block",
        handle: ".ev2-block", // Entire block is draggable
        filter: ".ev2-gap-drop-zone, .ev2-placeholder", // Don't drag gaps or placeholders

        onStart(evt: SortableEvent) {
          const item = evt.item as HTMLElement;
          item.classList.add(DND_CLASSES.dragging);
        },

        onEnd(evt: SortableEvent) {
          const item = evt.item as HTMLElement;
          item.classList.remove(DND_CLASSES.dragging);

          // Handle move within editor
          const blockId = item.dataset.blockId;
          if (!blockId) return;

          // If it was moved to a different container or position
          const toEl = evt.to as HTMLElement;
          const newParentId = getParentId(toEl);
          const newIndex = evt.newIndex ?? 0;

          // Only trigger callback if actually moved
          if (evt.from !== evt.to || evt.oldIndex !== evt.newIndex) {
            moveCallback?.(blockId, newParentId, newIndex);
          }
        },

        onAdd(evt: SortableEvent) {
          const item = evt.item as HTMLElement;
          item.classList.remove(DND_CLASSES.dragging);

          // Check if this is from palette
          const blockType = item.dataset.blockType;
          if (blockType) {
            // This is from palette - add new block
            const toEl = evt.to as HTMLElement;
            const newParentId = getParentId(toEl);
            const newIndex = evt.newIndex ?? 0;

            // Remove the palette clone from the sortable
            item.remove();

            // Trigger add callback
            addCallback?.(blockType, newParentId, newIndex);
          }
        },
      });

      sortableInstances.push(sortable);

      const cleanup = () => {
        sortable.destroy();
        const idx = sortableInstances.indexOf(sortable);
        if (idx !== -1) {
          sortableInstances.splice(idx, 1);
        }
        delete element.dataset.parentId;
        delete element.dataset.containerId;
        delete element.dataset.sortable;
      };

      cleanupFunctions.push(cleanup);
      return cleanup;
    },

    makePaletteContainer(element: HTMLElement): () => void {
      const sortable = Sortable.create(element, {
        group: {
          name: SORTABLE_GROUP,
          pull: "clone",
          put: false,
        },
        sort: false,
        draggable: ".ev2-palette__item",
        ghostClass: DND_CLASSES.ghost,
        chosenClass: DND_CLASSES.chosen,
        dragClass: DND_CLASSES.drag,

        onStart(evt: SortableEvent) {
          const item = evt.item as HTMLElement;
          item.classList.add(DND_CLASSES.dragging);
        },

        onEnd(evt: SortableEvent) {
          const item = evt.item as HTMLElement;
          item.classList.remove(DND_CLASSES.dragging);
        },
      });

      sortableInstances.push(sortable);

      const cleanup = () => {
        sortable.destroy();
        const idx = sortableInstances.indexOf(sortable);
        if (idx !== -1) {
          sortableInstances.splice(idx, 1);
        }
      };

      cleanupFunctions.push(cleanup);
      return cleanup;
    },

    onMove(callback: MoveCallback): void {
      moveCallback = callback;
    },

    onAdd(callback: AddCallback): void {
      addCallback = callback;
    },

    dispose(): void {
      for (const sortable of sortableInstances) {
        sortable.destroy();
      }
      sortableInstances.length = 0;
      cleanupFunctions.length = 0;
      moveCallback = null;
      addCallback = null;
    },
  };
}

// ============================================================================
// Utilities (kept for backward compatibility)
// ============================================================================

/**
 * Create a drag handle element for a block.
 */
export function createDragHandle(): HTMLElement {
  const handle = document.createElement("div");
  handle.className = "ev2-drag-handle";
  handle.innerHTML = `
    <svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor">
      <circle cx="3" cy="3" r="1.5"/>
      <circle cx="9" cy="3" r="1.5"/>
      <circle cx="3" cy="9" r="1.5"/>
      <circle cx="9" cy="9" r="1.5"/>
    </svg>
  `;
  return handle;
}

/**
 * Calculate the insertion index based on drop position.
 * @deprecated Use SortableJS which handles this automatically
 */
export function calculateInsertIndex(
  position: DropPosition,
  targetIndex: number,
  isMovingDown: boolean,
): number {
  switch (position) {
    case "before":
      return isMovingDown ? targetIndex : targetIndex;
    case "after":
      return isMovingDown ? targetIndex + 1 : targetIndex + 1;
    case "inside":
      return 0;
  }
}

// Legacy exports for backward compatibility
export type DropCallback = (data: DragData, target: DropTarget) => void;
