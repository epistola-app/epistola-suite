/**
 * Vanilla Editor Integration
 *
 * Wires the headless editor core with DOM adapters.
 * Import this module and call mountVanillaEditor() to bootstrap.
 */

import { TemplateEditor, defaultBlockDefinitions } from '/headless-editor/headless-editor.js';
import { BlockRenderer } from './adapters/renderer.js';
import { SortableAdapter } from './adapters/sortable-adapter.js';
import { UIController } from './adapters/ui-controller.js';

/**
 * Mount the vanilla editor.
 *
 * @param {object} options
 * @param {string|HTMLElement} options.container - Container element or ID
 * @param {object} [options.template] - Initial template data
 * @param {(template: object) => void} [options.onChange] - Called on every template change
 * @param {(error: Error) => void} [options.onError] - Called on errors
 * @param {boolean} [options.debug] - Enable debug state panel
 * @returns {{ getTemplate: () => object, setTemplate: (t: object) => void, undo: () => boolean, redo: () => boolean, destroy: () => void }}
 */
export function mountVanillaEditor(options) {
  const container =
    typeof options.container === 'string'
      ? document.getElementById(options.container)
      : options.container;

  if (!container) {
    throw new Error('Container element not found');
  }

  const editor = new TemplateEditor({
    template: options.template,
    callbacks: {
      onTemplateChange: options.onChange,
      onError: options.onError,
    },
  });

  const renderer = new BlockRenderer(editor, container.id, options.debug);
  const sortableAdapter = new SortableAdapter(editor, container.id);

  const render = () => {
    renderer.render();
    renderer.renderState();
    sortableAdapter.setup();
    uiController.updateUndoRedoButtons();
  };

  const uiController = new UIController(editor, {
    onUndoRedo: render,
    onBlockAdded: render,
  });

  sortableAdapter.setInvalidDropCallback(render);

  const unsubscribe = editor.subscribe(() => {
    render();
  });

  render();

  return {
    getTemplate: () => editor.getTemplate(),
    setTemplate: (template) => {
      editor.setTemplate(template);
      render();
    },
    undo: () => editor.undo(),
    redo: () => editor.redo(),
    canUndo: () => editor.canUndo(),
    canRedo: () => editor.canRedo(),
    exportJSON: () => editor.exportJSON(),
    importJSON: (json) => {
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
