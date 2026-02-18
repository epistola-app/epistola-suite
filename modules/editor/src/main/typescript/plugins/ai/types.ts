/**
 * AI chat plugin types.
 *
 * Defines the transport boundary (SendMessageFn) and internal state
 * types for the AI assistant sidebar panel.
 */

import type { TemplateDocument, NodeId } from '../../types/index.js'
import type { AnyCommand } from '../../engine/commands.js'

// ---------------------------------------------------------------------------
// File attachments — raw files passed through to the transport
// ---------------------------------------------------------------------------

export interface ChatAttachment {
  id: string
  name: string
  size: number
  type: string
  file: File
}

// ---------------------------------------------------------------------------
// Transport boundary — host page provides the SendMessageFn implementation
// ---------------------------------------------------------------------------

export interface ChatRequest {
  conversationId: string
  message: string
  document: TemplateDocument
  selectedNodeId?: NodeId
  attachments?: ChatAttachment[]
}

export type ChatChunk =
  | { type: 'text'; content: string }
  | { type: 'proposal'; proposal: AiProposal }
  | { type: 'done'; usage?: { inputTokens: number; outputTokens: number } }
  | { type: 'error'; message: string }

/**
 * Transport function provided by the host page. Streams response chunks
 * via the `onChunk` callback. Must respect `signal` for cancellation.
 */
export type SendMessageFn = (
  request: ChatRequest,
  signal: AbortSignal,
  onChunk: (chunk: ChatChunk) => void,
) => Promise<void>

// ---------------------------------------------------------------------------
// AI proposal — instructions for modifying the template document
// ---------------------------------------------------------------------------

export interface AiProposal {
  description: string
  mode: 'commands' | 'replace'
  commands?: AnyCommand[]
  document?: TemplateDocument
}

export type ProposalStatus = 'pending' | 'applied' | 'rejected'

// ---------------------------------------------------------------------------
// Chat message — internal state
// ---------------------------------------------------------------------------

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  attachments?: ChatAttachment[]
  proposal?: AiProposal
  proposalStatus?: ProposalStatus
  timestamp: number
}
