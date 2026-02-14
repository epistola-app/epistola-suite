/**
 * @module @epistola/editor-v2
 *
 * Epistola Template Editor V2 — Lit-based editor with headless engine.
 *
 * Public API:
 *   mountEditor(options)  → EditorInstance
 *   unmountEditor(instance)
 */

export type { TemplateDocument, Node, Slot, NodeId, SlotId } from './types/index.js'

// ---------------------------------------------------------------------------
// Public mount API (matches V1 contract shape)
// ---------------------------------------------------------------------------

export interface EditorOptions {
  /** DOM element to mount the editor into */
  container: HTMLElement
  /** Initial template document (node/slot model) */
  template?: import('./types/model.js').TemplateDocument
  /** Callback when the template is saved */
  onSave?: (template: import('./types/model.js').TemplateDocument) => Promise<void>
}

export interface EditorInstance {
  /** Tear down the editor and clean up */
  unmount(): void
  /** Get the current template document */
  getTemplate(): import('./types/model.js').TemplateDocument
  /** Replace the template document */
  setTemplate(template: import('./types/model.js').TemplateDocument): void
}

/**
 * Mount the editor into a DOM element.
 * Placeholder — will be implemented in Phase 2 (UI).
 */
export function mountEditor(_options: EditorOptions): EditorInstance {
  // TODO: Phase 2 — create <epistola-editor> element, wire engine, mount
  throw new Error('Editor V2 mount not yet implemented. See Phase 2 in the plan.')
}
