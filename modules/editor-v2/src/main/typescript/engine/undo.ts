/**
 * UndoStack — manages undo/redo history for the editor engine.
 *
 * Supports two kinds of entries:
 *  - Command: a structural inverse command (InsertNode, RemoveNode, etc.)
 *  - TextChangeEntry: a reference to a ProseMirror editing session that
 *    delegates undo/redo to ProseMirror's native history via undoDepth()
 */

import type { Command } from './commands.js'
import type { NodeId } from '../types/index.js'

// ---------------------------------------------------------------------------
// TextChangeEntry — first-class undo entry for ProseMirror editing sessions
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

export interface TextChangeEntry {
  readonly type: 'TextChange'
  readonly nodeId: NodeId
  /** Callbacks into ProseMirror. Null if PM was destroyed and ops invalidated. */
  ops: TextChangeOps | null
  /** Snapshot of the content before this editing session (for fallback undo). */
  readonly contentBefore: unknown
  /** Snapshot of the content after this editing session (for fallback redo). Captured lazily on first undo. */
  contentAfter?: unknown
  /** PM undoDepth at the start of this session — don't undo past this. */
  readonly undoDepthAtStart: number
  /** PM undoDepth at the end of this session — redo target. Set lazily on first undo. */
  undoDepthAtEnd?: number
}

export type UndoEntry = Command | TextChangeEntry

export function isTextChange(entry: UndoEntry): entry is TextChangeEntry {
  return (entry as TextChangeEntry).type === 'TextChange'
}

// ---------------------------------------------------------------------------
// UndoStack
// ---------------------------------------------------------------------------

export class UndoStack {
  private _undoStack: UndoEntry[] = []
  private _redoStack: UndoEntry[] = []
  private readonly _maxDepth: number

  constructor(maxDepth = 100) {
    this._maxDepth = maxDepth
  }

  /**
   * Push an entry onto the undo stack.
   * Clears the redo stack (new action invalidates redo history).
   */
  push(entry: UndoEntry): void {
    this._undoStack.push(entry)
    this._redoStack = []
    this._trim()
  }

  /** Push onto undo stack without clearing redo (used by redo). */
  pushUndo(entry: UndoEntry): void {
    this._undoStack.push(entry)
    this._trim()
  }

  /** Push onto redo stack (used by undo). */
  pushRedo(entry: UndoEntry): void {
    this._redoStack.push(entry)
  }

  /** Pop the most recent undo entry. */
  popUndo(): UndoEntry | undefined {
    return this._undoStack.pop()
  }

  /** Pop the most recent redo entry. */
  popRedo(): UndoEntry | undefined {
    return this._redoStack.pop()
  }

  /** Peek at the top of the undo stack without popping. */
  peekUndo(): UndoEntry | undefined {
    return this._undoStack[this._undoStack.length - 1]
  }

  /** Peek at the top of the redo stack without popping. */
  peekRedo(): UndoEntry | undefined {
    return this._redoStack[this._redoStack.length - 1]
  }

  /**
   * @deprecated Use popUndo() instead.
   */
  undo(): UndoEntry | undefined {
    return this.popUndo()
  }

  /**
   * @deprecated Use popRedo() instead.
   */
  redo(): UndoEntry | undefined {
    return this.popRedo()
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
