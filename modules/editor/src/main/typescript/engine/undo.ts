/**
 * UndoStack — manages undo/redo history for the editor engine.
 *
 * Entries implement the Change interface. Each Change manages its own
 * undo/redo logic (CommandChange for structural commands, TextChange
 * for ProseMirror editing sessions).
 */

import type { Change, ChangeStackOps } from './change.js'

// ---------------------------------------------------------------------------
// TextChangeOps — callbacks into ProseMirror (kept here, framework-agnostic)
// ---------------------------------------------------------------------------

/**
 * Callbacks into ProseMirror, created by the text editor component.
 * Keeps the engine decoupled from ProseMirror internals.
 */
export interface TextChangeOps {
  /** Whether the PM view is still alive (connected to DOM). */
  isAlive(): boolean
  /** Current PM undo depth. */
  undoDepth(): number
  /** Execute one PM undo step. Returns true if successful. */
  undo(): boolean
  /** Execute one PM redo step. Returns true if successful. */
  redo(): boolean
  /** Get current PM doc content as JSON. */
  getContent(): unknown
}

// ---------------------------------------------------------------------------
// UndoStack
// ---------------------------------------------------------------------------

export class UndoStack implements ChangeStackOps {
  private _undoStack: Change[] = []
  private _redoStack: Change[] = []
  private readonly _maxDepth: number

  constructor(maxDepth = 100) {
    this._maxDepth = maxDepth
  }

  /**
   * Push an entry onto the undo stack.
   * Clears the redo stack (new action invalidates redo history).
   */
  push(entry: Change): void {
    this._undoStack.push(entry)
    this._redoStack = []
    this._trim()
  }

  /** Push onto undo stack without clearing redo (used by redo). */
  pushUndo(entry: Change): void {
    this._undoStack.push(entry)
    this._trim()
  }

  /** Push onto redo stack (used by undo). */
  pushRedo(entry: Change): void {
    this._redoStack.push(entry)
  }

  /** Pop the most recent undo entry. */
  popUndo(): Change | undefined {
    return this._undoStack.pop()
  }

  /** Pop the most recent redo entry. */
  popRedo(): Change | undefined {
    return this._redoStack.pop()
  }

  /** Peek at the top of the undo stack without popping. */
  peekUndo(): Change | undefined {
    return this._undoStack[this._undoStack.length - 1]
  }

  /** Peek at the top of the redo stack without popping. */
  peekRedo(): Change | undefined {
    return this._redoStack[this._redoStack.length - 1]
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

  /** Iterate over undo entries (bottom to top). */
  *undoEntries(): IterableIterator<Change> {
    yield* this._undoStack
  }

  /** Iterate over redo entries (bottom to top). */
  *redoEntries(): IterableIterator<Change> {
    yield* this._redoStack
  }

  private _trim(): void {
    while (this._undoStack.length > this._maxDepth) {
      this._undoStack.shift()
    }
  }
}
