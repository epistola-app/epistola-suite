/**
 * AiChatService — Pure logic for AI chat lifecycle.
 *
 * Manages conversation state, streaming responses, abort signals,
 * and state transitions. No DOM or Lit dependency.
 *
 * State machine: idle → streaming → idle (or error)
 * Any new sendMessage() while streaming aborts the in-flight request.
 *
 * Follows PreviewService patterns: _disposed flag, AbortController per
 * request, AbortError silently ignored, onChange callback.
 */

import { nanoid } from 'nanoid'
import type { TemplateDocument, NodeId } from '../../types/index.js'
import type { SendMessageFn, ChatMessage, ProposalStatus } from './types.js'

// ---------------------------------------------------------------------------
// State types
// ---------------------------------------------------------------------------

export type ChatStatus = 'idle' | 'streaming' | 'error'

export interface ChatState {
  status: ChatStatus
  messages: readonly ChatMessage[]
  error?: string
}

export type OnChatStateChange = (state: ChatState) => void

// ---------------------------------------------------------------------------
// AiChatService
// ---------------------------------------------------------------------------

export class AiChatService {
  private _status: ChatStatus = 'idle'
  private _messages: ChatMessage[] = []
  private _error?: string
  private _abortController: AbortController | null = null
  private _disposed = false
  private _conversationId: string

  readonly _sendFn: SendMessageFn
  readonly _onChange: OnChatStateChange

  constructor(sendFn: SendMessageFn, onChange: OnChatStateChange, conversationId?: string) {
    this._sendFn = sendFn
    this._onChange = onChange
    this._conversationId = conversationId ?? nanoid()
  }

  get state(): ChatState {
    return {
      status: this._status,
      messages: this._messages,
      error: this._error,
    }
  }

  get messages(): readonly ChatMessage[] {
    return this._messages
  }

  get status(): ChatStatus {
    return this._status
  }

  /**
   * Send a user message and stream the assistant response.
   * Aborts any in-flight request before starting a new one.
   */
  async sendMessage(message: string, document: TemplateDocument, selectedNodeId?: NodeId): Promise<void> {
    if (this._disposed) return

    const trimmed = message.trim()
    if (!trimmed) return

    // Abort any in-flight request
    this.abort()

    // Add user message
    const userMessage: ChatMessage = {
      id: nanoid(),
      role: 'user',
      content: trimmed,
      timestamp: Date.now(),
    }
    this._messages = [...this._messages, userMessage]

    // Create assistant message placeholder for streaming
    const assistantMessage: ChatMessage = {
      id: nanoid(),
      role: 'assistant',
      content: '',
      timestamp: Date.now(),
    }
    this._messages = [...this._messages, assistantMessage]

    // Set up abort controller
    const controller = new AbortController()
    this._abortController = controller

    this._setStatus('streaming')

    try {
      await this._sendFn(
        {
          conversationId: this._conversationId,
          message: trimmed,
          document,
          selectedNodeId,
        },
        controller.signal,
        (chunk) => {
          if (this._disposed || controller.signal.aborted) return

          switch (chunk.type) {
            case 'text':
              this._updateLastAssistantMessage((msg) => ({
                ...msg,
                content: msg.content + chunk.content,
              }))
              break

            case 'proposal':
              this._updateLastAssistantMessage((msg) => ({
                ...msg,
                proposal: chunk.proposal,
                proposalStatus: 'pending',
              }))
              break

            case 'error':
              this._error = chunk.message
              this._setStatus('error')
              break

            case 'done':
              // No-op, handled after await
              break
          }
        },
      )

      if (this._disposed || controller.signal.aborted) return

      // Only transition to idle if we weren't already set to error by a chunk
      if (this._status === 'streaming') {
        this._setStatus('idle')
      }
    } catch (err: unknown) {
      // Silently ignore AbortError — it means we intentionally cancelled
      if (err instanceof DOMException && err.name === 'AbortError') return
      if (this._disposed) return

      this._error = err instanceof Error ? err.message : 'An error occurred'
      this._setStatus('error')
    } finally {
      if (this._abortController === controller) {
        this._abortController = null
      }
    }
  }

  /**
   * Abort the current in-flight request. Keeps any partial content
   * that was streamed as a message.
   */
  abort(): void {
    if (this._abortController) {
      this._abortController.abort()
      this._abortController = null
    }
    if (this._status === 'streaming') {
      this._setStatus('idle')
    }
  }

  /**
   * Update the proposal status on a specific message.
   */
  setProposalStatus(messageId: string, status: ProposalStatus): void {
    const idx = this._messages.findIndex((m) => m.id === messageId)
    if (idx === -1) return

    const msg = this._messages[idx]
    if (!msg.proposal) return

    this._messages = [
      ...this._messages.slice(0, idx),
      { ...msg, proposalStatus: status },
      ...this._messages.slice(idx + 1),
    ]
    this._notify()
  }

  /**
   * Clean up all resources.
   */
  dispose(): void {
    this._disposed = true
    this.abort()
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private _setStatus(status: ChatStatus): void {
    this._status = status
    this._notify()
  }

  private _notify(): void {
    this._onChange({
      status: this._status,
      messages: this._messages,
      error: this._error,
    })
  }

  private _updateLastAssistantMessage(updater: (msg: ChatMessage) => ChatMessage): void {
    const lastIdx = this._messages.length - 1
    if (lastIdx < 0 || this._messages[lastIdx].role !== 'assistant') return

    this._messages = [
      ...this._messages.slice(0, lastIdx),
      updater(this._messages[lastIdx]),
    ]
    this._notify()
  }
}
