/**
 * Headless Editor API.
 *
 * Provides a framework-agnostic editor that can be used without DOM.
 * This is the core state management layer that the UI builds upon.
 *
 * @example
 * ```typescript
 * import { createEditor } from '@epistola/editor-v2';
 *
 * const editor = createEditor({
 *   template: myTemplate,
 *   onSave: async (template) => saveToServer(template),
 * });
 *
 * // Mutate state
 * editor.addBlock("text", null, 0);
 * editor.updateBlock(blockId, { styles: { color: "red" } });
 *
 * // Subscribe to changes (for preview updates)
 * editor.onChange((template, changeType) => {
 *   renderPreview(template);
 * });
 *
 * // Cleanup
 * editor.dispose();
 * ```
 */

import type { Block, Template } from "../types/template.ts";
import { createState } from "./state.ts";
import { createHistory } from "./history.ts";
import {
  createSaveOrchestrator,
  type SaveStatus,
} from "./persistence.ts";
import {
  AddBlockCommand,
  UpdateBlockCommand,
  DeleteBlockCommand,
  MoveBlockCommand,
  UpdateDocumentStylesCommand,
  UpdatePageSettingsCommand,
  UpdateThemeCommand,
  CompositeCommand,
  type Command,
} from "./commands.ts";
import { createBlock } from "../blocks/index.ts";
import type { BlockType } from "../types/template.ts";
import type { DocumentStyles, PageSettings } from "../types/template.ts";

// ============================================================================
// Types
// ============================================================================

/**
 * Types of changes that can occur in the editor.
 * Used by onChange listeners to determine what changed.
 */
export type ChangeType =
  | "block-added"
  | "block-updated"
  | "block-deleted"
  | "block-moved"
  | "document-styles"
  | "page-settings"
  | "theme"
  | "selection"
  | "template-replaced"
  | "undo"
  | "redo";

/**
 * Listener for template changes.
 */
export type ChangeListener = (template: Template, changeType: ChangeType) => void;

/**
 * Listener for save status changes.
 */
export type SaveStatusListener = (status: SaveStatus) => void;

/**
 * Options for creating a headless editor.
 */
export interface EditorOptions {
  /** Initial template (optional - creates default if not provided) */
  template?: Template;

  /** Callback when save is triggered */
  onSave?: (template: Template) => void | Promise<void>;

  /** Debounce delay for auto-save in milliseconds (default: 1000) */
  autoSaveDelay?: number;

  /** Maximum history entries (default: 100) */
  historyLimit?: number;
}

/**
 * Internal editor state.
 */
interface EditorState {
  template: Template;
  selectedBlockId: string | null;
}

/**
 * Headless editor interface.
 *
 * Provides all editor functionality without any DOM dependencies.
 * Can be used directly for headless scenarios or wrapped by UI components.
 */
export interface Editor {
  // ============================================================================
  // State Access
  // ============================================================================

  /** Get the current template state */
  getTemplate(): Template;

  /** Get the currently selected block ID */
  getSelectedBlockId(): string | null;

  /** Set the selected block ID */
  setSelectedBlockId(blockId: string | null): void;

  // ============================================================================
  // Block Mutations
  // ============================================================================

  /**
   * Add a new block to the template.
   * @param type Block type to create
   * @param parentId Parent block ID, or null for root level
   * @param index Position to insert at
   * @returns The created block, or null if creation failed
   */
  addBlock(type: BlockType, parentId: string | null, index: number): Block | null;

  /**
   * Update a block's properties.
   * @param blockId Block ID to update
   * @param updates Partial block updates to apply
   */
  updateBlock(blockId: string, updates: Partial<Block>): void;

  /**
   * Delete a block from the template.
   * @param blockId Block ID to delete
   */
  deleteBlock(blockId: string): void;

  /**
   * Move a block to a new location.
   * @param blockId Block ID to move
   * @param newParentId New parent block ID, or null for root level
   * @param newIndex New position within the parent
   */
  moveBlock(blockId: string, newParentId: string | null, newIndex: number): void;

  // ============================================================================
  // Document Mutations
  // ============================================================================

  /** Update document styles */
  updateDocumentStyles(styles: Partial<DocumentStyles>): void;

  /** Update page settings */
  updatePageSettings(settings: Partial<PageSettings>): void;

  /** Update theme ID */
  updateTheme(themeId: string | null): void;

  // ============================================================================
  // Batch Operations
  // ============================================================================

  /**
   * Execute multiple commands as a single undoable operation.
   * @param commands Array of commands to execute
   * @param description Optional description for the composite command
   */
  batch(commands: Command<Template>[], description?: string): void;

  // ============================================================================
  // History
  // ============================================================================

  /** Undo the last change. Returns true if undo was successful. */
  undo(): boolean;

  /** Redo the last undone change. Returns true if redo was successful. */
  redo(): boolean;

  /** Check if undo is available */
  canUndo(): boolean;

  /** Check if redo is available */
  canRedo(): boolean;

  /** Clear history */
  clearHistory(): void;

  // ============================================================================
  // Persistence
  // ============================================================================

  /** Check if there are unsaved changes */
  isDirty(): boolean;

  /** Get current save status */
  getSaveStatus(): SaveStatus;

  /** Trigger immediate save (bypasses debounce) */
  saveNow(): Promise<void>;

  /** Mark the current state as saved (e.g., after external save) */
  markSaved(): void;

  // ============================================================================
  // Template Replacement
  // ============================================================================

  /**
   * Replace the entire template.
   * Clears history and marks as saved.
   * @param template New template to load
   */
  setTemplate(template: Template): void;

  // ============================================================================
  // Subscriptions
  // ============================================================================

  /**
   * Subscribe to template changes.
   * This is the primary hook for updating previews or other UI.
   * @param listener Callback when template changes
   * @returns Unsubscribe function
   */
  onChange(listener: ChangeListener): () => void;

  /**
   * Subscribe to save status changes.
   * @param listener Callback when save status changes
   * @returns Unsubscribe function
   */
  onSaveStatusChange(listener: SaveStatusListener): () => void;

  // ============================================================================
  // Lifecycle
  // ============================================================================

  /** Dispose the editor and clean up resources */
  dispose(): void;
}

// ============================================================================
// Implementation
// ============================================================================

/**
 * Create a default empty template.
 */
function createDefaultTemplate(): Template {
  return {
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
}

/**
 * Create a headless editor instance.
 */
export function createEditor(options: EditorOptions = {}): Editor {
  const {
    template,
    onSave,
    autoSaveDelay = 1000,
    historyLimit = 100,
  } = options;

  // ============================================================================
  // Internal State
  // ============================================================================

  const state = createState<EditorState>({
    template: template ?? createDefaultTemplate(),
    selectedBlockId: null,
  });

  const history = createHistory({ limit: historyLimit });

  const saveOrchestrator = createSaveOrchestrator({
    debounceDelay: autoSaveDelay,
    getTemplate: () => state.getState().template,
    save: async (tmpl) => {
      if (onSave) {
        await onSave(tmpl);
      }
    },
  });

  // Change listeners
  const changeListeners = new Set<ChangeListener>();

  // Track if disposed
  let disposed = false;

  // ============================================================================
  // Internal Helpers
  // ============================================================================

  function assertNotDisposed(): void {
    if (disposed) {
      throw new Error("Editor has been disposed");
    }
  }

  function notifyChange(changeType: ChangeType): void {
    const template = state.getState().template;
    for (const listener of changeListeners) {
      try {
        listener(template, changeType);
      } catch (error) {
        console.error("Error in change listener:", error);
      }
    }
  }

  function executeCommand(
    command: Command<Template>,
    changeType: ChangeType,
  ): void {
    assertNotDisposed();

    const currentState = state.getState();
    const stateBefore = currentState.template;
    const newTemplate = command.execute(currentState.template);
    state.setState({ template: newTemplate });
    history.push(command, stateBefore);
    saveOrchestrator.markDirty();
    notifyChange(changeType);
  }

  // ============================================================================
  // Public API
  // ============================================================================

  const editor: Editor = {
    // State Access
    getTemplate(): Template {
      assertNotDisposed();
      return state.getState().template;
    },

    getSelectedBlockId(): string | null {
      assertNotDisposed();
      return state.getState().selectedBlockId;
    },

    setSelectedBlockId(blockId: string | null): void {
      assertNotDisposed();
      state.setState({ selectedBlockId: blockId });
      notifyChange("selection");
    },

    // Block Mutations
    addBlock(type: BlockType, parentId: string | null, index: number): Block | null {
      assertNotDisposed();
      const block = createBlock(type);
      if (!block) return null;

      const command = new AddBlockCommand(block, parentId, index);
      executeCommand(command, "block-added");
      return block;
    },

    updateBlock(blockId: string, updates: Partial<Block>): void {
      assertNotDisposed();
      const command = new UpdateBlockCommand(blockId, updates);
      executeCommand(command, "block-updated");
    },

    deleteBlock(blockId: string): void {
      assertNotDisposed();
      const command = new DeleteBlockCommand(blockId);
      executeCommand(command, "block-deleted");
    },

    moveBlock(blockId: string, newParentId: string | null, newIndex: number): void {
      assertNotDisposed();
      const command = new MoveBlockCommand(blockId, newParentId, newIndex);
      executeCommand(command, "block-moved");
    },

    // Document Mutations
    updateDocumentStyles(styles: Partial<DocumentStyles>): void {
      assertNotDisposed();
      const command = new UpdateDocumentStylesCommand(styles);
      executeCommand(command, "document-styles");
    },

    updatePageSettings(settings: Partial<PageSettings>): void {
      assertNotDisposed();
      const command = new UpdatePageSettingsCommand(settings);
      executeCommand(command, "page-settings");
    },

    updateTheme(themeId: string | null): void {
      assertNotDisposed();
      const command = new UpdateThemeCommand(themeId);
      executeCommand(command, "theme");
    },

    // Batch Operations
    batch(commands: Command<Template>[], description?: string): void {
      assertNotDisposed();
      if (commands.length === 0) return;

      const composite = new CompositeCommand(commands, description);
      executeCommand(composite, "block-updated"); // Generic change type for batches
    },

    // History
    undo(): boolean {
      assertNotDisposed();
      const prevState = history.undo();
      if (prevState) {
        state.setState({ template: prevState });
        saveOrchestrator.markDirty();
        notifyChange("undo");
        return true;
      }
      return false;
    },

    redo(): boolean {
      assertNotDisposed();
      const currentState = state.getState();
      const newState = history.redo(currentState.template);
      if (newState) {
        state.setState({ template: newState });
        saveOrchestrator.markDirty();
        notifyChange("redo");
        return true;
      }
      return false;
    },

    canUndo(): boolean {
      assertNotDisposed();
      return history.canUndo();
    },

    canRedo(): boolean {
      assertNotDisposed();
      return history.canRedo();
    },

    clearHistory(): void {
      assertNotDisposed();
      history.clear();
    },

    // Persistence
    isDirty(): boolean {
      assertNotDisposed();
      return saveOrchestrator.getStatus() === "dirty";
    },

    getSaveStatus(): SaveStatus {
      assertNotDisposed();
      return saveOrchestrator.getStatus();
    },

    saveNow(): Promise<void> {
      assertNotDisposed();
      return saveOrchestrator.saveNow();
    },

    markSaved(): void {
      assertNotDisposed();
      saveOrchestrator.markSaved();
    },

    // Template Replacement
    setTemplate(newTemplate: Template): void {
      assertNotDisposed();
      state.setState({ template: newTemplate, selectedBlockId: null });
      history.clear();
      saveOrchestrator.markSaved();
      notifyChange("template-replaced");
    },

    // Subscriptions
    onChange(listener: ChangeListener): () => void {
      assertNotDisposed();
      changeListeners.add(listener);
      return () => {
        changeListeners.delete(listener);
      };
    },

    onSaveStatusChange(listener: SaveStatusListener): () => void {
      assertNotDisposed();
      return saveOrchestrator.onStatusChange(listener);
    },

    // Lifecycle
    dispose(): void {
      if (disposed) return;
      disposed = true;

      changeListeners.clear();
      saveOrchestrator.dispose();
    },
  };

  return editor;
}
