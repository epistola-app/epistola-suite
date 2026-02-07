/**
 * Native HTML5 drag-and-drop for blocks.
 *
 * Provides drag-and-drop functionality using the native HTML5 DnD API.
 * No external dependencies required.
 */

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
 * Callback when a drop occurs.
 */
export type DropCallback = (data: DragData, target: DropTarget) => void;

/**
 * Drag-and-drop manager interface.
 */
export interface DndManager {
  /** Make a block element draggable */
  makeDraggable(element: HTMLElement, blockId: string): () => void;

  /** Make an element a drop zone (container for blocks) */
  makeDropZone(
    element: HTMLElement,
    blockId: string | null,
    containerId?: string | null,
    allowInside?: boolean,
  ): () => void;

  /** Make a palette item draggable */
  makePaletteDraggable(element: HTMLElement, blockType: string): () => void;

  /** Set the drop callback */
  onDrop(callback: DropCallback): void;

  /** Dispose of the manager */
  dispose(): void;
}

// ============================================================================
// Constants
// ============================================================================

const DRAG_MIME_TYPE = "application/x-editor-v2-block";

export const DND_CLASSES = {
  dragging: "ev2-block--dragging",
  dragOver: "ev2-block--drag-over",
  dropBefore: "ev2-block--drop-before",
  dropAfter: "ev2-block--drop-after",
  dropInside: "ev2-block--drop-inside",
} as const;

// ============================================================================
// Implementation
// ============================================================================

/**
 * Create a drag-and-drop manager.
 *
 * @example
 * ```typescript
 * const dnd = createDndManager();
 *
 * // Make blocks draggable
 * dnd.makeDraggable(blockElement, 'block-1');
 *
 * // Make containers accept drops
 * dnd.makeDropZone(containerElement, 'container-1');
 *
 * // Handle drops
 * dnd.onDrop((data, target) => {
 *   if (data.source === 'palette') {
 *     // Add new block
 *     addBlock(data.blockType, target.blockId, target.position);
 *   } else {
 *     // Move existing block
 *     moveBlock(data.blockId, target.blockId, target.position);
 *   }
 * });
 * ```
 */
export function createDndManager(): DndManager {
  let dropCallback: DropCallback | null = null;
  let currentDragData: DragData | null = null;
  const cleanupFunctions: Array<() => void> = [];

  function clearDropIndicators(): void {
    document.querySelectorAll(
      `.${DND_CLASSES.dragOver}, .${DND_CLASSES.dropBefore}, .${DND_CLASSES.dropAfter}, .${DND_CLASSES.dropInside}`,
    ).forEach((el) => {
      el.classList.remove(
        DND_CLASSES.dragOver,
        DND_CLASSES.dropBefore,
        DND_CLASSES.dropAfter,
        DND_CLASSES.dropInside,
      );
    });
  }

  function getDropPosition(event: DragEvent, element: HTMLElement): DropPosition {
    const rect = element.getBoundingClientRect();
    const y = event.clientY - rect.top;
    const height = rect.height;

    // Check if the element can accept "inside" drops (has children)
    const allowInside = element.dataset.allowInside === "true";

    if (allowInside) {
      // For containers: top 25% = before, bottom 25% = after, middle = inside
      if (y < height * 0.25) {
        return "before";
      } else if (y > height * 0.75) {
        return "after";
      } else {
        return "inside";
      }
    } else {
      // For non-containers: top 50% = before, bottom 50% = after
      return y < height / 2 ? "before" : "after";
    }
  }

  function setDropIndicator(element: HTMLElement, position: DropPosition): void {
    clearDropIndicators();
    element.classList.add(DND_CLASSES.dragOver);

    switch (position) {
      case "before":
        element.classList.add(DND_CLASSES.dropBefore);
        break;
      case "after":
        element.classList.add(DND_CLASSES.dropAfter);
        break;
      case "inside":
        element.classList.add(DND_CLASSES.dropInside);
        break;
    }
  }

  return {
    makeDraggable(element: HTMLElement, blockId: string): () => void {
      element.draggable = true;
      element.dataset.blockId = blockId;

      function handleDragStart(event: DragEvent): void {
        if (!event.dataTransfer) return;

        currentDragData = {
          blockId,
          source: "editor",
        };

        event.dataTransfer.effectAllowed = "move";
        event.dataTransfer.setData(DRAG_MIME_TYPE, JSON.stringify(currentDragData));
        event.dataTransfer.setData("text/plain", blockId);

        // Add dragging class after a short delay to avoid it being captured in drag image
        requestAnimationFrame(() => {
          element.classList.add(DND_CLASSES.dragging);
        });
      }

      function handleDragEnd(): void {
        element.classList.remove(DND_CLASSES.dragging);
        clearDropIndicators();
        currentDragData = null;
      }

      element.addEventListener("dragstart", handleDragStart);
      element.addEventListener("dragend", handleDragEnd);

      const cleanup = () => {
        element.removeEventListener("dragstart", handleDragStart);
        element.removeEventListener("dragend", handleDragEnd);
        element.draggable = false;
      };

      cleanupFunctions.push(cleanup);
      return cleanup;
    },

    makeDropZone(
      element: HTMLElement,
      blockId: string | null,
      containerId: string | null = null,
      allowInside: boolean = true,
    ): () => void {
      element.dataset.dropZone = "true";
      element.dataset.allowInside = String(allowInside);
      if (blockId) {
        element.dataset.blockId = blockId;
      }
      if (containerId) {
        element.dataset.containerId = containerId;
      }

      function handleDragOver(event: DragEvent): void {
        if (!event.dataTransfer) return;

        // Check if this is a valid drop (don't allow dropping on self)
        if (currentDragData?.blockId === blockId) {
          return;
        }

        event.preventDefault();
        event.dataTransfer.dropEffect = "move";

        const position = getDropPosition(event, element);
        setDropIndicator(element, position);
      }

      function handleDragLeave(event: DragEvent): void {
        // Only clear if we're leaving the element entirely
        const relatedTarget = event.relatedTarget as HTMLElement | null;
        if (!element.contains(relatedTarget)) {
          element.classList.remove(
            DND_CLASSES.dragOver,
            DND_CLASSES.dropBefore,
            DND_CLASSES.dropAfter,
            DND_CLASSES.dropInside,
          );
        }
      }

      function handleDrop(event: DragEvent): void {
        event.preventDefault();
        clearDropIndicators();

        if (!event.dataTransfer || !dropCallback) return;

        const position = getDropPosition(event, element);

        // Try to get drag data from dataTransfer or current state
        let dragData: DragData | null = currentDragData;

        if (!dragData) {
          const jsonData = event.dataTransfer.getData(DRAG_MIME_TYPE);
          if (jsonData) {
            try {
              dragData = JSON.parse(jsonData);
            } catch {
              // Invalid JSON, ignore
            }
          }
        }

        if (!dragData) return;

        // Don't allow dropping on self
        if (dragData.blockId === blockId) {
          return;
        }

        const target: DropTarget = {
          blockId: blockId ?? "",
          position,
          containerId,
        };

        dropCallback(dragData, target);
      }

      element.addEventListener("dragover", handleDragOver);
      element.addEventListener("dragleave", handleDragLeave);
      element.addEventListener("drop", handleDrop);

      const cleanup = () => {
        element.removeEventListener("dragover", handleDragOver);
        element.removeEventListener("dragleave", handleDragLeave);
        element.removeEventListener("drop", handleDrop);
        delete element.dataset.dropZone;
        delete element.dataset.allowInside;
      };

      cleanupFunctions.push(cleanup);
      return cleanup;
    },

    makePaletteDraggable(element: HTMLElement, blockType: string): () => void {
      element.draggable = true;
      element.dataset.blockType = blockType;

      function handleDragStart(event: DragEvent): void {
        if (!event.dataTransfer) return;

        currentDragData = {
          blockType,
          source: "palette",
        };

        event.dataTransfer.effectAllowed = "copy";
        event.dataTransfer.setData(DRAG_MIME_TYPE, JSON.stringify(currentDragData));
        event.dataTransfer.setData("text/plain", blockType);

        element.classList.add(DND_CLASSES.dragging);
      }

      function handleDragEnd(): void {
        element.classList.remove(DND_CLASSES.dragging);
        clearDropIndicators();
        currentDragData = null;
      }

      element.addEventListener("dragstart", handleDragStart);
      element.addEventListener("dragend", handleDragEnd);

      const cleanup = () => {
        element.removeEventListener("dragstart", handleDragStart);
        element.removeEventListener("dragend", handleDragEnd);
        element.draggable = false;
      };

      cleanupFunctions.push(cleanup);
      return cleanup;
    },

    onDrop(callback: DropCallback): void {
      dropCallback = callback;
    },

    dispose(): void {
      for (const cleanup of cleanupFunctions) {
        cleanup();
      }
      cleanupFunctions.length = 0;
      dropCallback = null;
      currentDragData = null;
      clearDropIndicators();
    },
  };
}

// ============================================================================
// Utilities
// ============================================================================

/**
 * Create a drag handle element for a block.
 * Useful when you want only part of a block to be draggable.
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
      return 0; // Insert at beginning of container
  }
}
