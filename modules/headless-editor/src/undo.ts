/**
 * UndoManager - Simple undo/redo using history stacks
 *
 * Framework-agnostic history management for any serializable state.
 */

import type { Template } from './types.js';

/**
 * Deep clone a template (for history snapshots)
 */
function cloneTemplate(template: Template): Template {
  return JSON.parse(JSON.stringify(template));
}

/**
 * Simple undo/redo manager using history stacks
 */
export class UndoManager {
  private past: Template[] = [];
  private future: Template[] = [];
  private maxHistory: number;

  constructor(maxHistory = 50) {
    this.maxHistory = maxHistory;
  }

  /**
   * Save current state before a mutation
   * Call this BEFORE making changes to push the current state to history
   */
  push(state: Template): void {
    this.past.push(cloneTemplate(state));
    this.future = []; // Clear redo stack on new action

    // Limit history size
    if (this.past.length > this.maxHistory) {
      this.past.shift();
    }
  }

  /**
   * Undo: restore previous state
   * Returns the previous state, or null if no history
   */
  undo(current: Template): Template | null {
    if (this.past.length === 0) return null;
    this.future.push(cloneTemplate(current));
    return this.past.pop()!;
  }

  /**
   * Redo: restore next state
   * Returns the next state, or null if no future
   */
  redo(current: Template): Template | null {
    if (this.future.length === 0) return null;
    this.past.push(cloneTemplate(current));
    return this.future.pop()!;
  }

  /**
   * Check if undo is available
   */
  canUndo(): boolean {
    return this.past.length > 0;
  }

  /**
   * Check if redo is available
   */
  canRedo(): boolean {
    return this.future.length > 0;
  }

  /**
   * Clear all history
   */
  clear(): void {
    this.past = [];
    this.future = [];
  }

  /**
   * Get the number of undo steps available
   */
  getUndoCount(): number {
    return this.past.length;
  }

  /**
   * Get the number of redo steps available
   */
  getRedoCount(): number {
    return this.future.length;
  }
}
