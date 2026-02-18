/**
 * <epistola-ai-panel> — AI chat sidebar panel.
 *
 * Light DOM Lit component. Layout:
 * - Messages area (scrollable, flex-grow)
 * - Input area (fixed bottom: textarea + send/stop button)
 * - Proposal cards inside assistant messages (Apply/Reject buttons)
 *
 * Receives `chatService` and `engine` as properties. Subscribes to
 * service state changes in `updated()` lifecycle when service changes.
 */

import { LitElement, html, nothing, type TemplateResult } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { AiChatService, ChatState } from './ai-chat-service.js'
import type { ChatMessage, ProposalStatus } from './types.js'
import type { EditorEngine } from '../../engine/EditorEngine.js'
import type { TemplateDocument, NodeId } from '../../types/index.js'
import { applyProposal } from './apply-proposal.js'
import { icon } from '../../ui/icons.js'

@customElement('epistola-ai-panel')
export class EpistolaAiPanel extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) chatService?: AiChatService
  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument
  @property({ attribute: false }) selectedNodeId: NodeId | null = null

  @state() private _messages: readonly ChatMessage[] = []
  @state() private _status: 'idle' | 'streaming' | 'error' = 'idle'
  @state() private _error?: string
  @state() private _inputValue = ''

  private _prevService?: AiChatService
  private _userScrolledUp = false

  override updated(): void {
    // Subscribe to chat service when it changes
    if (this.chatService && this.chatService !== this._prevService) {
      this._prevService = this.chatService

      // Sync initial state
      const state = this.chatService.state
      this._messages = state.messages
      this._status = state.status
      this._error = state.error
    }
  }

  /**
   * Called by the plugin factory to wire up state change notifications.
   * We keep this as a method rather than doing it in updated() because
   * the onChange callback is set once on the service constructor.
   */
  handleStateChange(state: ChatState): void {
    this._messages = state.messages
    this._status = state.status
    this._error = state.error
    this._autoScroll()
  }

  // ---------------------------------------------------------------------------
  // Event handlers
  // ---------------------------------------------------------------------------

  private _handleSend(): void {
    if (!this.chatService || !this.doc || !this._inputValue.trim()) return
    this.chatService.sendMessage(this._inputValue, this.doc, this.selectedNodeId ?? undefined)
    this._inputValue = ''
    this._userScrolledUp = false
  }

  private _handleStop(): void {
    this.chatService?.abort()
  }

  private _handleKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      this._handleSend()
    }
  }

  private _handleInput(e: Event): void {
    this._inputValue = (e.target as HTMLTextAreaElement).value
  }

  private _handleScroll(e: Event): void {
    const el = e.target as HTMLElement
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
    this._userScrolledUp = !atBottom
  }

  private _handleApply(msg: ChatMessage): void {
    if (!msg.proposal || !this.engine || !this.chatService) return
    const result = applyProposal(this.engine, msg.proposal)
    this.chatService.setProposalStatus(msg.id, result.ok ? 'applied' : 'rejected')
  }

  private _handleReject(msg: ChatMessage): void {
    if (!this.chatService) return
    this.chatService.setProposalStatus(msg.id, 'rejected')
  }

  // ---------------------------------------------------------------------------
  // Auto-scroll
  // ---------------------------------------------------------------------------

  private _autoScroll(): void {
    if (this._userScrolledUp) return
    requestAnimationFrame(() => {
      const el = this.querySelector('.ai-messages')
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    })
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  override render() {
    return html`
      <div class="ai-panel">
        <div class="ai-messages" @scroll=${this._handleScroll}>
          ${this._messages.length === 0
            ? this._renderEmptyState()
            : this._messages.map((msg) => this._renderMessage(msg))}
          ${this._status === 'error' && this._error
            ? html`<div class="ai-error">${this._error}</div>`
            : nothing}
        </div>
        <div class="ai-input-area">
          <textarea
            class="ai-input"
            placeholder="Ask AI about your template..."
            .value=${this._inputValue}
            @input=${this._handleInput}
            @keydown=${this._handleKeydown}
            rows="2"
          ></textarea>
          ${this._status === 'streaming'
            ? html`
              <button class="ai-stop-btn" @click=${this._handleStop} title="Stop generating">
                ${icon('square', 14)}
              </button>`
            : html`
              <button
                class="ai-send-btn"
                @click=${this._handleSend}
                ?disabled=${!this._inputValue.trim()}
                title="Send message"
              >
                ${icon('arrow-up', 16)}
              </button>`
          }
        </div>
      </div>
    `
  }

  private _renderEmptyState(): TemplateResult {
    return html`
      <div class="ai-empty">
        <div class="ai-empty-icon">${icon('sparkles', 32)}</div>
        <p class="ai-empty-title">AI Assistant</p>
        <p class="ai-empty-text">Ask questions about your template or request changes.</p>
      </div>
    `
  }

  private _renderMessage(msg: ChatMessage): TemplateResult {
    const isUser = msg.role === 'user'
    const isStreaming = this._status === 'streaming' && msg === this._messages[this._messages.length - 1] && !isUser

    return html`
      <div class="ai-message ${isUser ? 'ai-message-user' : 'ai-message-assistant'} ${isStreaming ? 'ai-message-streaming' : ''}">
        <div class="ai-message-content">${msg.content}${isStreaming ? html`<span class="ai-cursor"></span>` : nothing}</div>
        ${msg.proposal ? this._renderProposal(msg) : nothing}
      </div>
    `
  }

  private _renderProposal(msg: ChatMessage): TemplateResult {
    const status = msg.proposalStatus ?? 'pending'
    return html`
      <div class="ai-proposal">
        <div class="ai-proposal-header">
          <span class="ai-proposal-description">${msg.proposal!.description}</span>
          ${this._renderProposalBadge(status)}
        </div>
        ${status === 'pending'
          ? html`
            <div class="ai-proposal-actions">
              <button class="ai-proposal-apply" @click=${() => this._handleApply(msg)}>Apply</button>
              <button class="ai-proposal-reject" @click=${() => this._handleReject(msg)}>Reject</button>
            </div>`
          : nothing}
      </div>
    `
  }

  private _renderProposalBadge(status: ProposalStatus): TemplateResult {
    if (status === 'pending') return html``
    const label = status === 'applied' ? 'Applied' : 'Rejected'
    return html`<span class="ai-proposal-badge ai-proposal-badge-${status}">${label}</span>`
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-ai-panel': EpistolaAiPanel
  }
}
