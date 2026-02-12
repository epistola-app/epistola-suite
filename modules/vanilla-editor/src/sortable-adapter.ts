/**
 * SortableAdapter — Bridges SortableJS with the headless editor's DragDropPort.
 *
 * Creates and manages SortableJS instances on all `.sortable-container` elements.
 * Re-initializes after every render cycle to account for new/removed containers.
 * Validates drops via the editor's constraint system before executing moves.
 */

import Sortable from "sortablejs";
import type { SortableEvent, MoveEvent } from "sortablejs";
import type { TemplateEditor, DragDropPort } from "@epistola/headless-editor";
import type { SortableAdapterOptions } from "./types.js";

type DropContainerCapableEditor = TemplateEditor & {
  getDropContainerIds?: () => string[];
};

/**
 * Manages SortableJS instances for block drag-and-drop.
 *
 * Usage:
 * ```ts
 * const adapter = new SortableAdapter({ editor, container, dragDropPort });
 * adapter.setup();   // after each render
 * adapter.destroy(); // on teardown
 * ```
 */
export class SortableAdapter {
  private static readonly DRAGGING_CLASS = "ve-block-dragging";
  private editor: TemplateEditor;
  private container: HTMLElement;
  private dnd: DragDropPort;
  private instances: Sortable[] = [];
  private isDragging = false;
  private onInvalidDrop?: () => void;
  private readonly onGlobalDragBlocker = (event: Event): void => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    if (!target.closest(".text-block-editor")) return;

    event.preventDefault();
    event.stopPropagation();
    if (typeof (event as Event & { stopImmediatePropagation?: () => void })
      .stopImmediatePropagation === "function") {
      (event as Event & { stopImmediatePropagation: () => void })
        .stopImmediatePropagation();
    }
  };

  constructor(options: SortableAdapterOptions) {
    this.editor = options.editor;
    this.container = options.container;
    this.dnd = options.dragDropPort;
  }

  /**
   * Initialize SortableJS on the root container and all nested `.sortable-container` elements.
   * Call this after every render to pick up new/removed containers.
   */
  setup(): void {
    this.destroy();

    const declaredDropContainers = new Set<string>(
      (this.editor as DropContainerCapableEditor).getDropContainerIds?.() ?? [],
    );

    const hasBlocks =
      this.container.children.length > 0 &&
      !this.container.querySelector(":scope > .empty-state");

    if (hasBlocks) {
      this.instances.push(
        new Sortable(this.container, this.createConfig(false)),
      );
    }

    this.container.querySelectorAll(".sortable-container").forEach((el) => {
      const containerEl = el as HTMLElement;
      const parentId = containerEl.dataset.parentId;
      if (!parentId || !declaredDropContainers.has(parentId)) {
        return;
      }
      this.instances.push(new Sortable(containerEl, this.createConfig(true)));
    });
  }

  /** Destroy all SortableJS instances. */
  destroy(): void {
    this.setDraggingState(false);
    for (const instance of this.instances) {
      instance.destroy();
    }
    this.instances = [];
  }

  /** Whether a drag operation is currently in progress. */
  getIsDragging(): boolean {
    return this.isDragging;
  }

  /** Set a callback invoked when a drop is rejected by constraints. */
  setInvalidDropCallback(callback: () => void): void {
    this.onInvalidDrop = callback;
  }

  /** Create a SortableJS configuration object. */
  private createConfig(isNested: boolean): Sortable.Options {
    return {
      group: "blocks",
      animation: 150,
      handle: ".drag-handle",
      ghostClass: "sortable-ghost",
      chosenClass: "sortable-chosen",
      dragClass: "sortable-drag",
      fallbackOnBody: true,
      swapThreshold: 0.65,
      emptyInsertThreshold: isNested ? 30 : 0,
      onChoose: (evt: SortableEvent) => this.handleStart(evt),
      onStart: (evt: SortableEvent) => this.handleStart(evt),
      onUnchoose: () => this.setDraggingState(false),
      onEnd: (evt: SortableEvent) => this.handleDrop(evt),
      onMove: (evt: MoveEvent) => this.handleMove(evt),
    };
  }

  private handleStart(_evt: SortableEvent): void {
    this.setDraggingState(true);
  }

  private handleMove(evt: MoveEvent): boolean {
    const draggedId = (evt.dragged as HTMLElement).dataset.blockId;
    if (!draggedId) return false;

    const toContainer = evt.to as HTMLElement;
    let targetParentId: string | null = null;
    if (toContainer.classList.contains("sortable-container")) {
      targetParentId = toContainer.dataset.parentId ?? null;
    }

    const canDrop = this.dnd.canDrop(draggedId, targetParentId, "inside");

    if (!canDrop) {
      toContainer.classList.add("invalid-drop-target");
      setTimeout(
        () => toContainer.classList.remove("invalid-drop-target"),
        200,
      );
    }

    return canDrop;
  }

  private handleDrop(evt: SortableEvent): void {
    if (!this.isDragging) return;
    this.setDraggingState(false);

    const draggedId = (evt.item as HTMLElement).dataset.blockId;
    if (!draggedId) return;

    const toContainer = evt.to as HTMLElement;
    const fromContainer = evt.from as HTMLElement;
    const newIndex = evt.newIndex ?? 0;
    const oldIndex = evt.oldIndex ?? 0;

    let targetParentId: string | null = null;
    if (toContainer.classList.contains("sortable-container")) {
      targetParentId = toContainer.dataset.parentId ?? null;
    }

    if (this.dnd.canDrop(draggedId, targetParentId, "inside")) {
      // CRITICAL: Revert SortableJS's DOM manipulation before calling moveBlock.
      // SortableJS physically moved the DOM node, but we need uhtml to handle
      // all DOM updates to avoid conflicts. Revert the move, then let the editor
      // state update trigger a clean uhtml re-render.
      if (toContainer !== fromContainer || newIndex !== oldIndex) {
        // Move the item back to its original position
        if (oldIndex < fromContainer.children.length) {
          fromContainer.insertBefore(
            evt.item,
            fromContainer.children[oldIndex],
          );
        } else {
          fromContainer.appendChild(evt.item);
        }
      }

      // Now update editor state, which will trigger uhtml to render the new state cleanly
      this.editor.moveBlock(draggedId, targetParentId, newIndex);
    } else {
      this.onInvalidDrop?.();
    }
  }

  private setDraggingState(isDragging: boolean): void {
    this.isDragging = isDragging;
    document.body.classList.toggle(SortableAdapter.DRAGGING_CLASS, isDragging);
    if (isDragging) {
      document.addEventListener("dragenter", this.onGlobalDragBlocker, true);
      document.addEventListener("dragover", this.onGlobalDragBlocker, true);
      document.addEventListener("drop", this.onGlobalDragBlocker, true);
      return;
    }

    document.removeEventListener("dragenter", this.onGlobalDragBlocker, true);
    document.removeEventListener("dragover", this.onGlobalDragBlocker, true);
    document.removeEventListener("drop", this.onGlobalDragBlocker, true);
  }
}
