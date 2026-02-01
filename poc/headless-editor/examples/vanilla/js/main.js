import { TemplateEditor } from '../../../dist/editor.bundle.js';
import { logCallback } from './dom-helpers.js';
import { BlockRenderer } from './renderer.js';
import { SortableAdapter } from './sortable-adapter.js';
import { UIController } from './ui-controller.js';

/**
 * Main entry point - wires all components together
 *
 * This module initializes the TemplateEditor and connects all UI components:
 * - BlockRenderer: Renders blocks to DOM
 * - SortableAdapter: Handles drag-and-drop
 * - UIController: Handles buttons and keyboard shortcuts
 *
 * The pattern: Editor (state) → Renderer (DOM) → Adapter (events) → Editor
 */

// Wait for DOM ready
document.addEventListener('DOMContentLoaded', () => {
  let ready = false;

  // === Initialize Editor ===
  const editor = new TemplateEditor({
    template: { id: 'demo-template', name: 'Demo Template', blocks: [] },
    callbacks: {
      onTemplateChange: (template) => {
        if (!ready) return;
        logCallback('Template changed');
        refreshUI();
      },
      onBlockSelect: (blockId) => {
        if (!ready) return;
        logCallback(`Selected: ${blockId || 'none'}`);
        refreshUI();
      },
      onBeforeBlockAdd: (block, parentId) => {
        logCallback(`Adding ${block.type} to ${parentId || 'root'}`);
        return true;
      },
      onBeforeBlockDelete: (blockId) => {
        logCallback(`Deleting ${blockId}`);
        return true;
      },
      onError: (error) => {
        logCallback(`ERROR: ${error.message}`);
      },
    },
  });

  // === Initialize UI Components ===
  const renderer = new BlockRenderer(editor, 'block-list');
  const sortableAdapter = new SortableAdapter(editor, 'block-list');

  // Wire invalid drop to re-render (restores DOM order)
  sortableAdapter.setInvalidDropCallback(() => {
    renderer.render();
    sortableAdapter.setup();
  });

  const uiController = new UIController(editor, {
    onUndoRedo: () => {
      refreshUI();
    },
  });

  // === Refresh Function ===
  function refreshUI() {
    // Skip if dragging (SortableJS handles DOM during drag)
    if (sortableAdapter.getIsDragging()) return;

    renderer.render();
    renderer.renderState();
    sortableAdapter.setup();
    uiController.updateUndoRedoButtons();
  }

  // === Initial Render ===
  ready = true;
  refreshUI();
  logCallback('Editor initialized with undo/redo + columns');
});
