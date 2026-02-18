/**
 * Mock transport for AI chat development and testing.
 *
 * Streams canned text word-by-word with configurable delay, optionally
 * appends a proposal with a valid InsertNode command targeting the
 * document's root slot.
 *
 * Respects AbortSignal at each step.
 */

import type { SendMessageFn, AiProposal } from './types.js'
import type { NodeId, SlotId } from '../../types/index.js'
import { nanoid } from 'nanoid'

export interface MockTransportOptions {
  /** Delay in ms between each word (default: 30) */
  delayMs?: number
  /** Whether to include a proposal in the response (default: true) */
  includeProposal?: boolean
}

const CANNED_RESPONSES = [
  'I can help you with that! Let me suggest some changes to your template. I\'ve analyzed the document structure and here\'s what I recommend.',
  'Great question! Based on the current template layout, I think we should add a new text block to provide more content. Here\'s my suggestion for improving the design.',
  'Looking at your template, I notice a few opportunities for improvement. Let me propose adding a text element that will enhance the document\'s readability.',
]

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('Aborted', 'AbortError'))
      return
    }
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    }, { once: true })
  })
}

function createProposal(rootSlotId: SlotId): AiProposal {
  const newNodeId = nanoid() as NodeId
  return {
    description: 'Add a text block to the document',
    mode: 'commands',
    commands: [
      {
        type: 'InsertNode',
        node: {
          id: newNodeId,
          type: 'text',
          slots: [],
          props: { content: null },
        },
        slots: [],
        targetSlotId: rootSlotId,
        index: -1,
      },
    ],
  }
}

export function createMockTransport(options: MockTransportOptions = {}): SendMessageFn {
  const { delayMs = 30, includeProposal = true } = options

  return async (request, signal, onChunk) => {
    // Acknowledge uploaded files if any
    if (request.attachments && request.attachments.length > 0) {
      const names = request.attachments.map((a) => a.name).join(', ')
      const ack = `I've reviewed your uploaded file(s): ${names}. `
      onChunk({ type: 'text', content: ack })
    }

    // Pick a canned response based on message hash
    const responseIndex = Math.abs(hashCode(request.message || 'attachment')) % CANNED_RESPONSES.length
    const text = CANNED_RESPONSES[responseIndex]
    const words = text.split(' ')

    // Stream word by word
    for (let i = 0; i < words.length; i++) {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')

      const prefix = i === 0 ? '' : ' '
      onChunk({ type: 'text', content: prefix + words[i] })

      if (delayMs > 0) {
        await delay(delayMs, signal)
      }
    }

    // Optionally send a proposal
    if (includeProposal) {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')

      // Find the root slot in the document
      const rootNode = request.document.nodes[request.document.root]
      const rootSlotId = rootNode?.slots?.[0]
      if (rootSlotId) {
        onChunk({ type: 'proposal', proposal: createProposal(rootSlotId) })
      }
    }

    onChunk({ type: 'done', usage: { inputTokens: 150, outputTokens: words.length * 2 } })
  }
}

function hashCode(str: string): number {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i)
    hash = ((hash << 5) - hash) + char
    hash |= 0 // Convert to 32bit integer
  }
  return hash
}
