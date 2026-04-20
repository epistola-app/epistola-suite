/**
 * @module @epistola/editor/theme-editor
 *
 * Epistola Theme Editor — Lit-based theme editor with visual style controls.
 *
 * Public API:
 *   mountThemeEditor(options)  → ThemeEditorInstance
 */

import './theme-editor/theme-editor.css';
import './theme-editor/EpistolaThemeEditor.js';
import type { EpistolaThemeEditor } from './theme-editor/EpistolaThemeEditor.js';
import type { ThemeData } from './theme-editor/ThemeEditorState.js';

export type { ThemeData } from './theme-editor/ThemeEditorState.js';

// ---------------------------------------------------------------------------
// Public mount API
// ---------------------------------------------------------------------------

export interface ThemeEditorOptions {
  /** DOM element to mount the editor into */
  container: HTMLElement;
  /** Initial theme data from the backend */
  theme: ThemeData;
  /** Callback when saving — receives the minimal PATCH payload */
  onSave: (payload: object) => Promise<void>;
  /** When true, all inputs are disabled and saving is suppressed */
  readonly?: boolean;
}

export interface ThemeEditorInstance {
  /** Tear down the editor and clean up */
  unmount(): void;
}

/**
 * Mount the theme editor into a DOM element.
 */
export function mountThemeEditor(options: ThemeEditorOptions): ThemeEditorInstance {
  const { container, theme, onSave, readonly } = options;

  const editorEl = document.createElement('epistola-theme-editor') as EpistolaThemeEditor;
  editorEl.style.display = 'block';

  editorEl.init(theme, onSave, readonly);

  container.innerHTML = '';
  container.appendChild(editorEl);

  return {
    unmount() {
      editorEl.remove();
    },
  };
}
