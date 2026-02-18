/**
 * Apply an AI proposal to the editor engine.
 *
 * - **Command mode**: dispatches each command individually via engine.dispatch().
 *   Each command is independently undoable.
 * - **Replace mode**: calls engine.replaceDocument() which clears the undo stack.
 */

import type { EditorEngine } from '../../engine/EditorEngine.js'
import type { AiProposal } from './types.js'

export interface ApplyResult {
  ok: boolean
  error?: string
  appliedCount?: number
}

export function applyProposal(engine: EditorEngine, proposal: AiProposal): ApplyResult {
  if (proposal.mode === 'commands') {
    const commands = proposal.commands
    if (!commands || commands.length === 0) {
      return { ok: false, error: 'Proposal has no commands' }
    }

    let appliedCount = 0
    for (const cmd of commands) {
      const result = engine.dispatch(cmd)
      if (!result.ok) {
        return {
          ok: false,
          error: `Command ${appliedCount + 1}/${commands.length} failed: ${result.error}`,
          appliedCount,
        }
      }
      appliedCount++
    }

    return { ok: true, appliedCount }
  }

  if (proposal.mode === 'replace') {
    const doc = proposal.document
    if (!doc) {
      return { ok: false, error: 'Proposal has no document' }
    }

    engine.replaceDocument(doc)
    return { ok: true }
  }

  return { ok: false, error: `Unknown proposal mode: ${(proposal as any).mode}` }
}
