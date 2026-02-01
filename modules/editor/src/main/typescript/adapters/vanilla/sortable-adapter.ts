/**
 * SortableAdapter - Integrates SortableJS with the TemplateEditor
 *
 * Bridges the headless editor core with SortableJS for drag-and-drop.
 */

import Sortable from 'sortablejs';
import type { TemplateEditor } from '../../core/editor.js';
import type { DragDropPort } from '../../core/types.js';
import { logCallback } from './dom-helpers.js';

/**
 * SortableAdapter - Bridges the headless editor with SortableJS
 */
export class SortableAdapter {
  private editor: TemplateEditor;
  private rootContainer: HTMLElement;
  private instances: Sortable[] = [];
  private isDragging = false;
  private dnd: DragDropPort;
  private onInvalidDrop?: () => void;

  constructor(editor: TemplateEditor, rootContainerId: string) {
    this.editor = editor;
    const rootContainer = document.getElementById(rootContainerId);
    if (!rootContainer) {
      throw new Error(`Root container element not found: ${rootContainerId}`);
    }
    this.rootContainer = rootContainer;
    this.dnd = editor.getDragDropPort();
  }

  /**
   * Sets up SortableJS on all containers
   * Should be called after every re-render
   */
  setup(): void {
    // Clean up old instances
    this.destroy();

    // Initialize on root container
    const hasBlocks =
      this.rootContainer.children.length > 0 && !this.rootContainer.querySelector(':scope > .empty-state');

    if (hasBlocks) {
      this.instances.push(new Sortable(this.rootContainer, this.createSortableConfig(false)));
    }

    // Initialize on all nested containers
    document.querySelectorAll('.sortable-container').forEach((container) => {
      this.instances.push(new Sortable(container as HTMLElement, this.createSortableConfig(true)));
    });
  }

  /**
   * Creates SortableJS configuration
   */
  private createSortableConfig(isNested: boolean): Sortable.Options {
    return {
      group: 'blocks',
      animation: 150,
      handle: '.drag-handle',
      ghostClass: 'sortable-ghost',
      chosenClass: 'sortable-chosen',
      dragClass: 'sortable-drag',
      fallbackOnBody: true,
      swapThreshold: 0.65,
      emptyInsertThreshold: isNested ? 30 : 0,
      onStart: this.handleStart.bind(this),
      onEnd: this.handleDrop.bind(this),
      onMove: this.handleMove.bind(this),
    };
  }

  /**
   * Handles drag start event
   */
  private handleStart(evt: Sortable.SortableEvent): void {
    this.isDragging = true;
    const blockId = (evt.item as HTMLElement).dataset.blockId;
    logCallback(`Drag started: ${blockId}`);
  }

  /**
   * Handles move event for validation during drag
   */
  private handleMove(evt: Sortable.MoveEvent): boolean | void {
    const draggedId = (evt.dragged as HTMLElement).dataset.blockId;
    if (!draggedId) return false;

    // Get target container's parent ID
    const toContainer = evt.to as HTMLElement;
    let targetParentId: string | null = null;

    if (toContainer.classList.contains('sortable-container')) {
      targetParentId = toContainer.dataset.parentId || null;
    }

    // Validate the drop
    const canDrop = this.dnd.canDrop(draggedId, targetParentId, 'inside');

    if (!canDrop) {
      // Visual feedback for invalid drop
      evt.to.classList.add('invalid-drop-target');
      setTimeout(() => evt.to.classList.remove('invalid-drop-target'), 200);
    }

    return canDrop;
  }

  /**
   * Handles drop event - validates and updates editor state
   */
  private handleDrop(evt: Sortable.SortableEvent): void {
    this.isDragging = false;

    const draggedId = (evt.item as HTMLElement).dataset.blockId;
    if (!draggedId) {
      logCallback('Drop failed: no block ID');
      return;
    }

    const toContainer = evt.to as HTMLElement;
    const newIndex = evt.newIndex ?? 0;

    // Determine target parent
    let targetParentId: string | null = null;
    if (toContainer.classList.contains('sortable-container')) {
      targetParentId = toContainer.dataset.parentId || null;
    }

    logCallback(`Drop: ${draggedId} -> ${targetParentId || 'root'} at ${newIndex}`);

    // Validate and execute drop
    if (this.dnd.canDrop(draggedId, targetParentId, 'inside')) {
      this.editor.moveBlock(draggedId, targetParentId, newIndex);
      logCallback('Drop accepted');
    } else {
      logCallback('Invalid drop rejected');
      // Re-render to restore original order
      this.onInvalidDrop?.();
    }
  }

  /**
   * Sets callback for invalid drop (to trigger re-render)
   */
  setInvalidDropCallback(callback: () => void): void {
    this.onInvalidDrop = callback;
  }

  /**
   * Cleans up all SortableJS instances
   */
  destroy(): void {
    this.instances.forEach((s) => s.destroy());
    this.instances = [];
  }

  /**
   * Check if currently dragging
   */
  getIsDragging(): boolean {
    return this.isDragging;
  }
}
