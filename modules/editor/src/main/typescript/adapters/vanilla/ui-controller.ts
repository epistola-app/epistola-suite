/**
 * UIController - Handles UI interactions and toolbar events
 *
 * Manages button click handlers, keyboard shortcuts, and toolbar state.
 */

import type { TemplateEditor } from '../../core/editor.js';
import { logCallback } from './dom-helpers.js';

/**
 * UIController options
 */
export interface UIControllerOptions {
  onUndoRedo?: () => void;
  onBlockAdded?: () => void;
}

/**
 * Button ID to block type mapping
 */
interface ButtonMapping {
  id: string;
  type: string;
  handler?: () => void;
}

/**
 * UIController - Manages toolbar and keyboard interactions
 */
export class UIController {
  private editor: TemplateEditor;
  private options: UIControllerOptions;
  private undoBtn: HTMLButtonElement | null = null;
  private redoBtn: HTMLButtonElement | null = null;
  private keydownHandler: (e: KeyboardEvent) => void;

  constructor(editor: TemplateEditor, options: UIControllerOptions = {}) {
    this.editor = editor;
    this.options = options;

    this.undoBtn = document.getElementById('btn-undo') as HTMLButtonElement;
    this.redoBtn = document.getElementById('btn-redo') as HTMLButtonElement;

    // Bind the keydown handler so we can remove it later
    this.keydownHandler = this.handleKeydown.bind(this);

    this.setupEventListeners();
    this.setupKeyboardShortcuts();
  }

  /**
   * Sets up all button click event listeners
   */
  private setupEventListeners(): void {
    // Block type buttons
    const blockButtons: ButtonMapping[] = [
      { id: 'btn-add-text', type: 'text' },
      { id: 'btn-add-container', type: 'container' },
      { id: 'btn-add-conditional', type: 'conditional' },
      { id: 'btn-add-loop', type: 'loop' },
      { id: 'btn-add-columns', type: 'columns' },
      { id: 'btn-add-table', type: 'table' },
      { id: 'btn-add-pagebreak', type: 'pagebreak' },
      { id: 'btn-add-pageheader', type: 'pageheader' },
      { id: 'btn-add-pagefooter', type: 'pagefooter' },
    ];

    for (const mapping of blockButtons) {
      const btn = document.getElementById(mapping.id);
      if (btn) {
        btn.addEventListener('click', () => {
          const block = this.editor.addBlock(mapping.type);
          if (block) {
            logCallback(`Added ${mapping.type} block`);
            this.options.onBlockAdded?.();
          }
        });
      }
    }

    // Add to selected button
    const addToSelectedBtn = document.getElementById('btn-add-to-selected');
    if (addToSelectedBtn) {
      addToSelectedBtn.addEventListener('click', () => this.handleAddToSelected());
    }

    // Undo/redo buttons
    if (this.undoBtn) {
      this.undoBtn.addEventListener('click', () => this.handleUndo());
    }

    if (this.redoBtn) {
      this.redoBtn.addEventListener('click', () => this.handleRedo());
    }

    // Export button
    const exportBtn = document.getElementById('btn-export');
    if (exportBtn) {
      exportBtn.addEventListener('click', () => this.handleExport());
    }

    // Import button
    const importBtn = document.getElementById('btn-import');
    if (importBtn) {
      importBtn.addEventListener('click', () => this.handleImport());
    }
  }

  /**
   * Sets up keyboard shortcuts
   * - Ctrl+Z: Undo
   * - Ctrl+Shift+Z or Ctrl+Y: Redo
   * - Delete/Backspace: Delete selected block
   */
  private setupKeyboardShortcuts(): void {
    document.addEventListener('keydown', this.keydownHandler);
  }

  /**
   * Handles keydown events
   */
  private handleKeydown(e: KeyboardEvent): void {
    // Don't handle shortcuts when typing in inputs
    if (
      e.target instanceof HTMLInputElement ||
      e.target instanceof HTMLTextAreaElement ||
      (e.target as HTMLElement).isContentEditable
    ) {
      return;
    }

    const isCtrlOrCmd = e.ctrlKey || e.metaKey;

    if (isCtrlOrCmd) {
      if (e.key === 'z' && !e.shiftKey) {
        e.preventDefault();
        this.handleUndo();
      } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
        e.preventDefault();
        this.handleRedo();
      }
    }

    // Delete selected block
    if ((e.key === 'Delete' || e.key === 'Backspace') && !isCtrlOrCmd) {
      const state = this.editor.getState();
      if (state.selectedBlockId) {
        e.preventDefault();
        this.editor.deleteBlock(state.selectedBlockId);
        logCallback('Deleted selected block');
        this.options.onBlockAdded?.(); // Trigger re-render
      }
    }

    // Escape to deselect
    if (e.key === 'Escape') {
      this.editor.selectBlock(null);
      logCallback('Selection cleared');
      this.options.onBlockAdded?.();
    }
  }

  /**
   * Handles "Add to Selected" button
   */
  private handleAddToSelected(): void {
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

    this.options.onBlockAdded?.();
  }

  /**
   * Handles undo action
   */
  private handleUndo(): void {
    if (this.editor.undo()) {
      logCallback('Undo');
      this.options.onUndoRedo?.();
    }
  }

  /**
   * Handles redo action
   */
  private handleRedo(): void {
    if (this.editor.redo()) {
      logCallback('Redo');
      this.options.onUndoRedo?.();
    }
  }

  /**
   * Handles export to JSON
   */
  private handleExport(): void {
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

  /**
   * Handles import from JSON
   */
  private handleImport(): void {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'application/json';

    input.onchange = (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;

      const reader = new FileReader();
      reader.onload = (event) => {
        const json = event.target?.result as string;
        this.editor.importJSON(json);
        logCallback('Template imported');
        this.options.onBlockAdded?.();
      };
      reader.readAsText(file);
    };

    input.click();
  }

  /**
   * Updates undo/redo button disabled states
   */
  updateUndoRedoButtons(): void {
    if (this.undoBtn) {
      this.undoBtn.disabled = !this.editor.canUndo();
    }
    if (this.redoBtn) {
      this.redoBtn.disabled = !this.editor.canRedo();
    }
  }

  /**
   * Cleans up event listeners
   */
  destroy(): void {
    document.removeEventListener('keydown', this.keydownHandler);
  }
}
