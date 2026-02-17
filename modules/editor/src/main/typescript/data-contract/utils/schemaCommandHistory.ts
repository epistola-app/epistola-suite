/**
 * SchemaCommandHistory — Snapshot-based undo/redo stack for VisualSchema.
 *
 * Each execute() pushes a structuredClone of the current state onto the undo
 * stack before applying the command. Redo stack is cleared on new commands.
 * VisualSchema for typical contracts is small (<50 fields), so snapshots are cheap.
 */

import type { VisualSchema } from '../types.js'
import { type SchemaCommand, executeSchemaCommand } from './schemaCommands.js'

export class SchemaCommandHistory {
  private undoStack: VisualSchema[] = []
  private redoStack: VisualSchema[] = []

  get canUndo(): boolean {
    return this.undoStack.length > 0
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0
  }

  /**
   * Execute a command, pushing the current state onto the undo stack.
   * Returns the new VisualSchema after applying the command.
   */
  execute(command: SchemaCommand, current: VisualSchema): VisualSchema {
    this.undoStack.push(structuredClone(current))
    this.redoStack = []
    return executeSchemaCommand(current, command)
  }

  /**
   * Undo the last command, returning the previous VisualSchema.
   * Returns null if there is nothing to undo.
   */
  undo(current: VisualSchema): VisualSchema | null {
    if (!this.canUndo) return null
    this.redoStack.push(structuredClone(current))
    return this.undoStack.pop()!
  }

  /**
   * Redo the last undone command, returning the re-applied VisualSchema.
   * Returns null if there is nothing to redo.
   */
  redo(current: VisualSchema): VisualSchema | null {
    if (!this.canRedo) return null
    this.undoStack.push(structuredClone(current))
    return this.redoStack.pop()!
  }

  /**
   * Clear all history. Call on save or discard.
   */
  clear(): void {
    this.undoStack = []
    this.redoStack = []
  }
}
