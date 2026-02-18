import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { AiChatService, type ChatState } from './ai-chat-service.js'
import type { SendMessageFn, ChatChunk, ChatAttachment, ChatRequest } from './types.js'
import { createTestDocument, resetCounter } from '../../engine/test-helpers.js'

beforeEach(() => {
  resetCounter()
})

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const DOC = createTestDocument()

function createMockSend(
  chunks: ChatChunk[] = [{ type: 'text', content: 'Hello!' }, { type: 'done' }],
): { fn: SendMessageFn; calls: { message: string; signal: AbortSignal }[] } {
  const calls: { message: string; signal: AbortSignal }[] = []
  const fn: SendMessageFn = async (request, signal, onChunk) => {
    calls.push({ message: request.message, signal })
    for (const chunk of chunks) {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
      onChunk(chunk)
      await Promise.resolve() // yield to simulate async
    }
  }
  return { fn, calls }
}

function collectStates(service: AiChatService): ChatState[] {
  const states: ChatState[] = []
  // Replace onChange via a wrapper — we observe via the constructor callback
  return states
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

describe('AiChatService', () => {
  describe('initial state', () => {
    it('starts idle with no messages', () => {
      const service = new AiChatService(vi.fn(), vi.fn())

      expect(service.status).toBe('idle')
      expect(service.messages).toEqual([])
      expect(service.state).toEqual({
        status: 'idle',
        messages: [],
        error: undefined,
      })
    })
  })

  // ---------------------------------------------------------------------------
  // sendMessage
  // ---------------------------------------------------------------------------

  describe('sendMessage', () => {
    it('adds user + assistant messages and transitions idle→streaming→idle', async () => {
      const states: ChatState[] = []
      const { fn } = createMockSend()
      const service = new AiChatService(fn, (s) => states.push(structuredClone(s)))

      await service.sendMessage('Hi there', DOC)

      // Should have transitioned through: streaming (with user+assistant), then idle
      expect(service.status).toBe('idle')
      expect(service.messages).toHaveLength(2)
      expect(service.messages[0].role).toBe('user')
      expect(service.messages[0].content).toBe('Hi there')
      expect(service.messages[1].role).toBe('assistant')
      expect(service.messages[1].content).toBe('Hello!')
    })

    it('trims whitespace from message', async () => {
      const { fn, calls } = createMockSend()
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('  spaced  ', DOC)

      expect(calls[0].message).toBe('spaced')
      expect(service.messages[0].content).toBe('spaced')
    })

    it('ignores empty messages', async () => {
      const { fn, calls } = createMockSend()
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('', DOC)
      await service.sendMessage('   ', DOC)

      expect(calls).toHaveLength(0)
      expect(service.messages).toHaveLength(0)
    })

    it('streams text chunks into the assistant message', async () => {
      const { fn } = createMockSend([
        { type: 'text', content: 'Hello ' },
        { type: 'text', content: 'world!' },
        { type: 'done' },
      ])
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('Test', DOC)

      expect(service.messages[1].content).toBe('Hello world!')
    })

    it('handles proposal chunks', async () => {
      const proposal = {
        description: 'Add a text block',
        mode: 'commands' as const,
        commands: [{ type: 'InsertNode', node: { id: 'n1', type: 'text', slots: [] }, slots: [], targetSlotId: 's1', index: -1 }],
      }
      const { fn } = createMockSend([
        { type: 'text', content: 'Here is my suggestion:' },
        { type: 'proposal', proposal },
        { type: 'done' },
      ])
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('Test', DOC)

      expect(service.messages[1].proposal).toEqual(proposal)
      expect(service.messages[1].proposalStatus).toBe('pending')
    })

    it('handles error chunks', async () => {
      const { fn } = createMockSend([
        { type: 'text', content: 'Starting...' },
        { type: 'error', message: 'Rate limit exceeded' },
      ])
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('Test', DOC)

      expect(service.status).toBe('error')
      expect(service.state.error).toBe('Rate limit exceeded')
    })

    it('handles transport errors', async () => {
      const fn: SendMessageFn = async () => {
        throw new Error('Network failure')
      }
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('Test', DOC)

      expect(service.status).toBe('error')
      expect(service.state.error).toBe('Network failure')
    })

    it('passes conversationId and selectedNodeId to transport', async () => {
      const calls: { conversationId: string; selectedNodeId?: string }[] = []
      const fn: SendMessageFn = async (request, _signal, onChunk) => {
        calls.push({ conversationId: request.conversationId, selectedNodeId: request.selectedNodeId as string | undefined })
        onChunk({ type: 'done' })
      }
      const service = new AiChatService(fn, vi.fn(), 'conv-123')

      await service.sendMessage('Test', DOC, 'n1' as any)

      expect(calls[0].conversationId).toBe('conv-123')
      expect(calls[0].selectedNodeId).toBe('n1')
    })

    it('fires onChange on every state transition', async () => {
      const states: ChatState[] = []
      const { fn } = createMockSend([
        { type: 'text', content: 'Hi' },
        { type: 'done' },
      ])
      const service = new AiChatService(fn, (s) => states.push(structuredClone(s)))

      await service.sendMessage('Test', DOC)

      // At minimum: streaming start, text chunk update, idle at end
      expect(states.length).toBeGreaterThanOrEqual(3)
      expect(states[0].status).toBe('streaming')
      expect(states[states.length - 1].status).toBe('idle')
    })
  })

  // ---------------------------------------------------------------------------
  // Abort
  // ---------------------------------------------------------------------------

  describe('abort', () => {
    it('aborts in-flight request and transitions to idle', async () => {
      let resolveBlock: () => void
      const blocked = new Promise<void>((r) => { resolveBlock = r })

      const fn: SendMessageFn = async (_request, signal, onChunk) => {
        onChunk({ type: 'text', content: 'Partial' })
        await blocked
        if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
        onChunk({ type: 'done' })
      }
      const service = new AiChatService(fn, vi.fn())

      const promise = service.sendMessage('Test', DOC)

      // Let the first chunk through
      await Promise.resolve()
      await Promise.resolve()

      expect(service.status).toBe('streaming')
      service.abort()
      expect(service.status).toBe('idle')

      // Unblock the transport
      resolveBlock!()
      await promise

      // Keeps partial content
      expect(service.messages[1].content).toBe('Partial')
    })

    it('new sendMessage() aborts previous request', async () => {
      let callCount = 0
      const fn: SendMessageFn = async (request, signal, onChunk) => {
        callCount++
        onChunk({ type: 'text', content: `Response ${callCount}` })
        // Simulate a delay
        await new Promise((r) => setTimeout(r, 0))
        if (signal.aborted) throw new DOMException('Aborted', 'AbortError')
        onChunk({ type: 'done' })
      }
      const service = new AiChatService(fn, vi.fn())

      // Start two messages — second should abort the first
      const p1 = service.sendMessage('First', DOC)
      const p2 = service.sendMessage('Second', DOC)
      await Promise.all([p1, p2])

      // Should have 4 messages: user1, assistant1(partial), user2, assistant2
      expect(service.messages).toHaveLength(4)
      expect(service.messages[0].content).toBe('First')
      expect(service.messages[2].content).toBe('Second')
      expect(service.status).toBe('idle')
    })
  })

  // ---------------------------------------------------------------------------
  // setProposalStatus
  // ---------------------------------------------------------------------------

  describe('setProposalStatus', () => {
    it('updates proposal status on a specific message', async () => {
      const proposal = { description: 'Test', mode: 'commands' as const, commands: [] }
      const { fn } = createMockSend([
        { type: 'proposal', proposal },
        { type: 'done' },
      ])
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('Test', DOC)

      const msgId = service.messages[1].id
      expect(service.messages[1].proposalStatus).toBe('pending')

      service.setProposalStatus(msgId, 'applied')
      expect(service.messages[1].proposalStatus).toBe('applied')
    })

    it('fires onChange when proposal status changes', async () => {
      const states: ChatState[] = []
      const proposal = { description: 'Test', mode: 'commands' as const, commands: [] }
      const { fn } = createMockSend([
        { type: 'proposal', proposal },
        { type: 'done' },
      ])
      const service = new AiChatService(fn, (s) => states.push(structuredClone(s)))

      await service.sendMessage('Test', DOC)
      const countBefore = states.length

      service.setProposalStatus(service.messages[1].id, 'rejected')

      expect(states.length).toBe(countBefore + 1)
      const lastState = states[states.length - 1]
      expect(lastState.messages[1].proposalStatus).toBe('rejected')
    })

    it('ignores unknown message IDs', async () => {
      const { fn } = createMockSend()
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('Test', DOC)

      // Should not throw
      service.setProposalStatus('unknown-id', 'applied')
      expect(service.messages).toHaveLength(2)
    })
  })

  // ---------------------------------------------------------------------------
  // Attachments
  // ---------------------------------------------------------------------------

  describe('attachments', () => {
    function createAttachment(name: string): ChatAttachment {
      const file = new File(['dummy'], name, { type: 'application/pdf' })
      return { id: `att-${name}`, name, size: file.size, type: file.type, file }
    }

    it('stores attachments on user message', async () => {
      const { fn } = createMockSend()
      const service = new AiChatService(fn, vi.fn())

      const att = createAttachment('report.pdf')
      await service.sendMessage('Check this', DOC, undefined, [att])

      expect(service.messages[0].attachments).toHaveLength(1)
      expect(service.messages[0].attachments![0].name).toBe('report.pdf')
    })

    it('passes attachments to the transport', async () => {
      const requests: ChatRequest[] = []
      const fn: SendMessageFn = async (request, _signal, onChunk) => {
        requests.push(request)
        onChunk({ type: 'done' })
      }
      const service = new AiChatService(fn, vi.fn())

      const att = createAttachment('document.docx')
      await service.sendMessage('Review', DOC, undefined, [att])

      expect(requests[0].attachments).toHaveLength(1)
      expect(requests[0].attachments![0].name).toBe('document.docx')
    })

    it('allows sending with only attachments (empty message)', async () => {
      const { fn } = createMockSend()
      const service = new AiChatService(fn, vi.fn())

      const att = createAttachment('file.pdf')
      await service.sendMessage('', DOC, undefined, [att])

      expect(service.messages).toHaveLength(2)
      expect(service.messages[0].content).toBe('')
      expect(service.messages[0].attachments).toHaveLength(1)
    })

    it('omits attachments field when none provided', async () => {
      const requests: ChatRequest[] = []
      const fn: SendMessageFn = async (request, _signal, onChunk) => {
        requests.push(request)
        onChunk({ type: 'done' })
      }
      const service = new AiChatService(fn, vi.fn())

      await service.sendMessage('No files', DOC)

      expect(requests[0].attachments).toBeUndefined()
      expect(service.messages[0].attachments).toBeUndefined()
    })
  })

  // ---------------------------------------------------------------------------
  // Dispose
  // ---------------------------------------------------------------------------

  describe('dispose', () => {
    it('prevents further messages after dispose', async () => {
      const { fn, calls } = createMockSend()
      const service = new AiChatService(fn, vi.fn())

      service.dispose()
      await service.sendMessage('Test', DOC)

      expect(calls).toHaveLength(0)
      expect(service.messages).toHaveLength(0)
    })

    it('aborts in-flight request on dispose', async () => {
      let signalRef: AbortSignal | undefined
      const fn: SendMessageFn = async (_request, signal, onChunk) => {
        signalRef = signal
        await new Promise((r) => setTimeout(r, 100))
        onChunk({ type: 'done' })
      }
      const service = new AiChatService(fn, vi.fn())

      const promise = service.sendMessage('Test', DOC)
      // Let it start
      await Promise.resolve()

      service.dispose()
      await promise

      expect(signalRef!.aborted).toBe(true)
    })
  })
})
