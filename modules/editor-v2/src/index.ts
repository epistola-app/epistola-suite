/**
 * Template Editor v2 - Framework-Agnostic Document Template Editor
 *
 * This is the public API for mounting and controlling the template editor.
 *
 * @example
 * ```typescript
 * import { mountEditor, registerAllBlocks } from '@epistola/editor-v2';
 *
 * // Register all block types
 * registerAllBlocks();
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

// Import CSS for bundling
import "./ui/styles.css";

import type {
  Template,
  DataExample,
  ThemeSummary,
  JsonObject,
  DocumentStyles,
} from "./types/template.ts";
import type { CSSStyles } from "./types/styles.ts";
import { createState } from "./core/state.ts";
import { createHistory } from "./core/history.ts";
import {
  createSaveOrchestrator,
  createBeforeUnloadHandler,
  type SaveStatus,
} from "./core/persistence.ts";
import {
  AddBlockCommand,
  UpdateBlockCommand,
  DeleteBlockCommand,
  MoveBlockCommand,
  UpdateDocumentStylesCommand,
} from "./core/commands.ts";
import { findBlock, findBlockLocation } from "./blocks/tree.ts";
import { createRenderer } from "./dom/renderer.ts";
import { createPalette } from "./ui/palette.ts";
import { createSidebar } from "./ui/sidebar.ts";
import { createToolbar } from "./ui/toolbar.ts";
import { registry, createBlock, registerAllBlocks } from "./blocks/index.ts";

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

// Re-export headless editor API
export { createEditor } from "./core/editor.ts";
export type { Editor, EditorOptions as HeadlessEditorOptions, ChangeType, ChangeListener } from "./core/editor.ts";

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
  registerAllBlocks,
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

// Re-export UI components
export {
  createPalette,
  createSidebar,
  createToolbar,
  createUnitInput,
  createColorInput,
  createSpacingInput,
  createSelectInput,
} from "./ui/index.ts";
export type {
  Palette,
  PaletteOptions,
  Sidebar,
  SidebarOptions,
  Toolbar,
  ToolbarOptions,
} from "./ui/index.ts";

// Re-export rich text module
export {
  createRichTextEditor,
  contentToHTML,
  contentToText,
  createEmptyContent,
  createTextContent,
  ExpressionNode,
} from "./richtext/index.ts";
export type {
  RichTextEditor,
  RichTextEditorOptions,
  ExpressionEvaluator,
  ExpressionNodeOptions,
} from "./richtext/index.ts";

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

  /** Whether to auto-register all block types (default: true) */
  registerBlocks?: boolean;
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
 * Editor internal state.
 */
interface EditorState {
  template: Template;
  selectedBlockId: string | null;
  dataExamples: DataExample[];
  selectedExampleId: string | null;
}

/**
 * Mount the template editor into a container element.
 *
 * @param options Editor configuration options
 * @returns An editor instance with control methods
 */
export function mountEditor(options: EditorOptions): EditorInstance {
  const {
    container,
    template,
    dataExamples = [],
    onSave,
    autoSaveDelay = 1000,
    registerBlocks = true,
  } = options;

  // Validate container
  if (!container || !(container instanceof HTMLElement)) {
    throw new Error("mountEditor requires a valid HTMLElement container");
  }

  // Register block types if requested
  if (registerBlocks && registry.size === 0) {
    registerAllBlocks();
  }

  // Create default template if not provided
  const defaultTemplate: Template = {
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

  // Initialize state
  const state = createState<EditorState>({
    template: template ?? defaultTemplate,
    selectedBlockId: null,
    dataExamples,
    selectedExampleId: null,
  });

  // Initialize history
  const history = createHistory({ limit: 100 });

  // Initialize save orchestrator
  const saveOrchestrator = createSaveOrchestrator({
    debounceDelay: autoSaveDelay,
    getTemplate: () => state.getState().template,
    save: async (tmpl) => {
      if (onSave) {
        await onSave(tmpl);
      }
    },
  });

  // Setup before unload handler
  const removeBeforeUnload = createBeforeUnloadHandler(saveOrchestrator);

  // Track mount state
  let isMounted = true;

  // Create editor layout
  container.classList.add("ev2-editor");
  container.innerHTML = `
    <div class="ev2-editor__toolbar"></div>
    <div class="ev2-editor__main">
      <div class="ev2-editor__palette"></div>
      <div class="ev2-editor__canvas"></div>
      <div class="ev2-editor__sidebar"></div>
    </div>
  `;

  // Get layout elements
  const toolbarEl = container.querySelector(".ev2-editor__toolbar") as HTMLElement;
  const paletteEl = container.querySelector(".ev2-editor__palette") as HTMLElement;
  const canvasEl = container.querySelector(".ev2-editor__canvas") as HTMLElement;
  const sidebarEl = container.querySelector(".ev2-editor__sidebar") as HTMLElement;

  // Execute command and push to history
  function executeCommand(
    command: AddBlockCommand | UpdateBlockCommand | DeleteBlockCommand | MoveBlockCommand | UpdateDocumentStylesCommand,
  ): void {
    const currentState = state.getState();
    const stateBefore = currentState.template;
    const newTemplate = command.execute(currentState.template);
    state.setState({ template: newTemplate });
    history.push(command, stateBefore);
    saveOrchestrator.markDirty();
  }

  // Create toolbar
  const toolbar = createToolbar({
    container: toolbarEl,
    templateName: state.getState().template.name,
    onUndo: () => {
      const prevState = history.undo();
      if (prevState) {
        state.setState({ template: prevState });
        saveOrchestrator.markDirty();
      }
    },
    onRedo: () => {
      const currentState = state.getState();
      const newState = history.redo(currentState.template);
      if (newState) {
        state.setState({ template: newState });
        saveOrchestrator.markDirty();
      }
    },
    onSave: () => saveOrchestrator.saveNow(),
  });

  // Create renderer first (it has the DnD manager)
  const renderer = createRenderer({
    root: canvasEl,
    template: state.getState().template,
    data: {},
    mode: "edit",
    onBlockClick: (blockId: string, _event: MouseEvent) => {
      state.setState({ selectedBlockId: blockId });
    },
    onBlockChange: (blockId: string, updates: Record<string, unknown>) => {
      const command = new UpdateBlockCommand(blockId, updates);
      executeCommand(command);
    },
    onBlockMove: (
      blockId: string,
      targetBlockId: string | null,
      position: "before" | "after" | "inside",
      _containerId?: string | null,
    ) => {
      // For "inside" position, insert into the target block's children at index 0
      // For "before"/"after", insert relative to the target block
      if (position === "inside") {
        const command = new MoveBlockCommand(blockId, targetBlockId, 0);
        executeCommand(command);
      } else {
        // Find the target's parent and calculate the correct index
        const template = state.getState().template;
        const targetLocation = targetBlockId
          ? findBlockLocation(template.blocks, targetBlockId)
          : null;

        if (targetLocation) {
          const parentId = targetLocation.parent?.id ?? null;
          const targetIndex = targetLocation.index;
          const insertIndex = position === "after" ? targetIndex + 1 : targetIndex;
          const command = new MoveBlockCommand(blockId, parentId, insertIndex);
          executeCommand(command);
        } else {
          // Target not found or inserting at root - insert at end
          const insertIndex = position === "after" ? template.blocks.length : 0;
          const command = new MoveBlockCommand(blockId, null, insertIndex);
          executeCommand(command);
        }
      }
    },
    onBlockAdd: (
      blockType: string,
      targetBlockId: string | null,
      position: "before" | "after" | "inside",
      _containerId?: string | null,
    ) => {
      const newBlock = createBlock(blockType as any);
      if (!newBlock) return;

      // For "inside" position, insert into the target block's children at index 0
      // For "before"/"after", insert relative to the target block
      if (position === "inside") {
        const command = new AddBlockCommand(newBlock, targetBlockId, 0);
        executeCommand(command);
      } else {
        const template = state.getState().template;
        const targetLocation = targetBlockId
          ? findBlockLocation(template.blocks, targetBlockId)
          : null;

        if (targetLocation) {
          const parentId = targetLocation.parent?.id ?? null;
          const targetIndex = targetLocation.index;
          const insertIndex = position === "after" ? targetIndex + 1 : targetIndex;
          const command = new AddBlockCommand(newBlock, parentId, insertIndex);
          executeCommand(command);
        } else {
          // Insert at root level
          const insertIndex = position === "after" ? template.blocks.length : 0;
          const command = new AddBlockCommand(newBlock, null, insertIndex);
          executeCommand(command);
        }
      }
    },
  });

  // Create palette with DnD manager from renderer
  const palette = createPalette({
    container: paletteEl,
    dnd: renderer.getContext().dnd,
  });

  // Create sidebar
  const sidebar = createSidebar({
    container: sidebarEl,
    onDocumentStylesChange: (styles: Partial<DocumentStyles>) => {
      const command = new UpdateDocumentStylesCommand(styles);
      executeCommand(command);
    },
    onBlockStylesChange: (blockId: string, styles: Partial<CSSStyles>) => {
      const command = new UpdateBlockCommand(blockId, { styles });
      executeCommand(command);
    },
  });

  // Initial render
  renderer.render();

  // Subscribe to state changes
  const unsubscribeState = state.subscribe((newState, _prevState) => {
    // Update renderer context and re-render
    renderer.getContext().template = newState.template;
    renderer.render();

    // Update sidebar with selected block
    if (newState.selectedBlockId) {
      const block = findBlock(newState.template.blocks, newState.selectedBlockId);
      sidebar.setSelectedBlock(block ?? null);
    } else {
      sidebar.setSelectedBlock(null);
      sidebar.setDocumentStyles(newState.template.documentStyles);
    }
  });

  // Subscribe to history changes
  const unsubscribeHistory = history.subscribe((canUndo, canRedo) => {
    toolbar.setUndoRedo(canUndo, canRedo);
  });

  // Subscribe to save status changes
  const unsubscribeSave = saveOrchestrator.onStatusChange((status: SaveStatus) => {
    toolbar.setSaveStatus(status);
  });

  // Initial sidebar state
  sidebar.setDocumentStyles(state.getState().template.documentStyles);

  return {
    unmount: () => {
      if (!isMounted) return;
      isMounted = false;

      // Cleanup subscriptions
      unsubscribeState();
      unsubscribeHistory();
      unsubscribeSave();
      removeBeforeUnload();

      // Cleanup components
      toolbar.destroy();
      palette.destroy();
      sidebar.destroy();
      renderer.dispose();
      saveOrchestrator.dispose();

      // Clear container
      container.innerHTML = "";
      container.classList.remove("ev2-editor");
    },

    getTemplate: () => state.getState().template,

    setTemplate: (newTemplate: Template) => {
      state.setState({ template: newTemplate, selectedBlockId: null });
      history.clear();
      saveOrchestrator.markSaved();
    },

    isDirty: () => saveOrchestrator.getStatus() === "dirty",

    saveNow: () => saveOrchestrator.saveNow(),

    undo: () => {
      const prevState = history.undo();
      if (prevState) {
        state.setState({ template: prevState });
        saveOrchestrator.markDirty();
      }
    },

    redo: () => {
      const currentState = state.getState();
      const newState = history.redo(currentState.template);
      if (newState) {
        state.setState({ template: newState });
        saveOrchestrator.markDirty();
      }
    },

    canUndo: () => history.canUndo(),

    canRedo: () => history.canRedo(),
  };
}
