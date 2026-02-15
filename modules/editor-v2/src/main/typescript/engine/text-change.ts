/**
 * TextChange — a first-class undo entry for ProseMirror editing sessions.
 *
 * Delegates undo/redo to ProseMirror's native history via the TextChangeOps
 * callback interface, using undoDepth() as the session boundary. When the PM
 * view is destroyed (e.g. block deleted), falls back to snapshot restore.
 *
 * All undo/redo logic that was previously in EditorEngine (_undoTextChange,
 * _redoTextChange, _moveRedoToUndo, _syncTextContent, _applyContentSnapshot)
 * now lives here as undoStep() and redoStep().
 */

import type { Change, ChangeContext } from './change.js'
import type { TextChangeOps } from './undo.js'
import type { NodeId } from '../types/index.js'

export class TextChange implements Change {
  readonly nodeId: NodeId
  ops: TextChangeOps | null
  readonly contentBefore: unknown
  contentAfter?: unknown
  readonly undoDepthAtStart: number
  undoDepthAtEnd?: number

  constructor(init: {
    nodeId: NodeId
    ops: TextChangeOps | null
    contentBefore: unknown
    undoDepthAtStart: number
  }) {
    this.nodeId = init.nodeId
    this.ops = init.ops
    this.contentBefore = init.contentBefore
    this.undoDepthAtStart = init.undoDepthAtStart
  }

  undoStep(ctx: ChangeContext): boolean {
    const ops = this.ops

    if (ops && ops.isAlive()) {
      // Lazily capture end-of-session state on first undo
      if (this.undoDepthAtEnd === undefined) {
        this.undoDepthAtEnd = ops.undoDepth()
        this.contentAfter = ops.getContent()
      }

      if (ops.undoDepth() > this.undoDepthAtStart) {
        ops.undo()
        ctx.syncContent(this.nodeId, ops.getContent())

        // If session is now exhausted, move entry to redo stack immediately
        if (ops.undoDepth() <= this.undoDepthAtStart) {
          ctx.stack.popUndo()
          ctx.stack.pushRedo(this)
        }
        return true
      }

      // Session already exhausted (entry still on undo stack from prior state)
    } else {
      // PM destroyed — apply contentBefore via snapshot restore
      ctx.stack.popUndo()
      ctx.applySnapshot(this.nodeId, this.contentBefore)
      ctx.stack.pushRedo(this)
      return true
    }

    // Fall through: session exhausted without performing an undo step
    ctx.stack.popUndo()
    ctx.stack.pushRedo(this)
    return ctx.undo()
  }

  redoStep(ctx: ChangeContext): boolean {
    const ops = this.ops

    if (ops && ops.isAlive()) {
      if (this.undoDepthAtEnd !== undefined && ops.undoDepth() < this.undoDepthAtEnd) {
        ops.redo()
        ctx.syncContent(this.nodeId, ops.getContent())

        // If session is fully redone, move entry to undo stack immediately
        if (ops.undoDepth() >= this.undoDepthAtEnd) {
          this._moveRedoToUndo(ctx)
        }
        return true
      }

      // Session already fully redone
      this._moveRedoToUndo(ctx)
      return ctx.redo()
    }

    // PM destroyed — apply contentAfter via snapshot restore
    ctx.stack.popRedo()
    if (this.contentAfter !== undefined) {
      ctx.applySnapshot(this.nodeId, this.contentAfter)
    }
    this._moveRedoToUndo(ctx)
    return true
  }

  /**
   * Move this entry from redo to undo stack, resetting the end-of-session
   * markers so they'll be re-captured if the user continues typing.
   */
  private _moveRedoToUndo(ctx: ChangeContext): void {
    ctx.stack.popRedo()
    this.undoDepthAtEnd = undefined
    this.contentAfter = undefined
    ctx.stack.pushUndo(this)
  }
}
