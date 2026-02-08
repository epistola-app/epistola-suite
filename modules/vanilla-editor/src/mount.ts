/**
 * Mount function and module-level editor access.
 *
 * `mountEditor()` is the main entry point for creating a fully functional editor.
 * `getEditor()` provides module-level access to the TemplateEditor instance
 * for Stimulus controllers, replacing the `window.__editor` pattern.
 *
 * @example
 * ```ts
 * import { mountEditor } from '@epistola/vanilla-editor';
 *
 * const editor = mountEditor({
 *   container: '#editor-root',
 *   template: templateData,
 *   save: { handler: async (t) => fetch('/save', { method: 'POST', body: JSON.stringify(t) }) },
 * });
 *
 * // Later:
 * editor.destroy();
 * ```
 */

import { TemplateEditor } from '@epistola/headless-editor';
import type { Template } from '@epistola/headless-editor';
import { Application } from '@hotwired/stimulus';
import type { MountConfig, MountedEditor } from './types.js';
import { BlockRenderer } from './renderer.js';
import { SortableAdapter } from './sortable-adapter.js';
import { TextBlockController } from './controllers/text-block.js';
import { ExpressionEditorController } from './controllers/expression-editor.js';
import { EditorController } from './controllers/editor-controller.js';
import { installHotkeys } from './hotkeys.js';

/** Module-level editor instance, set during mountEditor() */
let editorInstance: TemplateEditor | null = null;

/** Module-level mount config, set during mountEditor() */
let mountConfig: MountConfig | null = null;

/** Module-level mounted state for double-mount prevention */
let isMounted = false;

/**
 * Get the current TemplateEditor instance.
 * Returns null if no editor has been mounted.
 * Used by Stimulus controllers to access the editor without globals.
 */
export function getEditor(): TemplateEditor | null {
  return editorInstance;
}

/**
 * Set the module-level editor instance.
 * Called internally by mountEditor() — not part of the public API.
 */
export function setEditor(editor: TemplateEditor | null): void {
  editorInstance = editor;
}

/**
 * Get the current mount configuration.
 * Returns null if no editor has been mounted.
 * Used by EditorController to read save handler and other config.
 */
export function getMountConfig(): MountConfig | null {
  return mountConfig;
}

/**
 * Mount a fully functional editor into a container element.
 *
 * Creates a TemplateEditor, registers Stimulus controllers, sets up the
 * renderer, sortable adapter, and hotkey bindings. Subscribes to template
 * changes for automatic re-rendering and optional preview refresh.
 *
 * @throws Error if an editor is already mounted (call destroy() first)
 * @throws Error if the container element cannot be resolved
 */
export function mountEditor(config: MountConfig): MountedEditor {
  if (isMounted) {
    throw new Error('An editor is already mounted. Call destroy() before mounting again.');
  }

  // Resolve container
  const container = resolveContainer(config.container);

  // Create headless editor
  const editor = new TemplateEditor({
    template: config.template,
  });

  // Set module-level references
  editorInstance = editor;
  mountConfig = config;
  isMounted = true;

  // Expose editor to window for E2E tests
  if (typeof window !== 'undefined') {
    (window as any).__editor = editor;
  }

  // Configure themes
  if (config.themes) {
    editor.setThemes(config.themes);
  }
  if (config.defaultTheme) {
    editor.setDefaultTheme(config.defaultTheme);
  }

  // Configure data examples
  if (config.dataExamples) {
    editor.setDataExamples(config.dataExamples);
  }

  // Configure schema
  if (config.schema) {
    editor.setSchema(config.schema);
  }

  // Create renderer and sortable adapter
  const renderer = new BlockRenderer({ container, editor, debug: config.debug });
  const sortableAdapter = new SortableAdapter({
    editor,
    container,
    dragDropPort: editor.getDragDropPort(),
  });

  // Register Stimulus controllers
  const stimulusApp = Application.start();
  stimulusApp.register('text-block', TextBlockController);
  stimulusApp.register('expression-editor', ExpressionEditorController);
  stimulusApp.register('editor', EditorController);

  // Render loop: subscribe to template changes
  // Destroy sortable BEFORE uhtml re-renders to keep DOM clean for diffing,
  // then re-create sortable instances on the freshly rendered DOM.
  const render = () => {
    sortableAdapter.destroy();
    renderer.render();
    sortableAdapter.setup();
  };

  const unsubscribe = editor.subscribe(() => {
    render();
  });

  // Initial render
  render();

  // Install hotkeys
  const cleanupHotkeys = installHotkeys(container);

  // Return public API
  return {
    getTemplate(): Template {
      return editor.getTemplate();
    },

    getEditor(): TemplateEditor {
      return editor;
    },

    destroy(): void {
      cleanupHotkeys();
      unsubscribe();
      sortableAdapter.destroy();
      renderer.destroy();
      stimulusApp.stop();
      editorInstance = null;
      mountConfig = null;
      isMounted = false;
    },
  };
}

/** Resolve a container string or element to an HTMLElement. */
function resolveContainer(container: string | HTMLElement): HTMLElement {
  if (typeof container === 'string') {
    const el = document.querySelector<HTMLElement>(container);
    if (!el) throw new Error(`Container not found: ${container}`);
    return el;
  }
  return container;
}
