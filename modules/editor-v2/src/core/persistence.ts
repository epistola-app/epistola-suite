/**
 * Unified save orchestration.
 *
 * Single source of truth for:
 * - Dirty state detection
 * - Debounced auto-save
 * - Save status tracking
 * - Backend confirmation
 */

import type { Template } from "../types/template.ts";

/**
 * Save status states.
 */
export type SaveStatus = "saved" | "dirty" | "saving" | "error";

/**
 * Listener for save status changes.
 */
export type SaveStatusListener = (status: SaveStatus, error?: Error) => void;

/**
 * Save function type - called to persist the template.
 */
export type SaveFunction = (template: Template) => Promise<void>;

/**
 * Save orchestrator interface.
 */
export interface SaveOrchestrator {
  /**
   * Check if there are unsaved changes.
   */
  isDirty(): boolean;

  /**
   * Get the current save status.
   */
  getStatus(): SaveStatus;

  /**
   * Mark the current state as saved.
   * Called after backend confirms save was successful.
   */
  markSaved(): void;

  /**
   * Mark the state as dirty.
   * Called when any change is made.
   */
  markDirty(): void;

  /**
   * Request a save (debounced).
   * The actual save will happen after the debounce delay.
   */
  requestSave(): void;

  /**
   * Save immediately, bypassing debounce.
   */
  saveNow(): Promise<void>;

  /**
   * Cancel any pending save request.
   */
  cancelPending(): void;

  /**
   * Subscribe to status changes.
   */
  onStatusChange(listener: SaveStatusListener): () => void;

  /**
   * Update the template getter.
   * Called when the template reference changes.
   */
  setTemplateGetter(getter: () => Template): void;

  /**
   * Dispose of the orchestrator.
   * Cancels any pending saves and clears listeners.
   */
  dispose(): void;
}

/**
 * Options for creating a save orchestrator.
 */
export interface SaveOptions {
  /**
   * Debounce delay in milliseconds.
   * Default: 1000 (1 second)
   */
  debounceDelay?: number;

  /**
   * Function to get the current template.
   */
  getTemplate: () => Template;

  /**
   * Function to save the template.
   */
  save: SaveFunction;

  /**
   * Initial saved snapshot for comparison.
   * If not provided, the first template state is used.
   */
  initialSnapshot?: Template;
}

/**
 * Create a save orchestrator.
 *
 * @param options Configuration options
 * @returns A save orchestrator instance
 *
 * @example
 * ```typescript
 * const persistence = createSaveOrchestrator({
 *   getTemplate: () => store.getState().template,
 *   save: async (template) => {
 *     await fetch('/api/template', {
 *       method: 'PUT',
 *       body: JSON.stringify(template)
 *     });
 *   },
 *   debounceDelay: 1500
 * });
 *
 * // When template changes
 * persistence.markDirty();
 * persistence.requestSave();
 *
 * // Or combine them
 * persistence.markDirty();
 *
 * // Subscribe to status
 * persistence.onStatusChange((status, error) => {
 *   if (status === 'error') {
 *     showError('Save failed:', error);
 *   }
 * });
 * ```
 */
export function createSaveOrchestrator(options: SaveOptions): SaveOrchestrator {
  const { debounceDelay = 1000, save } = options;
  let { getTemplate } = options;

  let savedSnapshot: string = JSON.stringify(
    options.initialSnapshot ?? getTemplate(),
  );
  let status: SaveStatus = "saved";
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  let savePromise: Promise<void> | null = null;

  const listeners = new Set<SaveStatusListener>();

  /**
   * Deep compare using JSON serialization.
   */
  function hasChanges(): boolean {
    const current = JSON.stringify(getTemplate());
    return current !== savedSnapshot;
  }

  /**
   * Notify all listeners of status change.
   */
  function notifyStatusChange(newStatus: SaveStatus, error?: Error): void {
    if (newStatus !== status || error) {
      status = newStatus;
      for (const listener of listeners) {
        try {
          listener(status, error);
        } catch (err) {
          console.error("Error in save status listener:", err);
        }
      }
    }
  }

  /**
   * Perform the actual save.
   */
  async function doSave(): Promise<void> {
    if (!hasChanges()) {
      notifyStatusChange("saved");
      return;
    }

    const template = getTemplate();
    notifyStatusChange("saving");

    try {
      await save(template);
      // Update snapshot only after successful save
      savedSnapshot = JSON.stringify(template);
      notifyStatusChange("saved");
    } catch (error) {
      notifyStatusChange("error", error instanceof Error ? error : new Error(String(error)));
      throw error;
    }
  }

  return {
    isDirty(): boolean {
      return hasChanges();
    },

    getStatus(): SaveStatus {
      return status;
    },

    markSaved(): void {
      savedSnapshot = JSON.stringify(getTemplate());
      notifyStatusChange("saved");
    },

    markDirty(): void {
      if (status !== "saving") {
        notifyStatusChange("dirty");
      }
    },

    requestSave(): void {
      // Cancel any pending save
      if (timeoutId) {
        clearTimeout(timeoutId);
      }

      // Mark dirty if we have changes
      if (hasChanges() && status !== "saving") {
        notifyStatusChange("dirty");
      }

      // Schedule debounced save
      timeoutId = setTimeout(() => {
        timeoutId = null;
        // Catch errors to prevent unhandled rejections - errors are reported via status
        savePromise = doSave()
          .catch(() => {
            // Error already handled via notifyStatusChange in doSave
          })
          .finally(() => {
            savePromise = null;
          });
      }, debounceDelay);
    },

    async saveNow(): Promise<void> {
      // Cancel any pending debounced save
      if (timeoutId) {
        clearTimeout(timeoutId);
        timeoutId = null;
      }

      // If already saving, wait for that to complete
      if (savePromise) {
        await savePromise;
        // Check if still dirty after that save completes
        if (hasChanges()) {
          await doSave();
        }
        return;
      }

      await doSave();
    },

    cancelPending(): void {
      if (timeoutId) {
        clearTimeout(timeoutId);
        timeoutId = null;
      }
    },

    onStatusChange(listener: SaveStatusListener): () => void {
      listeners.add(listener);
      // Immediately call with current status
      listener(status);
      return () => {
        listeners.delete(listener);
      };
    },

    setTemplateGetter(getter: () => Template): void {
      getTemplate = getter;
    },

    dispose(): void {
      if (timeoutId) {
        clearTimeout(timeoutId);
        timeoutId = null;
      }
      listeners.clear();
    },
  };
}

/**
 * Create a beforeunload handler to warn about unsaved changes.
 *
 * @param orchestrator The save orchestrator to check
 * @returns A cleanup function to remove the handler
 *
 * @example
 * ```typescript
 * const cleanup = createBeforeUnloadHandler(persistence);
 * // Later...
 * cleanup();
 * ```
 */
export function createBeforeUnloadHandler(
  orchestrator: SaveOrchestrator,
): () => void {
  function handler(event: BeforeUnloadEvent): string | undefined {
    if (orchestrator.isDirty()) {
      event.preventDefault();
      // Modern browsers ignore the return value but still show a dialog
      const message = "You have unsaved changes. Are you sure you want to leave?";
      event.returnValue = message;
      return message;
    }
    return undefined;
  }

  window.addEventListener("beforeunload", handler);

  return () => {
    window.removeEventListener("beforeunload", handler);
  };
}
