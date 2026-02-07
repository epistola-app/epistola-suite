/**
 * Editor toolbar component.
 *
 * Provides undo/redo, save status, and common actions.
 */

import type { SaveStatus } from "../core/persistence.ts";

// ============================================================================
// Types
// ============================================================================

export interface ToolbarOptions {
  /** Container element to mount into */
  container: HTMLElement;
  /** Template name */
  templateName?: string;
  /** Callback for undo */
  onUndo?: () => void;
  /** Callback for redo */
  onRedo?: () => void;
  /** Callback for save */
  onSave?: () => void;
  /** Callback for preview */
  onPreview?: () => void;
}

export interface Toolbar {
  /** Root element */
  element: HTMLElement;
  /** Set template name */
  setTemplateName(name: string): void;
  /** Set undo/redo availability */
  setUndoRedo(canUndo: boolean, canRedo: boolean): void;
  /** Set save status */
  setSaveStatus(status: SaveStatus): void;
  /** Destroy and cleanup */
  destroy(): void;
}

// ============================================================================
// Icons
// ============================================================================

const ICONS = {
  undo: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13"/></svg>`,
  redo: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 7v6h-6"/><path d="M3 17a9 9 0 0 1 9-9 9 9 0 0 1 6 2.3l3 2.7"/></svg>`,
  save: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>`,
  preview: `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>`,
  check: `<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`,
  spinner: `<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="ev2-spin"><line x1="12" y1="2" x2="12" y2="6"/><line x1="12" y1="18" x2="12" y2="22"/><line x1="4.93" y1="4.93" x2="7.76" y2="7.76"/><line x1="16.24" y1="16.24" x2="19.07" y2="19.07"/><line x1="2" y1="12" x2="6" y2="12"/><line x1="18" y1="12" x2="22" y2="12"/><line x1="4.93" y1="19.07" x2="7.76" y2="16.24"/><line x1="16.24" y1="7.76" x2="19.07" y2="4.93"/></svg>`,
  error: `<svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`,
};

// ============================================================================
// Component
// ============================================================================

/**
 * Create an editor toolbar component.
 */
export function createToolbar(options: ToolbarOptions): Toolbar {
  const {
    container,
    templateName = "Untitled",
    onUndo,
    onRedo,
    onSave,
    onPreview,
  } = options;

  // Create root element
  const root = document.createElement("div");
  root.className = "ev2-toolbar";

  // Left section: template name
  const left = document.createElement("div");
  left.className = "ev2-toolbar__left";

  const nameEl = document.createElement("span");
  nameEl.className = "ev2-toolbar__name";
  nameEl.textContent = templateName;
  left.appendChild(nameEl);

  // Center section: undo/redo
  const center = document.createElement("div");
  center.className = "ev2-toolbar__center";

  const undoBtn = createButton("Undo", ICONS.undo, () => onUndo?.());
  undoBtn.classList.add("ev2-toolbar__btn--undo");
  undoBtn.disabled = true;

  const redoBtn = createButton("Redo", ICONS.redo, () => onRedo?.());
  redoBtn.classList.add("ev2-toolbar__btn--redo");
  redoBtn.disabled = true;

  center.appendChild(undoBtn);
  center.appendChild(redoBtn);

  // Right section: status and actions
  const right = document.createElement("div");
  right.className = "ev2-toolbar__right";

  // Save status indicator
  const statusEl = document.createElement("span");
  statusEl.className = "ev2-toolbar__status";
  statusEl.innerHTML = `${ICONS.check} Saved`;
  right.appendChild(statusEl);

  // Save button
  const saveBtn = createButton("Save", ICONS.save, () => onSave?.());
  saveBtn.classList.add("ev2-toolbar__btn--save");
  right.appendChild(saveBtn);

  // Preview button
  const previewBtn = createButton("Preview", ICONS.preview, () => onPreview?.());
  previewBtn.classList.add("ev2-toolbar__btn--preview");
  right.appendChild(previewBtn);

  root.appendChild(left);
  root.appendChild(center);
  root.appendChild(right);

  // Helper to create button
  function createButton(
    title: string,
    icon: string,
    onClick: () => void,
  ): HTMLButtonElement {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "ev2-toolbar__btn";
    btn.title = title;
    btn.innerHTML = icon;
    btn.addEventListener("click", onClick);
    return btn;
  }

  // Mount
  container.appendChild(root);

  return {
    element: root,

    setTemplateName(name: string): void {
      nameEl.textContent = name;
    },

    setUndoRedo(canUndo: boolean, canRedo: boolean): void {
      undoBtn.disabled = !canUndo;
      redoBtn.disabled = !canRedo;
    },

    setSaveStatus(status: SaveStatus): void {
      statusEl.className = `ev2-toolbar__status ev2-toolbar__status--${status}`;

      switch (status) {
        case "saved":
          statusEl.innerHTML = `${ICONS.check} Saved`;
          break;
        case "dirty":
          statusEl.textContent = "Unsaved changes";
          break;
        case "saving":
          statusEl.innerHTML = `${ICONS.spinner} Saving...`;
          break;
        case "error":
          statusEl.innerHTML = `${ICONS.error} Save failed`;
          break;
      }
    },

    destroy(): void {
      root.remove();
    },
  };
}
