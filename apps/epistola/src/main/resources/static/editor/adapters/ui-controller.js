/**
 * UIController - Handles UI interactions and toolbar events
 *
 * Manages button click handlers, keyboard shortcuts, and toolbar state.
 */

import { logCallback } from './dom-helpers.js';

const BLOCK_BUTTONS = [
  { id: 'btn-add-text',        type: 'text' },
  { id: 'btn-add-container',   type: 'container' },
  { id: 'btn-add-conditional', type: 'conditional' },
  { id: 'btn-add-loop',        type: 'loop' },
  { id: 'btn-add-columns',     type: 'columns' },
  { id: 'btn-add-table',       type: 'table' },
  { id: 'btn-add-pagebreak',   type: 'pagebreak' },
  { id: 'btn-add-pageheader',  type: 'pageheader' },
  { id: 'btn-add-pagefooter',  type: 'pagefooter' },
];

export class UIController {
  /**
   * @param {import('@epistola/headless-editor').TemplateEditor} editor
   * @param {{ onUndoRedo?: () => void, onBlockAdded?: () => void }} [options]
   */
  constructor(editor, options = {}) {
    this.editor = editor;
    this.options = options;
    this.undoBtn = document.getElementById('btn-undo');
    this.redoBtn = document.getElementById('btn-redo');
    this.keydownHandler = this._handleKeydown.bind(this);

    this._setupEventListeners();
    document.addEventListener('keydown', this.keydownHandler);
  }

  _setupEventListeners() {
    for (const mapping of BLOCK_BUTTONS) {
      const btn = document.getElementById(mapping.id);
      if (btn) {
        btn.addEventListener('click', () => {
          const block = this.editor.addBlock(mapping.type);
          if (block) {
            logCallback(`Added ${mapping.type} block`);
            if (this.options.onBlockAdded) this.options.onBlockAdded();
          }
        });
      }
    }

    const addToSelectedBtn = document.getElementById('btn-add-to-selected');
    if (addToSelectedBtn) {
      addToSelectedBtn.addEventListener('click', () => this._handleAddToSelected());
    }

    if (this.undoBtn) {
      this.undoBtn.addEventListener('click', () => this._handleUndo());
    }

    if (this.redoBtn) {
      this.redoBtn.addEventListener('click', () => this._handleRedo());
    }

    const exportBtn = document.getElementById('btn-export');
    if (exportBtn) {
      exportBtn.addEventListener('click', () => this._handleExport());
    }

    const importBtn = document.getElementById('btn-import');
    if (importBtn) {
      importBtn.addEventListener('click', () => this._handleImport());
    }
  }

  _handleKeydown(e) {
    if (
      e.target instanceof HTMLInputElement ||
      e.target instanceof HTMLTextAreaElement ||
      e.target.isContentEditable
    ) {
      return;
    }

    const isCtrlOrCmd = e.ctrlKey || e.metaKey;

    if (isCtrlOrCmd) {
      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        this._handleUndo();
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault();
        this._handleRedo();
      }
    }

    if ((e.key === 'Delete' || e.key === 'Backspace') && !isCtrlOrCmd) {
      const state = this.editor.getState();
      if (state.selectedBlockId) {
        e.preventDefault();
        this.editor.deleteBlock(state.selectedBlockId);
        logCallback('Deleted selected block');
        if (this.options.onBlockAdded) this.options.onBlockAdded();
      }
    }

    if (e.key === 'Escape') {
      this.editor.selectBlock(null);
      logCallback('Selection cleared');
      if (this.options.onBlockAdded) this.options.onBlockAdded();
    }
  }

  _handleAddToSelected() {
    const state = this.editor.getState();

    if (!state.selectedBlockId) {
      logCallback('No block selected');
      return;
    }

    const block = this.editor.findBlock(state.selectedBlockId);
    if (!block) return;

    switch (block.type) {
      case 'columns':
        this.editor.addColumn(state.selectedBlockId);
        logCallback('Added column to selection');
        break;

      case 'table':
        this.editor.addRow(state.selectedBlockId);
        logCallback('Added row to selection');
        break;

      case 'container':
      case 'conditional':
      case 'loop':
      case 'pageheader':
      case 'pagefooter':
        this.editor.addBlock('text', state.selectedBlockId);
        logCallback('Added text to selection');
        break;

      default:
        logCallback(`Cannot add children to ${block.type}`);
        return;
    }

    if (this.options.onBlockAdded) this.options.onBlockAdded();
  }

  _handleUndo() {
    if (this.editor.undo()) {
      logCallback('Undo');
      if (this.options.onUndoRedo) this.options.onUndoRedo();
    }
  }

  _handleRedo() {
    if (this.editor.redo()) {
      logCallback('Redo');
      if (this.options.onUndoRedo) this.options.onUndoRedo();
    }
  }

  _handleExport() {
    const json = this.editor.exportJSON();
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);

    const a = document.createElement('a');
    a.href = url;
    a.download = `template-${this.editor.getTemplate().id}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);

    logCallback('Template exported');
  }

  _handleImport() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'application/json';

    input.onchange = (e) => {
      const file = e.target.files?.[0];
      if (!file) return;

      const reader = new FileReader();
      reader.onload = (event) => {
        const json = event.target?.result;
        if (!json) {
          logCallback('Error: Could not read file');
          return;
        }
        this.editor.importJSON(json);
        logCallback('Template imported');
        if (this.options.onBlockAdded) this.options.onBlockAdded();
      };
      reader.onerror = () => {
        logCallback('Error reading file');
      };
      reader.readAsText(file);
    };

    input.click();
  }

  updateUndoRedoButtons() {
    if (this.undoBtn) {
      this.undoBtn.disabled = !this.editor.canUndo();
    }
    if (this.redoBtn) {
      this.redoBtn.disabled = !this.editor.canRedo();
    }
  }

  destroy() {
    document.removeEventListener('keydown', this.keydownHandler);
  }
}
