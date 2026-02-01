import { logCallback } from './dom-helpers.js';

/**
 * SortableAdapter - Integrates SortableJS with the TemplateEditor
 *
 * This adapter bridges the gap between the headless editor core and
 * the SortableJS drag-and-drop library. It:
 * - Initializes SortableJS on root and nested containers
 * - Uses the editor's DragDropPort for validation
 * - Handles drag start/end events
 * - Syncs DOM changes back to the editor state
 */
export class SortableAdapter {
  /**
   * @param {TemplateEditor} editor - The template editor instance
   * @param {string} rootContainerId - ID of the root block list container
   */
  constructor(editor, rootContainerId) {
    this.editor = editor;
    this.rootContainer = document.getElementById(rootContainerId);
    if (!this.rootContainer) {
      throw new Error(`Root container element not found: ${rootContainerId}`);
    }

    this.instances = [];
    this.isDragging = false;
    this.dnd = editor.getDragDropPort();

    // Bind methods for event handlers
    this.handleDrop = this.handleDrop.bind(this);
    this.handleStart = this.handleStart.bind(this);
  }

  /**
   * Sets up SortableJS on all containers
   *
   * Destroys old instances and creates new ones for root and nested containers.
   * Should be called after every re-render.
   */
  setup() {
    // Clean up old instances
    this.instances.forEach((s) => s.destroy());
    this.instances = [];

    // Initialize on root container
    const rootChildren = this.rootContainer.children;
    const hasBlocks =
      rootChildren.length > 0 &&
      !this.rootContainer.querySelector(':scope > .empty-state');

    if (hasBlocks) {
      this.instances.push(
        new Sortable(this.rootContainer, this.createSortableConfig(false))
      );
    }

    // Initialize on all nested containers
    document.querySelectorAll('.sortable-container').forEach((container) => {
      this.instances.push(
        new Sortable(container, this.createSortableConfig(true))
      );
    });
  }

  /**
   * Creates SortableJS configuration
   *
   * @param {boolean} isNested - Whether this is for a nested container
   * @returns {Object} SortableJS configuration object
   */
  createSortableConfig(isNested = false) {
    return {
      group: 'blocks',
      animation: 150,
      handle: '.block-header',
      ghostClass: 'sortable-ghost',
      chosenClass: 'sortable-chosen',
      dragClass: 'sortable-drag',
      fallbackOnBody: true,
      swapThreshold: 0.65,
      emptyInsertThreshold: isNested ? 30 : 0,
      onStart: this.handleStart,
      onEnd: this.handleDrop,
    };
  }

  /**
   * Handles drag start event
   */
  handleStart() {
    this.isDragging = true;
    logCallback('Drag started');
  }

  /**
   * Handles drop event - validates and updates editor state
   *
   * @param {Object} evt - SortableJS event object
   * @param {Element} evt.item - The dragged element
   * @param {Element} evt.to - The target container
   * @param {number} evt.newIndex - New index in the target container
   */
  handleDrop(evt) {
    this.isDragging = false;

    const draggedId = evt.item.dataset.blockId;
    const toContainer = evt.to;
    const newIndex = evt.newIndex;

    // Determine target parent
    let targetParentId = null;
    if (toContainer.classList.contains('sortable-container')) {
      targetParentId = toContainer.dataset.parentId;
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
   *
   * @param {Function} callback - Function to call when drop is rejected
   */
  setInvalidDropCallback(callback) {
    this.onInvalidDrop = callback;
  }

  /**
   * Cleans up all SortableJS instances
   */
  destroy() {
    this.instances.forEach((s) => s.destroy());
    this.instances = [];
  }

  /**
   * Check if currently dragging
   *
   * @returns {boolean} True if drag operation is in progress
   */
  getIsDragging() {
    return this.isDragging;
  }
}
