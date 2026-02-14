/**
 * @module @epistola/editor-v2
 *
 * Epistola Template Editor V2 — Lit-based editor with headless engine.
 *
 * Public API:
 *   mountEditor(options)  → EditorInstance
 */

import './editor.css'
import './ui/EpistolaEditor.js'
import type { EpistolaEditor } from './ui/EpistolaEditor.js'
import type { TemplateDocument, NodeId, SlotId } from './types/index.js'
import { createDefaultRegistry } from './engine/registry.js'
import { nanoid } from 'nanoid'

export type { TemplateDocument, Node, Slot, NodeId, SlotId } from './types/index.js'
export { EditorEngine } from './engine/EditorEngine.js'
export { createDefaultRegistry, ComponentRegistry } from './engine/registry.js'

// ---------------------------------------------------------------------------
// Public mount API
// ---------------------------------------------------------------------------

export interface EditorOptions {
  /** DOM element to mount the editor into */
  container: HTMLElement
  /** Initial template document (node/slot model) */
  template?: TemplateDocument
  /** Callback when the template is saved */
  onSave?: (template: TemplateDocument) => Promise<void>
  /** JSON Schema describing the data model (for expression autocomplete) */
  dataModel?: object
  /** Example data objects for previewing expressions */
  dataExamples?: object[]
}

export interface EditorInstance {
  /** Tear down the editor and clean up */
  unmount(): void
  /** Get the current template document */
  getTemplate(): TemplateDocument
  /** Replace the template document */
  setTemplate(template: TemplateDocument): void
}

/**
 * Create an empty template document with a root container.
 */
export function createEmptyDocument(): TemplateDocument {
  const rootId = nanoid() as NodeId
  const rootSlotId = nanoid() as SlotId

  return {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: {
        id: rootId,
        type: 'root',
        slots: [rootSlotId],
      },
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootId,
        name: 'children',
        children: [],
      },
    },
    themeRef: { type: 'inherit' },
  }
}

/**
 * Mount the editor into a DOM element.
 */
export function mountEditor(options: EditorOptions): EditorInstance {
  const { container, template, dataModel, dataExamples } = options
  const doc = template ?? createEmptyDocument()

  // Create the custom element
  const editorEl = document.createElement('epistola-editor') as EpistolaEditor
  editorEl.style.height = '100%'
  editorEl.style.width = '100%'
  editorEl.style.display = 'block'

  // Initialize the engine with data model context
  editorEl.initEngine(doc, createDefaultRegistry(), { dataModel, dataExamples })

  // Mount into the container
  container.innerHTML = ''
  container.appendChild(editorEl)

  return {
    unmount() {
      editorEl.remove()
    },
    getTemplate() {
      return editorEl.engine!.doc
    },
    setTemplate(newDoc: TemplateDocument) {
      editorEl.engine!.replaceDocument(newDoc)
    },
  }
}
