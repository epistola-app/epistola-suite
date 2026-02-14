/**
 * UndoStack — manages undo/redo command history.
 *
 * Each entry is a Command that, when dispatched, reverses the
 * corresponding forward operation.
 */

import type { Command } from './commands.js'

export class UndoStack {
  private _undoStack: Command[] = []
  private _redoStack: Command[] = []
  private readonly _maxDepth: number

  constructor(maxDepth = 100) {
    this._maxDepth = maxDepth
  }

  /**
   * Push an inverse command onto the undo stack.
   * Clears the redo stack (new action invalidates redo history).
   */
  push(inverse: Command): void {
    this._undoStack.push(inverse)
    this._redoStack = []
    this._trim()
  }

  /** Push onto undo stack without clearing redo (used by redo). */
  pushUndo(inverse: Command): void {
    this._undoStack.push(inverse)
    this._trim()
  }

  /** Push onto redo stack (used by undo). */
  pushRedo(inverse: Command): void {
    this._redoStack.push(inverse)
  }

  /** Pop the most recent undo command. */
  undo(): Command | undefined {
    return this._undoStack.pop()
  }

  /** Pop the most recent redo command. */
  redo(): Command | undefined {
    return this._redoStack.pop()
  }

  get canUndo(): boolean {
    return this._undoStack.length > 0
  }

  get canRedo(): boolean {
    return this._redoStack.length > 0
  }

  clear(): void {
    this._undoStack = []
    this._redoStack = []
  }

  private _trim(): void {
    while (this._undoStack.length > this._maxDepth) {
      this._undoStack.shift()
    }
  }
}
