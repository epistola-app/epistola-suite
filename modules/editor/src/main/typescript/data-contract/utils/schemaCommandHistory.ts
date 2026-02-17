/**
 * SchemaCommandHistory — Command-based undo/redo for VisualSchema.
 *
 * Composes SnapshotHistory<VisualSchema> internally. Each execute() snapshots
 * the current state, applies the command, and returns the new state.
 * Public API is unchanged from the original implementation.
 */

import type { VisualSchema } from '../types.js'
import { type SchemaCommand, executeSchemaCommand } from './schemaCommands.js'
import { SnapshotHistory } from './snapshotHistory.js'

export class SchemaCommandHistory {
  private _history = new SnapshotHistory<VisualSchema>()

  get canUndo(): boolean {
    return this._history.canUndo
  }

  get canRedo(): boolean {
    return this._history.canRedo
  }

  /**
   * Execute a command, pushing the current state onto the undo stack.
   * Returns the new VisualSchema after applying the command.
   */
  execute(command: SchemaCommand, current: VisualSchema): VisualSchema {
    this._history.push(current)
    return executeSchemaCommand(current, command)
  }

  /**
   * Undo the last command, returning the previous VisualSchema.
   * Returns null if there is nothing to undo.
   */
  undo(current: VisualSchema): VisualSchema | null {
    return this._history.undo(current)
  }

  /**
   * Redo the last undone command, returning the re-applied VisualSchema.
   * Returns null if there is nothing to redo.
   */
  redo(current: VisualSchema): VisualSchema | null {
    return this._history.redo(current)
  }

  /**
   * Clear all history. Call on save or discard.
   */
  clear(): void {
    this._history.clear()
  }
}
