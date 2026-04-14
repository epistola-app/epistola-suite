/**
 * @module @epistola/editor/data-contract-editor
 *
 * Epistola Data Contract Editor — Lit-based schema and example editor.
 *
 * Public API:
 *   mountDataContractEditor(options)  → DataContractEditorInstance
 */

import './data-contract/data-contract-editor.css';
import './data-contract/EpistolaDataContractEditor.js';
import type { EpistolaDataContractEditor } from './data-contract/EpistolaDataContractEditor.js';
import type { DataExample, JsonSchema, SaveCallbacks } from './data-contract/types.js';

export type { DataExample, JsonSchema, SaveCallbacks } from './data-contract/types.js';

// ---------------------------------------------------------------------------
// Public mount API
// ---------------------------------------------------------------------------

export interface DataContractEditorOptions {
  /** DOM element to mount the editor into */
  container: HTMLElement;
  /** Template ID (for context) */
  templateId: string;
  /** Initial JSON Schema from the backend (null if none defined) */
  initialSchema: JsonSchema | null;
  /** Initial data examples from the backend */
  initialExamples: DataExample[];
  /** Callbacks for saving schema, examples, etc. */
  callbacks: SaveCallbacks;
  /** When true, all editing controls are disabled */
  readonly?: boolean;
}

export interface DataContractEditorInstance {
  /** Tear down the editor and clean up */
  unmount(): void;
}

/**
 * Mount the data contract editor into a DOM element.
 */
export function mountDataContractEditor(
  options: DataContractEditorOptions,
): DataContractEditorInstance {
  const { container, initialSchema, initialExamples, callbacks, readonly = false } = options;

  const editorEl = document.createElement(
    'epistola-data-contract-editor',
  ) as EpistolaDataContractEditor;
  editorEl.style.display = 'block';

  editorEl.init(initialSchema, initialExamples, callbacks, readonly);

  container.innerHTML = '';
  container.appendChild(editorEl);

  return {
    unmount() {
      editorEl.remove();
    },
  };
}
