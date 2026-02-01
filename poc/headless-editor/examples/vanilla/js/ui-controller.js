import { logCallback } from './dom-helpers.js';

/**
 * UIController - Handles UI interactions and button events
 *
 * This class manages:
 * - Button click handlers (add block, undo, redo, etc.)
 * - Keyboard shortcuts (Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y)
 * - Undo/redo button state updates
 * - "Add to selected" smart block insertion
 */
export class UIController {
  /**
   * @param {TemplateEditor} editor - The template editor instance
   * @param {Object} options - Configuration options
   * @param {Function} options.onUndoRedo - Callback when undo/redo occurs
   */
  constructor(editor, options = {}) {
    this.editor = editor;
    this.onUndoRedo = options.onUndoRedo || (() => {});

    this.undoBtn = document.getElementById('btn-undo');
    this.redoBtn = document.getElementById('btn-redo');

    this.setupEventListeners();
    this.setupKeyboardShortcuts();
  }

  /**
   * Sets up all button click event listeners
   */
  setupEventListeners() {
    // Add block buttons
    const addTextBtn = document.getElementById('btn-add-text');
    const addContainerBtn = document.getElementById('btn-add-container');
    const addColumnsBtn = document.getElementById('btn-add-columns');
    const addToSelectedBtn = document.getElementById('btn-add-to-selected');

    if (addTextBtn) {
      addTextBtn.addEventListener('click', () => this.editor.addBlock('text'));
    }

    if (addContainerBtn) {
      addContainerBtn.addEventListener('click', () =>
        this.editor.addBlock('container')
      );
    }

    if (addColumnsBtn) {
      addColumnsBtn.addEventListener('click', () => this.handleAddColumns());
    }

    if (addToSelectedBtn) {
      addToSelectedBtn.addEventListener('click', () =>
        this.handleAddToSelected()
      );
    }

    // Undo/redo buttons
    if (this.undoBtn) {
      this.undoBtn.addEventListener('click', () => this.handleUndo());
    }

    if (this.redoBtn) {
      this.redoBtn.addEventListener('click', () => this.handleRedo());
    }
  }

  /**
   * Sets up keyboard shortcuts
   * - Ctrl+Z: Undo
   * - Ctrl+Shift+Z or Ctrl+Y: Redo
   */
  setupKeyboardShortcuts() {
    document.addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey)) return;

      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        this.handleUndo();
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault();
        this.handleRedo();
      }
    });
  }

  /**
   * Handles adding a columns block with default 2 columns
   */
  handleAddColumns() {
    const columns = this.editor.addBlock('columns');
    if (columns) {
      this.editor.addBlock('column', columns.id);
      this.editor.addBlock('column', columns.id);
    }
  }

  /**
   * Handles "Add to Selected" button - intelligently adds blocks
   * - If columns selected: adds a column (max 4)
   * - If container or column selected: adds text block
   * - Otherwise: shows error
   */
  handleAddToSelected() {
    const state = this.editor.getState();

    if (!state.selectedBlockId) {
      logCallback('No block selected');
      return;
    }

    const block = this.editor.findBlock(state.selectedBlockId);
    if (!block) return;

    switch (block.type) {
      case 'columns':
        if (block.children && block.children.length >= 4) {
          logCallback('Max 4 columns allowed');
          return;
        }
        this.editor.addBlock('column', state.selectedBlockId);
        break;

      case 'container':
      case 'column':
        this.editor.addBlock('text', state.selectedBlockId);
        break;

      default:
        logCallback(`Cannot add children to ${block.type}`);
    }
  }

  /**
   * Handles undo action
   */
  handleUndo() {
    if (this.editor.undo()) {
      logCallback('Undo');
      this.onUndoRedo();
    }
  }

  /**
   * Handles redo action
   */
  handleRedo() {
    if (this.editor.redo()) {
      logCallback('Redo');
      this.onUndoRedo();
    }
  }

  /**
   * Updates undo/redo button disabled states
   */
  updateUndoRedoButtons() {
    if (this.undoBtn) {
      this.undoBtn.disabled = !this.editor.canUndo();
    }
    if (this.redoBtn) {
      this.redoBtn.disabled = !this.editor.canRedo();
    }
  }
}
