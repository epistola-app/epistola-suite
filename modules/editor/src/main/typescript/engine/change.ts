/**
 * Unified Change interface for the undo/redo system.
 *
 * Every undoable action (structural commands, text editing sessions)
 * implements this interface. The engine's undo() and redo() methods
 * simply delegate to the top-of-stack Change, eliminating type branching.
 */

import type { AnyCommand, CommandResult } from './commands.js'
import type { NodeId } from '../types/index.js'

// ---------------------------------------------------------------------------
// Change — the uniform undo/redo contract
// ---------------------------------------------------------------------------

export interface Change {
  /** Execute one undo step. Manages own stack operations via ctx. Returns true if applied. */
  undoStep(ctx: ChangeContext): boolean
  /** Execute one redo step. Manages own stack operations via ctx. Returns true if applied. */
  redoStep(ctx: ChangeContext): boolean
}

// ---------------------------------------------------------------------------
// ChangeContext — services provided by the engine to Change implementations
// ---------------------------------------------------------------------------

export interface ChangeContext {
  /** Access to the undo/redo stacks for push/pop/peek operations. */
  readonly stack: ChangeStackOps

  /** Apply a command without recording it on the undo stack. Returns the result with inverse. */
  applySilent(command: AnyCommand): CommandResult

  /** Dispatch UpdateNodeProps with skipUndo (for PM content sync). */
  syncContent(nodeId: NodeId, content: unknown): void

  /** Apply a content snapshot via structuredClone + dispatch (for fallback restore). */
  applySnapshot(nodeId: NodeId, content: unknown): void

  /** Recursive undo (for TextChange fallthrough when session is exhausted). */
  undo(): boolean

  /** Recursive redo (for TextChange fallthrough when session is fully redone). */
  redo(): boolean
}

// ---------------------------------------------------------------------------
// ChangeStackOps — stack operations exposed to Change implementations
// ---------------------------------------------------------------------------

export interface ChangeStackOps {
  peekUndo(): Change | undefined
  peekRedo(): Change | undefined
  popUndo(): Change | undefined
  popRedo(): Change | undefined
  pushUndo(entry: Change): void
  pushRedo(entry: Change): void
}
