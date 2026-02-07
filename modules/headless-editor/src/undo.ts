/**
 * UndoManager - Simple undo/redo using history stacks
 *
 * Framework-agnostic history management for any serializable state.
 * Exposes `$canUndo` and `$canRedo` as reactive nanostores atoms.
 */

import { atom } from 'nanostores';
import type { Template } from "./types.js";

/**
 * Deep clone a template (for history snapshots)
 */
function cloneTemplate(template: Template): Template {
  return JSON.parse(JSON.stringify(template));
}

/**
 * Simple undo/redo manager using history stacks.
 *
 * Maintains `$canUndo` and `$canRedo` reactive atoms that update
 * after each `push()`, `undo()`, `redo()`, and `clear()` operation.
 */
export class UndoManager {
  private past: Template[] = [];
  private future: Template[] = [];
  private maxHistory: number;

  /** Reactive atom indicating whether undo is available */
  readonly $canUndo = atom<boolean>(false);

  /** Reactive atom indicating whether redo is available */
  readonly $canRedo = atom<boolean>(false);

  constructor(maxHistory = 50) {
    this.maxHistory = maxHistory;
  }

  /** Update reactive atoms to reflect current history state */
  private updateAtoms(): void {
    this.$canUndo.set(this.past.length > 0);
    this.$canRedo.set(this.future.length > 0);
  }

  /**
   * Save current state before a mutation.
   * Call this BEFORE making changes to push the current state to history.
   * @param state - The current template state to snapshot
   */
  push(state: Template): void {
    this.past.push(cloneTemplate(state));
    this.future = []; // Clear redo stack on new action

    // Limit history size
    if (this.past.length > this.maxHistory) {
      this.past.shift();
    }

    this.updateAtoms();
  }

  /**
   * Undo: restore previous state.
   * @param current - The current template state (pushed to redo stack)
   * @returns The previous state, or null if no history
   */
  undo(current: Template): Template | null {
    if (this.past.length === 0) return null;
    this.future.push(cloneTemplate(current));
    const result = this.past.pop()!;
    this.updateAtoms();
    return result;
  }

  /**
   * Redo: restore next state.
   * @param current - The current template state (pushed to undo stack)
   * @returns The next state, or null if no future
   */
  redo(current: Template): Template | null {
    if (this.future.length === 0) return null;
    this.past.push(cloneTemplate(current));
    const result = this.future.pop()!;
    this.updateAtoms();
    return result;
  }

  /**
   * Check if undo is available (snapshot, not reactive).
   * Prefer subscribing to `$canUndo` for reactive updates.
   */
  canUndo(): boolean {
    return this.past.length > 0;
  }

  /**
   * Check if redo is available (snapshot, not reactive).
   * Prefer subscribing to `$canRedo` for reactive updates.
   */
  canRedo(): boolean {
    return this.future.length > 0;
  }

  /**
   * Clear all history (undo and redo stacks).
   */
  clear(): void {
    this.past = [];
    this.future = [];
    this.updateAtoms();
  }

  /**
   * Get the number of undo steps available.
   */
  getUndoCount(): number {
    return this.past.length;
  }

  /**
   * Get the number of redo steps available.
   */
  getRedoCount(): number {
    return this.future.length;
  }
}
