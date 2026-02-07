/**
 * Undo/redo history manager.
 *
 * Stack-based implementation that works with the command pattern.
 * Supports configurable history limit.
 */

import type { Command } from "./commands.ts";
import type { Template } from "../types/template.ts";

/**
 * History entry storing a command and the state before execution.
 */
export interface HistoryEntry {
  command: Command<Template>;
  stateBefore: Template;
}

/**
 * Listener for history state changes.
 */
export type HistoryListener = (canUndo: boolean, canRedo: boolean) => void;

/**
 * History manager interface.
 */
export interface HistoryManager {
  /**
   * Push a command onto the history stack.
   * This is called after the command is executed.
   */
  push(command: Command<Template>, stateBefore: Template): void;

  /**
   * Undo the last command.
   * Returns the state before the command was executed, or undefined if nothing to undo.
   */
  undo(): Template | undefined;

  /**
   * Redo the last undone command.
   * Returns the state after re-executing the command, or undefined if nothing to redo.
   */
  redo(currentState: Template): Template | undefined;

  /**
   * Check if undo is available.
   */
  canUndo(): boolean;

  /**
   * Check if redo is available.
   */
  canRedo(): boolean;

  /**
   * Clear all history.
   */
  clear(): void;

  /**
   * Get the number of entries in the undo stack.
   */
  undoCount(): number;

  /**
   * Get the number of entries in the redo stack.
   */
  redoCount(): number;

  /**
   * Subscribe to history state changes.
   * Called whenever canUndo/canRedo status changes.
   */
  subscribe(listener: HistoryListener): () => void;
}

/**
 * Options for creating a history manager.
 */
export interface HistoryOptions {
  /**
   * Maximum number of entries to keep in history.
   * Oldest entries are discarded when limit is reached.
   * Default: 100
   */
  limit?: number;
}

/**
 * Create a history manager.
 *
 * @param options Configuration options
 * @returns A history manager instance
 *
 * @example
 * ```typescript
 * const history = createHistory({ limit: 50 });
 *
 * // After executing a command
 * const newState = command.execute(currentState);
 * history.push(command, currentState);
 *
 * // Undo
 * const prevState = history.undo();
 * if (prevState) {
 *   store.replaceState(prevState);
 * }
 *
 * // Redo
 * const redoneState = history.redo(currentState);
 * if (redoneState) {
 *   store.replaceState(redoneState);
 * }
 * ```
 */
export function createHistory(options: HistoryOptions = {}): HistoryManager {
  const limit = options.limit ?? 100;

  const undoStack: HistoryEntry[] = [];
  const redoStack: HistoryEntry[] = [];
  const listeners = new Set<HistoryListener>();

  let previousCanUndo = false;
  let previousCanRedo = false;

  /**
   * Notify listeners if undo/redo availability changed.
   */
  function notifyIfChanged(): void {
    const canUndoNow = undoStack.length > 0;
    const canRedoNow = redoStack.length > 0;

    if (canUndoNow !== previousCanUndo || canRedoNow !== previousCanRedo) {
      previousCanUndo = canUndoNow;
      previousCanRedo = canRedoNow;

      for (const listener of listeners) {
        try {
          listener(canUndoNow, canRedoNow);
        } catch (error) {
          console.error("Error in history listener:", error);
        }
      }
    }
  }

  return {
    push(command: Command<Template>, stateBefore: Template): void {
      undoStack.push({ command, stateBefore });

      // Enforce limit
      while (undoStack.length > limit) {
        undoStack.shift();
      }

      // Clear redo stack when new action is performed
      redoStack.length = 0;

      notifyIfChanged();
    },

    undo(): Template | undefined {
      const entry = undoStack.pop();
      if (!entry) {
        return undefined;
      }

      // Store for redo
      redoStack.push(entry);

      notifyIfChanged();

      return entry.stateBefore;
    },

    redo(currentState: Template): Template | undefined {
      const entry = redoStack.pop();
      if (!entry) {
        return undefined;
      }

      // Re-execute the command
      const newState = entry.command.execute(currentState);

      // Push back to undo stack
      undoStack.push({
        command: entry.command,
        stateBefore: currentState,
      });

      notifyIfChanged();

      return newState;
    },

    canUndo(): boolean {
      return undoStack.length > 0;
    },

    canRedo(): boolean {
      return redoStack.length > 0;
    },

    clear(): void {
      undoStack.length = 0;
      redoStack.length = 0;
      notifyIfChanged();
    },

    undoCount(): number {
      return undoStack.length;
    },

    redoCount(): number {
      return redoStack.length;
    },

    subscribe(listener: HistoryListener): () => void {
      listeners.add(listener);
      // Immediately call with current state
      listener(undoStack.length > 0, redoStack.length > 0);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

/**
 * Create a debounced history push function.
 * Useful for merging rapid successive operations (like typing) into single history entries.
 *
 * @param history The history manager
 * @param delay Debounce delay in milliseconds (default: 500)
 * @returns A debounced push function
 */
export function createDebouncedPush(
  history: HistoryManager,
  delay: number = 500,
): {
  push: (command: Command<Template>, stateBefore: Template) => void;
  flush: () => void;
} {
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  let pendingEntry: { command: Command<Template>; stateBefore: Template } | null =
    null;
  let firstStateBefore: Template | null = null;

  function flush(): void {
    if (timeoutId) {
      clearTimeout(timeoutId);
      timeoutId = null;
    }

    if (pendingEntry && firstStateBefore) {
      // Use the first state before for proper undo
      history.push(pendingEntry.command, firstStateBefore);
      pendingEntry = null;
      firstStateBefore = null;
    }
  }

  return {
    push(command: Command<Template>, stateBefore: Template): void {
      if (timeoutId) {
        clearTimeout(timeoutId);
      }

      // Keep track of the first state before in the debounce window
      if (!firstStateBefore) {
        firstStateBefore = stateBefore;
      }

      pendingEntry = { command, stateBefore };

      timeoutId = setTimeout(flush, delay);
    },

    flush,
  };
}
