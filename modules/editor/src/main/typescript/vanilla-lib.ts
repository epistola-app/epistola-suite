/**
 * Vanilla Editor Library
 *
 * Public API for mounting the vanilla editor.
 */

import { TemplateEditor } from './core/editor.js';
import { BlockRenderer } from './adapters/vanilla/renderer.js';
import { SortableAdapter } from './adapters/vanilla/sortable-adapter.js';
import { UIController } from './adapters/vanilla/ui-controller.js';
import type { Template, EditorCallbacks } from './core/types.js';

/**
 * Editor mount options
 */
export interface EditorOptions {
  /** Container element or ID where the editor will be mounted */
  container: HTMLElement | string;
  /** Initial template data */
  template?: Template;
  /** Callback when template is saved */
  onSave?: (template: Template) => void | Promise<void>;
  /** Callback when an error occurs */
  onError?: (error: Error) => void;
  /** Callback when template changes */
  onChange?: (template: Template) => void;
  /** Enable debug mode (shows state panel) */
  debug?: boolean;
}

/**
 * Editor instance interface
 */
export interface EditorInstance {
  /** Get the current template */
  getTemplate(): Template;
  /** Set/replace the template */
  setTemplate(template: Template): void;
  /** Undo the last action */
  undo(): boolean;
  /** Redo the last undone action */
  redo(): boolean;
  /** Check if undo is available */
  canUndo(): boolean;
  /** Check if redo is available */
  canRedo(): boolean;
  /** Export template to JSON string */
  exportJSON(): string;
  /** Import template from JSON string */
  importJSON(json: string): void;
  /** Destroy the editor instance */
  destroy(): void;
}

/**
 * Mount the vanilla editor
 *
 * @param options - Editor options
 * @returns Editor instance
 *
 * @example
 * ```typescript
 * const editor = mountEditor({
 *   container: document.getElementById('editor')!,
 *   onSave: async (template) => {
 *     await fetch('/api/templates', {
 *       method: 'POST',
 *       body: JSON.stringify(template),
 *     });
 *   },
 * });
 *
 * // Later...
 * const template = editor.getTemplate();
 * editor.destroy();
 * ```
 */
export function mountEditor(options: EditorOptions): EditorInstance {
  // Resolve container
  const container =
    typeof options.container === 'string'
      ? document.getElementById(options.container)
      : options.container;

  if (!container) {
    throw new Error('Container element not found');
  }

  // Setup callbacks
  const callbacks: EditorCallbacks = {
    onTemplateChange: options.onChange,
    onError: options.onError,
  };

  // Create editor
  const editor = new TemplateEditor({
    template: options.template,
    callbacks,
  });

  // Create renderer
  const renderer = new BlockRenderer(editor, container.id, options.debug);

  // Create sortable adapter
  const sortableAdapter = new SortableAdapter(editor, container.id);

  // Render function
  const render = () => {
    renderer.render();
    renderer.renderState();
    sortableAdapter.setup();
    uiController.updateUndoRedoButtons();
  };

  // Create UI controller
  const uiController = new UIController(editor, {
    onUndoRedo: render,
    onBlockAdded: render,
  });

  // Set invalid drop callback
  sortableAdapter.setInvalidDropCallback(render);

  // Subscribe to changes
  const unsubscribe = editor.subscribe(() => {
    render();
  });

  // Initial render
  render();

  // Return instance
  return {
    getTemplate: () => editor.getTemplate(),
    setTemplate: (template: Template) => {
      editor.setTemplate(template);
      render();
    },
    undo: () => editor.undo(),
    redo: () => editor.redo(),
    canUndo: () => editor.canUndo(),
    canRedo: () => editor.canRedo(),
    exportJSON: () => editor.exportJSON(),
    importJSON: (json: string) => {
      editor.importJSON(json);
      render();
    },
    destroy: () => {
      unsubscribe();
      sortableAdapter.destroy();
      uiController.destroy();
    },
  };
}

// Re-export core types for convenience
export type { Template, Block, BlockType, EditorState } from './core/types.js';

// Re-export the core editor for advanced use cases
export { TemplateEditor } from './core/editor.js';
export { BlockRenderer } from './adapters/vanilla/renderer.js';
export { SortableAdapter } from './adapters/vanilla/sortable-adapter.js';
export { UIController } from './adapters/vanilla/ui-controller.js';
