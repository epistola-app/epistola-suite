import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createMockTransport } from './mock-transport.js'
import type { ChatChunk } from './types.js'
import { createTestDocument, resetCounter } from '../../engine/test-helpers.js'

beforeEach(() => {
  vi.useFakeTimers()
  resetCounter()
})

afterEach(() => {
  vi.useRealTimers()
})

const DOC = createTestDocument()

function collectChunks(): { chunks: ChatChunk[]; onChunk: (chunk: ChatChunk) => void } {
  const chunks: ChatChunk[] = []
  return { chunks, onChunk: (chunk) => chunks.push(chunk) }
}

// ---------------------------------------------------------------------------
// Streaming behavior
// ---------------------------------------------------------------------------

describe('createMockTransport', () => {
  it('streams text chunks and a done chunk', async () => {
    const transport = createMockTransport({ delayMs: 0, includeProposal: false })
    const { chunks, onChunk } = collectChunks()

    await transport(
      { conversationId: 'test', message: 'Hello', document: DOC },
      new AbortController().signal,
      onChunk,
    )

    const textChunks = chunks.filter((c) => c.type === 'text')
    const doneChunks = chunks.filter((c) => c.type === 'done')

    expect(textChunks.length).toBeGreaterThan(0)
    expect(doneChunks).toHaveLength(1)

    // Reconstructed text should be non-empty
    const fullText = textChunks.map((c) => (c as { type: 'text'; content: string }).content).join('')
    expect(fullText.trim().length).toBeGreaterThan(0)
  })

  it('includes a proposal when includeProposal is true', async () => {
    const transport = createMockTransport({ delayMs: 0, includeProposal: true })
    const { chunks, onChunk } = collectChunks()

    await transport(
      { conversationId: 'test', message: 'Hello', document: DOC },
      new AbortController().signal,
      onChunk,
    )

    const proposalChunks = chunks.filter((c) => c.type === 'proposal')
    expect(proposalChunks).toHaveLength(1)

    const proposal = (proposalChunks[0] as { type: 'proposal'; proposal: any }).proposal
    expect(proposal.mode).toBe('commands')
    expect(proposal.commands).toHaveLength(1)
    expect(proposal.commands[0].type).toBe('InsertNode')
  })

  it('proposal targets the document root slot', async () => {
    const transport = createMockTransport({ delayMs: 0, includeProposal: true })
    const { chunks, onChunk } = collectChunks()

    await transport(
      { conversationId: 'test', message: 'Hello', document: DOC },
      new AbortController().signal,
      onChunk,
    )

    const proposal = (chunks.find((c) => c.type === 'proposal') as any).proposal
    const rootNode = DOC.nodes[DOC.root]
    const rootSlotId = rootNode.slots[0]
    expect(proposal.commands[0].targetSlotId).toBe(rootSlotId)
  })

  it('omits proposal when includeProposal is false', async () => {
    const transport = createMockTransport({ delayMs: 0, includeProposal: false })
    const { chunks, onChunk } = collectChunks()

    await transport(
      { conversationId: 'test', message: 'Hello', document: DOC },
      new AbortController().signal,
      onChunk,
    )

    const proposalChunks = chunks.filter((c) => c.type === 'proposal')
    expect(proposalChunks).toHaveLength(0)
  })

  it('provides usage info in done chunk', async () => {
    const transport = createMockTransport({ delayMs: 0, includeProposal: false })
    const { chunks, onChunk } = collectChunks()

    await transport(
      { conversationId: 'test', message: 'Hello', document: DOC },
      new AbortController().signal,
      onChunk,
    )

    const done = chunks.find((c) => c.type === 'done') as { type: 'done'; usage?: { inputTokens: number; outputTokens: number } }
    expect(done.usage).toBeDefined()
    expect(done.usage!.inputTokens).toBeGreaterThan(0)
    expect(done.usage!.outputTokens).toBeGreaterThan(0)
  })

  // ---------------------------------------------------------------------------
  // Delay behavior
  // ---------------------------------------------------------------------------

  it('delays between words when delayMs > 0', async () => {
    const transport = createMockTransport({ delayMs: 50, includeProposal: false })
    const { chunks, onChunk } = collectChunks()

    const promise = transport(
      { conversationId: 'test', message: 'Hello', document: DOC },
      new AbortController().signal,
      onChunk,
    )

    // Initially only the first word should be emitted
    expect(chunks.length).toBe(1)

    // Advance past a few delays
    await vi.advanceTimersByTimeAsync(200)

    // Should have more chunks now
    expect(chunks.length).toBeGreaterThan(1)

    // Advance enough for all words
    await vi.advanceTimersByTimeAsync(5000)
    await promise

    const doneChunks = chunks.filter((c) => c.type === 'done')
    expect(doneChunks).toHaveLength(1)
  })

  // ---------------------------------------------------------------------------
  // Abort behavior
  // ---------------------------------------------------------------------------

  it('throws AbortError when signal is already aborted', async () => {
    const transport = createMockTransport({ delayMs: 0, includeProposal: false })
    const controller = new AbortController()
    controller.abort()

    await expect(
      transport(
        { conversationId: 'test', message: 'Hello', document: DOC },
        controller.signal,
        vi.fn(),
      ),
    ).rejects.toThrow('Aborted')
  })

  it('throws AbortError when aborted during streaming', async () => {
    const transport = createMockTransport({ delayMs: 50, includeProposal: false })
    const controller = new AbortController()
    const { chunks, onChunk } = collectChunks()

    const promise = transport(
      { conversationId: 'test', message: 'Hello', document: DOC },
      controller.signal,
      onChunk,
    )

    // Let the first word emit
    expect(chunks.length).toBe(1)

    // Abort mid-stream
    controller.abort()

    await expect(promise).rejects.toThrow('Aborted')

    // Should not have received all chunks
    const doneChunks = chunks.filter((c) => c.type === 'done')
    expect(doneChunks).toHaveLength(0)
  })

  // ---------------------------------------------------------------------------
  // Deterministic response selection
  // ---------------------------------------------------------------------------

  it('returns consistent response for same input', async () => {
    const transport = createMockTransport({ delayMs: 0, includeProposal: false })

    const run = async (msg: string) => {
      const { chunks, onChunk } = collectChunks()
      await transport(
        { conversationId: 'test', message: msg, document: DOC },
        new AbortController().signal,
        onChunk,
      )
      return chunks.filter((c) => c.type === 'text').map((c) => (c as any).content).join('')
    }

    const result1 = await run('How are you?')
    const result2 = await run('How are you?')
    expect(result1).toBe(result2)
  })
})
