/**
 * SnapshotHistory<T> — Generic snapshot-based undo/redo stack.
 *
 * Each push() stores a structuredClone of the current state on the undo stack
 * and clears the redo stack. Undo/redo swap snapshots between the two stacks.
 *
 * Extracted from SchemaCommandHistory so the same pattern can be reused for
 * example data editing (SnapshotHistory<JsonObject>).
 */

export class SnapshotHistory<T> {
  private undoStack: T[] = []
  private redoStack: T[] = []

  get canUndo(): boolean {
    return this.undoStack.length > 0
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0
  }

  /**
   * Snapshot the current state before a mutation.
   * Clears the redo stack (new edit branch).
   */
  push(current: T): void {
    this.undoStack.push(structuredClone(current))
    this.redoStack = []
  }

  /**
   * Undo: pop from undo stack, push current onto redo stack.
   * Returns the restored state, or null if nothing to undo.
   */
  undo(current: T): T | null {
    if (!this.canUndo) return null
    this.redoStack.push(structuredClone(current))
    return this.undoStack.pop()!
  }

  /**
   * Redo: pop from redo stack, push current onto undo stack.
   * Returns the re-applied state, or null if nothing to redo.
   */
  redo(current: T): T | null {
    if (!this.canRedo) return null
    this.undoStack.push(structuredClone(current))
    return this.redoStack.pop()!
  }

  /**
   * Clear all history. Call on save or context switch.
   */
  clear(): void {
    this.undoStack = []
    this.redoStack = []
  }
}
