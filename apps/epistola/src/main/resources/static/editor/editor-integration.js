/**
 * Vanilla Editor Integration
 *
 * Wires the headless editor core with DOM adapters.
 * Import this module and call mountVanillaEditor() to bootstrap.
 *
 * Exposes window.__editor and window.__editorTestData for Stimulus controllers.
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
  let container =
    typeof options.container === 'string'
      ? document.getElementById(options.container)
      : options.container;

  if (!container) {
    throw new Error('Container element not found');
  }

  if (!container.id) {
    container.id = 'vanilla-editor-' + Math.random().toString(36).slice(2);
  }

  const editor = new TemplateEditor({
    template: options.template,
    callbacks: {
      onTemplateChange: options.onChange,
      onError: options.onError,
    },
  });

  // =========================================================================
  // Global state bridge for Stimulus controllers
  // =========================================================================
  window.__editorTestData = editor.store.getTestData() || {};

  // Subscribe to test data changes
  editor.subscribe(() => {
    window.__editorTestData = editor.store.getTestData() || {};
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
  });

  sortableAdapter.setInvalidDropCallback(render);

  const unsubscribe = editor.subscribe(() => {
    render();
  });

  render();

  // Create the public API object
  const publicApi = {
    // Debug access to internal state
    getState: () => editor.getState(),
    subscribe: (callback) => editor.subscribe(callback),
    // Internal editor reference for Stimulus controllers
    store: editor.store,

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
      renderer.destroy();
      sortableAdapter.destroy();
      uiController.destroy();
    },

    // =========================================================================
    // INITIALIZATION (one-time from server)
    // =========================================================================
    setThemes: (themes) => editor.setThemes(themes),
    setDefaultTheme: (theme) => editor.setDefaultTheme(theme),
    setDataExamples: (examples) => editor.setDataExamples(examples),
    setSchema: (schema) => editor.setSchema(schema),

    // =========================================================================
    // DIRTY TRACKING
    // =========================================================================
    markAsSaved: () => editor.markAsSaved(),
    isDirty: () => editor.isDirty(),

    // =========================================================================
    // THEME SELECTION
    // =========================================================================
    updateThemeId: (themeId) => {
      editor.updateThemeId(themeId);
      render();
    },
    getThemes: () => editor.getThemes(),
    getDefaultTheme: () => editor.getDefaultTheme(),

    // =========================================================================
    // DATA EXAMPLES
    // =========================================================================
    selectDataExample: (id) => editor.selectDataExample(id),
    getDataExamples: () => editor.getDataExamples(),
    getSelectedDataExampleId: () => editor.getSelectedDataExampleId(),

    // =========================================================================
    // PAGE SETTINGS & DOCUMENT STYLES
    // =========================================================================
    updatePageSettings: (settings) => {
      editor.updatePageSettings(settings);
      render();
    },
    updateDocumentStyles: (styles) => {
      editor.updateDocumentStyles(styles);
      render();
    },

    // =========================================================================
    // BLOCK OPERATIONS
    // =========================================================================
    findBlock: (blockId) => editor.findBlock(blockId),
    updateBlock: (blockId, updates) => {
      editor.updateBlock(blockId, updates);
      render();
    },
  };

  // Expose editor globally for Stimulus controllers
  window.__editor = publicApi;

  return publicApi;
}
