/**
 * Template Editor v2 - Framework-Agnostic Document Template Editor
 *
 * This is the public API for mounting and controlling the template editor.
 *
 * @example
 * ```typescript
 * import { mountEditor } from '@epistola/editor-v2';
 *
 * const editor = mountEditor({
 *   container: document.getElementById('editor-root'),
 *   template: myTemplate,
 *   onSave: async (template) => {
 *     await saveToServer(template);
 *   }
 * });
 *
 * // Later, get current state
 * const currentTemplate = editor.getTemplate();
 *
 * // Cleanup
 * editor.unmount();
 * ```
 */

import type { Template, DataExample, ThemeSummary, JsonObject } from "./types/template.ts";

// Re-export types for consumers
export type { Template, DataExample, ThemeSummary } from "./types/template.ts";
export type { SaveStatus } from "./core/persistence.ts";
export type {
  BlockDefinition,
  BlockCategory,
  RenderContext,
  BlockLocation,
} from "./blocks/index.ts";

// Re-export core utilities (for advanced usage)
export { createState, createComputed } from "./core/state.ts";
export {
  createHistory,
  createDebouncedPush,
} from "./core/history.ts";
export {
  createSaveOrchestrator,
  createBeforeUnloadHandler,
} from "./core/persistence.ts";
export {
  AddBlockCommand,
  UpdateBlockCommand,
  DeleteBlockCommand,
  MoveBlockCommand,
  UpdateDocumentStylesCommand,
  UpdatePageSettingsCommand,
  UpdateThemeCommand,
  CompositeCommand,
} from "./core/commands.ts";

// Re-export block registry and tree operations
export {
  registry,
  registerBlock,
  unregisterBlock,
  createBlock,
  getBlockLabel,
  getBlockIcon,
  canContain,
  findBlock,
  findBlockLocation,
  getChildren,
  updateBlock,
  insertBlock,
  removeBlock,
  moveBlock,
  walkTree,
  countBlocks,
  findBlocksByType,
  getBlockPath,
  registerContainerBlock,
} from "./blocks/index.ts";

// Re-export DOM rendering layer
export {
  // Selection
  createSelectionManager,
  SELECTION_CLASSES,
  applySelectionClasses,
  handleSelectionClick,
  // Drag-and-drop
  createDndManager,
  DND_CLASSES,
  createDragHandle,
  calculateInsertIndex,
  // Renderer
  createRenderer,
  RENDERER_CLASSES,
  focusBlock,
  getBlockIdFromEvent,
} from "./dom/index.ts";
export type {
  SelectionState,
  SelectionListener,
  SelectionManager,
  DropPosition,
  DropTarget,
  DragData,
  DropCallback,
  DndManager,
  RenderMode,
  EditorRenderContext,
  EditorRenderer,
} from "./dom/index.ts";

/**
 * Options for mounting the template editor.
 */
export interface EditorOptions {
  /** The DOM element to mount the editor into */
  container: HTMLElement;

  /** Initial template to load (optional) */
  template?: Template;

  /** Initial data examples for testing expressions (optional) */
  dataExamples?: DataExample[];

  /** Initial data model/schema for validation (optional) */
  dataModel?: JsonObject | null;

  /** Available themes for selection in the editor (optional) */
  themes?: ThemeSummary[];

  /** The parent template's default theme (for showing inherited theme in UI) */
  defaultTheme?: ThemeSummary | null;

  /** Callback when user clicks Save */
  onSave?: (template: Template) => void | Promise<void>;

  /** Callback when user selects a different example */
  onExampleSelected?: (exampleId: string | null) => void;

  /** Debounce delay for auto-save in milliseconds (default: 1000) */
  autoSaveDelay?: number;
}

/**
 * Result of mounting the editor.
 */
export interface EditorInstance {
  /** Unmount the editor and clean up */
  unmount: () => void;

  /** Get the current template state */
  getTemplate: () => Template;

  /** Set/replace the template */
  setTemplate: (template: Template) => void;

  /** Check if there are unsaved changes */
  isDirty: () => boolean;

  /** Trigger immediate save (bypasses debounce) */
  saveNow: () => Promise<void>;

  /** Undo the last change */
  undo: () => void;

  /** Redo the last undone change */
  redo: () => void;

  /** Check if undo is available */
  canUndo: () => boolean;

  /** Check if redo is available */
  canRedo: () => boolean;
}

/**
 * Mount the template editor into a container element.
 *
 * NOTE: This is currently a stub implementation for Phase 1.
 * Full rendering will be implemented in later phases.
 *
 * @param options Editor configuration options
 * @returns An editor instance with control methods
 */
export function mountEditor(options: EditorOptions): EditorInstance {
  const { container, template, onSave } = options;

  // Validate container
  if (!container || !(container instanceof HTMLElement)) {
    throw new Error("mountEditor requires a valid HTMLElement container");
  }

  // For Phase 1, we just set up the core infrastructure
  // Full rendering will come in later phases

  // Add root class for CSS scoping
  container.classList.add("template-editor-v2-root");

  // Create default template if not provided
  let currentTemplate: Template = template ?? {
    id: crypto.randomUUID(),
    name: "Untitled Template",
    version: 1,
    pageSettings: {
      format: "A4",
      orientation: "portrait",
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    blocks: [],
    documentStyles: {},
  };

  // Track if mounted
  let isMounted = true;

  // Placeholder save function
  const doSave = async () => {
    if (onSave) {
      await onSave(currentTemplate);
    }
  };

  // Stub: Display a placeholder message (will be replaced with real UI)
  container.innerHTML = `
    <div style="padding: 20px; font-family: system-ui, sans-serif; color: #666;">
      <h2 style="margin: 0 0 10px 0; color: #333;">Template Editor v2</h2>
      <p style="margin: 0;">Phase 3 complete: DOM rendering layer ready.</p>
      <p style="margin: 10px 0 0 0; font-size: 14px;">
        Template: <strong>${currentTemplate.name}</strong>
      </p>
      <p style="margin: 5px 0 0 0; font-size: 12px; color: #999;">
        Block implementations will be added in Phase 4.
      </p>
    </div>
  `;

  // Stub history state
  let historyCanUndo = false;
  let historyCanRedo = false;

  return {
    unmount: () => {
      if (!isMounted) return;
      isMounted = false;
      container.innerHTML = "";
      container.classList.remove("template-editor-v2-root");
    },

    getTemplate: () => currentTemplate,

    setTemplate: (newTemplate: Template) => {
      currentTemplate = newTemplate;
      // In later phases, this will trigger re-render
    },

    isDirty: () => {
      // Stub: always false until persistence is wired up
      return false;
    },

    saveNow: doSave,

    undo: () => {
      // Stub: will be implemented with history integration
      console.log("[editor-v2] undo() called (stub)");
    },

    redo: () => {
      // Stub: will be implemented with history integration
      console.log("[editor-v2] redo() called (stub)");
    },

    canUndo: () => historyCanUndo,

    canRedo: () => historyCanRedo,
  };
}
