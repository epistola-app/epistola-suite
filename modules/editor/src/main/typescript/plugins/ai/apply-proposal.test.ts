import { describe, it, expect, beforeEach } from 'vitest'
import { applyProposal } from './apply-proposal.js'
import type { AiProposal } from './types.js'
import { EditorEngine } from '../../engine/EditorEngine.js'
import { createTestDocument, testRegistry, resetCounter, nodeId, slotId } from '../../engine/test-helpers.js'
import type { NodeId, SlotId } from '../../types/index.js'

beforeEach(() => {
  resetCounter()
})

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createEngine() {
  const doc = createTestDocument()
  const engine = new EditorEngine(doc, testRegistry())
  const rootSlotId = Object.values(engine.doc.slots).find((s) => s.name === 'children')!.id
  return { engine, rootSlotId }
}

function makeTextNode(id: string = 'ai-text-1'): { id: NodeId; type: string; slots: SlotId[]; props: Record<string, unknown> } {
  return {
    id: id as NodeId,
    type: 'text',
    slots: [],
    props: { content: null },
  }
}

// ---------------------------------------------------------------------------
// Command mode
// ---------------------------------------------------------------------------

describe('applyProposal — command mode', () => {
  it('applies a single InsertNode command', () => {
    const { engine, rootSlotId } = createEngine()
    const node = makeTextNode()
    const proposal: AiProposal = {
      description: 'Add a text block',
      mode: 'commands',
      commands: [
        {
          type: 'InsertNode',
          node,
          slots: [],
          targetSlotId: rootSlotId,
          index: -1,
        },
      ],
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(true)
    expect(result.appliedCount).toBe(1)

    // Verify the node was actually inserted
    const rootSlot = engine.doc.slots[rootSlotId]
    expect(rootSlot.children).toContain(node.id)
    expect(engine.doc.nodes[node.id]).toBeDefined()
    expect(engine.doc.nodes[node.id].type).toBe('text')
  })

  it('applied commands are undoable', () => {
    const { engine, rootSlotId } = createEngine()
    const node = makeTextNode()
    const proposal: AiProposal = {
      description: 'Add a text block',
      mode: 'commands',
      commands: [
        {
          type: 'InsertNode',
          node,
          slots: [],
          targetSlotId: rootSlotId,
          index: -1,
        },
      ],
    }

    applyProposal(engine, proposal)
    expect(engine.doc.nodes[node.id]).toBeDefined()

    // Undo should remove the inserted node
    const undone = engine.undo()
    expect(undone).toBe(true)
    expect(engine.doc.nodes[node.id]).toBeUndefined()
  })

  it('applies multiple commands in sequence', () => {
    const { engine, rootSlotId } = createEngine()
    const node1 = makeTextNode('ai-text-1')
    const node2 = makeTextNode('ai-text-2')
    const proposal: AiProposal = {
      description: 'Add two text blocks',
      mode: 'commands',
      commands: [
        { type: 'InsertNode', node: node1, slots: [], targetSlotId: rootSlotId, index: -1 },
        { type: 'InsertNode', node: node2, slots: [], targetSlotId: rootSlotId, index: -1 },
      ],
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(true)
    expect(result.appliedCount).toBe(2)

    const rootSlot = engine.doc.slots[rootSlotId]
    expect(rootSlot.children).toContain(node1.id)
    expect(rootSlot.children).toContain(node2.id)
  })

  it('returns error when a command fails', () => {
    const { engine } = createEngine()
    const proposal: AiProposal = {
      description: 'Remove nonexistent node',
      mode: 'commands',
      commands: [
        { type: 'RemoveNode', nodeId: 'nonexistent' as NodeId },
      ],
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(false)
    expect(result.error).toContain('Command 1/1 failed')
    expect(result.appliedCount).toBe(0)
  })

  it('stops on first failing command and reports progress', () => {
    const { engine, rootSlotId } = createEngine()
    const node1 = makeTextNode('ai-text-1')
    const proposal: AiProposal = {
      description: 'Two commands, second fails',
      mode: 'commands',
      commands: [
        { type: 'InsertNode', node: node1, slots: [], targetSlotId: rootSlotId, index: -1 },
        { type: 'RemoveNode', nodeId: 'nonexistent' as NodeId },
      ],
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(false)
    expect(result.appliedCount).toBe(1) // First succeeded
    expect(result.error).toContain('Command 2/2 failed')

    // First command was still applied
    expect(engine.doc.nodes[node1.id]).toBeDefined()
  })

  it('returns error for empty commands array', () => {
    const { engine } = createEngine()
    const proposal: AiProposal = {
      description: 'No commands',
      mode: 'commands',
      commands: [],
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(false)
    expect(result.error).toBe('Proposal has no commands')
  })

  it('returns error when commands is undefined', () => {
    const { engine } = createEngine()
    const proposal: AiProposal = {
      description: 'Missing commands',
      mode: 'commands',
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(false)
    expect(result.error).toBe('Proposal has no commands')
  })
})

// ---------------------------------------------------------------------------
// Replace mode
// ---------------------------------------------------------------------------

describe('applyProposal — replace mode', () => {
  it('replaces the entire document', () => {
    const { engine } = createEngine()
    const newDoc = createTestDocument()

    const proposal: AiProposal = {
      description: 'Replace document',
      mode: 'replace',
      document: newDoc,
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(true)
    expect(engine.doc.root).toBe(newDoc.root)
  })

  it('clears undo stack after replace', () => {
    const { engine, rootSlotId } = createEngine()

    // Make a change first
    engine.dispatch({
      type: 'InsertNode',
      node: makeTextNode(),
      slots: [],
      targetSlotId: rootSlotId,
      index: -1,
    })
    expect(engine.canUndo).toBe(true)

    // Replace document
    const newDoc = createTestDocument()
    applyProposal(engine, {
      description: 'Replace',
      mode: 'replace',
      document: newDoc,
    })

    expect(engine.canUndo).toBe(false)
  })

  it('returns error when document is missing', () => {
    const { engine } = createEngine()
    const proposal: AiProposal = {
      description: 'Missing document',
      mode: 'replace',
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(false)
    expect(result.error).toBe('Proposal has no document')
  })
})

// ---------------------------------------------------------------------------
// Unknown mode
// ---------------------------------------------------------------------------

describe('applyProposal — unknown mode', () => {
  it('returns error for unknown mode', () => {
    const { engine } = createEngine()
    const proposal = {
      description: 'Unknown',
      mode: 'magic' as any,
    }

    const result = applyProposal(engine, proposal)

    expect(result.ok).toBe(false)
    expect(result.error).toContain('Unknown proposal mode')
  })
})
