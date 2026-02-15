/**
 * CommandChange — wraps a structural command inverse for undo/redo.
 *
 * Undo applies the stored inverse command; if it succeeds and produces
 * a new inverse, that new inverse is pushed onto the redo stack.
 * Redo is symmetric.
 */

import type { Command } from './commands.js'
import type { Change, ChangeContext } from './change.js'

export class CommandChange implements Change {
  readonly command: Command

  constructor(command: Command) {
    this.command = command
  }

  undoStep(ctx: ChangeContext): boolean {
    ctx.stack.popUndo()
    const result = ctx.applySilent(this.command)
    if (!result.ok) return false
    if (result.inverse) {
      ctx.stack.pushRedo(new CommandChange(result.inverse))
    }
    return true
  }

  redoStep(ctx: ChangeContext): boolean {
    ctx.stack.popRedo()
    const result = ctx.applySilent(this.command)
    if (!result.ok) return false
    if (result.inverse) {
      ctx.stack.pushUndo(new CommandChange(result.inverse))
    }
    return true
  }
}
