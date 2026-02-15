/**
 * EditorController — Main Stimulus controller for the editor toolbar and actions.
 *
 * Manages toolbar buttons (add block, undo, redo, delete), save lifecycle,
 * reactive UI updates from editor state (dirty, canUndo, canRedo), and
 * theme/data example selectors.
 *
 * Stimulus values:
 * - `dirty` (Boolean) — reflects $isDirty state
 * - `canUndo` (Boolean) — reflects $canUndo state
 * - `canRedo` (Boolean) — reflects $canRedo state
 *
 * Stimulus targets:
 * - `undoBtn` — undo button (disabled when canUndo is false)
 * - `redoBtn` — redo button (disabled when canRedo is false)
 * - `saveBtn` — save button
 * - `saveStatus` — save status indicator text
 * - `themeSelect` — theme selector dropdown
 * - `dataExampleSelect` — data example selector dropdown
 * - `blockContainer` — the block rendering container
 */

import { Controller } from "@hotwired/stimulus";
import { html, render as renderHtml } from "uhtml";
import type { TemplateEditor, Template } from "@epistola/headless-editor";
import { getEditorForElement, getRuntimeForElement } from "../mount.js";

export class EditorController extends Controller {
  static targets = [
    "undoBtn",
    "redoBtn",
    "saveBtn",
    "saveStatus",
    "themeSelect",
    "dataExampleSelect",
    "blockContainer",
  ];
  static values = {
    dirty: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
  };

  declare readonly undoBtnTarget: HTMLButtonElement;
  declare readonly hasUndoBtnTarget: boolean;
  declare readonly redoBtnTarget: HTMLButtonElement;
  declare readonly hasRedoBtnTarget: boolean;
  declare readonly saveBtnTarget: HTMLButtonElement;
  declare readonly hasSaveBtnTarget: boolean;
  declare readonly saveStatusTarget: HTMLElement;
  declare readonly hasSaveStatusTarget: boolean;
  declare readonly themeSelectTarget: HTMLSelectElement;
  declare readonly hasThemeSelectTarget: boolean;
  declare readonly dataExampleSelectTarget: HTMLSelectElement;
  declare readonly hasDataExampleSelectTarget: boolean;
  declare readonly blockContainerTarget: HTMLElement;
  declare readonly hasBlockContainerTarget: boolean;

  declare dirtyValue: boolean;
  declare canUndoValue: boolean;
  declare canRedoValue: boolean;

  private unsubscribers: Array<() => void> = [];
  private beforeUnloadHandler?: (e: BeforeUnloadEvent) => void;

  connect(): void {
    const editor = this.getEditor();
    if (!editor) return;

    const stores = editor.getStores();

    // Subscribe to reactive stores and update Stimulus values
    this.unsubscribers.push(
      stores.$isDirty.subscribe((dirty: boolean) => {
        this.dirtyValue = dirty;
      }),
      stores.$canUndo.subscribe((canUndo: boolean) => {
        this.canUndoValue = canUndo;
      }),
      stores.$canRedo.subscribe((canRedo: boolean) => {
        this.canRedoValue = canRedo;
      }),
    );

    // Read save handler from mount config
    this.saveHandler = getRuntimeForElement(this.element)?.saveHandler;

    // Populate theme selector
    if (this.hasThemeSelectTarget) {
      const themes = editor.getThemes();
      const currentThemeId = editor.getTemplate().themeId;
      renderHtml(
        this.themeSelectTarget,
        html`${themes.map(
          (theme) => html`<option value=${theme.id}>${theme.name}</option>`,
        )}`,
      );
      if (currentThemeId) {
        this.themeSelectTarget.value = currentThemeId;
      }
    }

    // Populate data example selector
    if (this.hasDataExampleSelectTarget) {
      const examples = editor.getDataExamples();
      const selectedId = editor.getSelectedDataExampleId();
      renderHtml(
        this.dataExampleSelectTarget,
        html`${examples.map(
          (example) =>
            html`<option value=${example.id}>${example.name}</option>`,
        )}`,
      );
      if (selectedId) {
        this.dataExampleSelectTarget.value = selectedId;
      }
    }

    // Unsaved changes warning
    this.beforeUnloadHandler = (e: BeforeUnloadEvent) => {
      if (stores.$isDirty.get()) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", this.beforeUnloadHandler);
  }

  disconnect(): void {
    for (const unsub of this.unsubscribers) unsub();
    this.unsubscribers = [];
    if (this.beforeUnloadHandler) {
      window.removeEventListener("beforeunload", this.beforeUnloadHandler);
      this.beforeUnloadHandler = undefined;
    }
  }

  // ==========================================================================
  // Stimulus value callbacks — update button disabled states
  // ==========================================================================

  canUndoValueChanged(): void {
    if (this.hasUndoBtnTarget) {
      this.undoBtnTarget.disabled = !this.canUndoValue;
    }
  }

  canRedoValueChanged(): void {
    if (this.hasRedoBtnTarget) {
      this.redoBtnTarget.disabled = !this.canRedoValue;
    }
  }

  dirtyValueChanged(): void {
    if (this.hasSaveStatusTarget) {
      if (this.dirtyValue) {
        this.saveStatusTarget.textContent = "Unsaved changes";
        this.saveStatusTarget.className = "save-status dirty";
      } else {
        this.saveStatusTarget.textContent = "Saved";
        this.saveStatusTarget.className = "save-status saved";
      }
    }
  }

  // ==========================================================================
  // Toolbar actions (wired via data-action attributes)
  // ==========================================================================

  /** Add a block of the specified type at root level. Data attribute: `data-block-type`. */
  addBlock(event: Event): void {
    const editor = this.getEditor();
    if (!editor) return;

    const target = event.currentTarget as HTMLElement;
    const blockType = target.dataset.blockType;
    if (!blockType) {
      console.error("Missing data-block-type on add block action target");
      return;
    }
    const availableTypes = editor.getBlockTypes();
    if (!availableTypes.includes(blockType as (typeof availableTypes)[number])) {
      console.error(`Unsupported built-in block type: ${blockType}`);
      return;
    }

    editor.addBlock(blockType as (typeof availableTypes)[number]);
  }

  /** Add a text block inside the currently selected container. */
  addBlockToSelected(): void {
    const editor = this.getEditor();
    if (!editor) return;

    const state = editor.getState();
    if (state.selectedBlockId) {
      editor.addBlock("text", state.selectedBlockId);
    }
  }

  undo(): void {
    this.getEditor()?.undo();
  }

  redo(): void {
    this.getEditor()?.redo();
  }

  deleteSelected(): void {
    const editor = this.getEditor();
    if (!editor) return;
    const state = editor.getState();
    if (state.selectedBlockId) {
      editor.deleteBlock(state.selectedBlockId);
    }
  }

  clearSelection(): void {
    this.getEditor()?.selectBlock(null);
  }

  // ==========================================================================
  // Save lifecycle
  // ==========================================================================

  private saveHandler?: (template: Template) => Promise<void>;

  /** Set the save handler (called from mountEditor config). */
  setSaveHandler(handler: (template: Template) => Promise<void>): void {
    this.saveHandler = handler;
  }

  async save(): Promise<void> {
    const editor = this.getEditor();
    if (!this.saveHandler) {
      this.saveHandler = getRuntimeForElement(this.element)?.saveHandler;
      if (!this.saveHandler && editor) {
        this.saveHandler = (
          editor as unknown as {
            __saveHandler?: (template: Template) => Promise<void>;
          }
        ).__saveHandler;
      }
    }
    if (!editor || !this.saveHandler) return;

    if (this.hasSaveStatusTarget) {
      this.saveStatusTarget.textContent = "Saving...";
      this.saveStatusTarget.className = "save-status saving";
    }

    try {
      await this.saveHandler(editor.getTemplate());
      editor.markAsSaved();
      if (this.hasSaveStatusTarget) {
        this.saveStatusTarget.textContent = "Saved";
        this.saveStatusTarget.className = "save-status saved";
      }
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : "Save failed";
      if (this.hasSaveStatusTarget) {
        this.saveStatusTarget.textContent = msg;
        this.saveStatusTarget.className = "save-status error";
      }
    }
  }

  // ==========================================================================
  // Theme selector
  // ==========================================================================

  handleThemeChange(): void {
    const editor = this.getEditor();
    if (!editor || !this.hasThemeSelectTarget) return;
    editor.updateThemeId(this.themeSelectTarget.value || null);
  }

  // ==========================================================================
  // Data example selector
  // ==========================================================================

  handleDataExampleChange(): void {
    const editor = this.getEditor();
    if (!editor || !this.hasDataExampleSelectTarget) return;
    editor.selectDataExample(this.dataExampleSelectTarget.value || null);
  }

  // ==========================================================================
  // Export / Import
  // ==========================================================================

  exportTemplate(): void {
    const editor = this.getEditor();
    if (!editor) return;

    const json = editor.exportJSON();
    const blob = new Blob([json], { type: "application/json" });
    const url = URL.createObjectURL(blob);

    const a = document.createElement("a");
    a.href = url;
    a.download = `template-${editor.getTemplate().id}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  importTemplate(): void {
    const editor = this.getEditor();
    if (!editor) return;

    const input = document.createElement("input");
    input.type = "file";
    input.accept = "application/json";

    input.onchange = () => {
      const file = input.files?.[0];
      if (!file) return;

      const reader = new FileReader();
      reader.onload = (event) => {
        const json = event.target?.result;
        if (typeof json !== "string") return;
        editor.importJSON(json);
      };
      reader.readAsText(file);
    };

    input.click();
  }

  private getEditor(): TemplateEditor | null {
    return getEditorForElement(this.element);
  }
}
