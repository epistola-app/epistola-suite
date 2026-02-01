/**
 * SortableAdapter - Integrates SortableJS with the TemplateEditor
 *
 * Bridges the headless editor core with SortableJS for drag-and-drop.
 * Expects the global `Sortable` to be available (loaded via CDN).
 */

import { logCallback } from './dom-helpers.js';

export class SortableAdapter {
  /** @param {import('@epistola/headless-editor').TemplateEditor} editor */
  /** @param {string} rootContainerId */
  constructor(editor, rootContainerId) {
    this.editor = editor;
    const rootContainer = document.getElementById(rootContainerId);
    if (!rootContainer) {
      throw new Error(`Root container element not found: ${rootContainerId}`);
    }
    this.rootContainer = rootContainer;
    this.instances = [];
    this.isDragging = false;
    this.dnd = editor.getDragDropPort();
    this.onInvalidDrop = undefined;
  }

  /**
   * Sets up SortableJS on all containers.
   * Should be called after every re-render.
   */
  setup() {
    this.destroy();

    const hasBlocks =
      this.rootContainer.children.length > 0 && !this.rootContainer.querySelector(':scope > .empty-state');

    if (hasBlocks) {
      this.instances.push(new Sortable(this.rootContainer, this._createSortableConfig(false)));
    }

    document.querySelectorAll('.sortable-container').forEach((container) => {
      this.instances.push(new Sortable(container, this._createSortableConfig(true)));
    });
  }

  /** @param {boolean} isNested */
  _createSortableConfig(isNested) {
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
      onStart: (evt) => this._handleStart(evt),
      onEnd: (evt) => this._handleDrop(evt),
      onMove: (evt) => this._handleMove(evt),
    };
  }

  _handleStart(evt) {
    this.isDragging = true;
    const blockId = evt.item.dataset.blockId;
    logCallback(`Drag started: ${blockId}`);
  }

  _handleMove(evt) {
    const draggedId = evt.dragged.dataset.blockId;
    if (!draggedId) return false;

    const toContainer = evt.to;
    let targetParentId = null;

    if (toContainer.classList.contains('sortable-container')) {
      targetParentId = toContainer.dataset.parentId || null;
    }

    const canDrop = this.dnd.canDrop(draggedId, targetParentId, 'inside');

    if (!canDrop) {
      evt.to.classList.add('invalid-drop-target');
      setTimeout(() => evt.to.classList.remove('invalid-drop-target'), 200);
    }

    return canDrop;
  }

  _handleDrop(evt) {
    this.isDragging = false;

    const draggedId = evt.item.dataset.blockId;
    if (!draggedId) {
      logCallback('Drop failed: no block ID');
      return;
    }

    const toContainer = evt.to;
    const newIndex = evt.newIndex ?? 0;

    let targetParentId = null;
    if (toContainer.classList.contains('sortable-container')) {
      targetParentId = toContainer.dataset.parentId || null;
    }

    logCallback(`Drop: ${draggedId} -> ${targetParentId || 'root'} at ${newIndex}`);

    if (this.dnd.canDrop(draggedId, targetParentId, 'inside')) {
      this.editor.moveBlock(draggedId, targetParentId, newIndex);
      logCallback('Drop accepted');
    } else {
      logCallback('Invalid drop rejected');
      if (this.onInvalidDrop) this.onInvalidDrop();
    }
  }

  /** @param {() => void} callback */
  setInvalidDropCallback(callback) {
    this.onInvalidDrop = callback;
  }

  destroy() {
    this.instances.forEach((s) => s.destroy());
    this.instances = [];
  }

  /** @returns {boolean} */
  getIsDragging() {
    return this.isDragging;
  }
}
