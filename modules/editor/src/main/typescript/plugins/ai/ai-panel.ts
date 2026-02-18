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
import type { ChatMessage, ChatAttachment, ProposalStatus } from './types.js'
import type { EditorEngine } from '../../engine/EditorEngine.js'
import type { TemplateDocument, NodeId } from '../../types/index.js'
import { nanoid } from 'nanoid'
import { applyProposal } from './apply-proposal.js'
import { icon } from '../../ui/icons.js'

const MAX_FILE_SIZE = 10 * 1024 * 1024 // 10 MB
const ACCEPTED_TYPES = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
]
const ACCEPT_STRING = '.pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document'

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
  @state() private _pendingFiles: ChatAttachment[] = []
  @state() private _attachError?: string

  private _prevService?: AiChatService
  private _userScrolledUp = false
  private _attachErrorTimer?: ReturnType<typeof setTimeout>

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
    if (!this.chatService || !this.doc) return
    const hasText = this._inputValue.trim().length > 0
    const hasFiles = this._pendingFiles.length > 0
    if (!hasText && !hasFiles) return

    const attachments = hasFiles ? [...this._pendingFiles] : undefined
    this.chatService.sendMessage(this._inputValue, this.doc, this.selectedNodeId ?? undefined, attachments)
    this._inputValue = ''
    this._pendingFiles = []
    this._userScrolledUp = false
  }

  private _handleStop(): void {
    this.chatService?.abort()
  }

  private _handleKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      if (this._inputValue.trim() || this._pendingFiles.length > 0) {
        this._handleSend()
      }
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

  private _handleAttachClick(): void {
    const input = this.querySelector<HTMLInputElement>('.ai-file-input')
    input?.click()
  }

  private _handleFileChange(e: Event): void {
    const input = e.target as HTMLInputElement
    if (!input.files?.length) return

    for (const file of Array.from(input.files)) {
      if (!ACCEPTED_TYPES.includes(file.type)) {
        this._showAttachError(`"${file.name}" is not a supported file type. Only PDF and DOCX are allowed.`)
        continue
      }
      if (file.size > MAX_FILE_SIZE) {
        this._showAttachError(`"${file.name}" exceeds the 10 MB size limit.`)
        continue
      }
      this._pendingFiles = [...this._pendingFiles, {
        id: nanoid(),
        name: file.name,
        size: file.size,
        type: file.type,
        file,
      }]
    }

    // Reset input so the same file can be re-selected
    input.value = ''
  }

  private _handleRemoveFile(id: string): void {
    this._pendingFiles = this._pendingFiles.filter((f) => f.id !== id)
  }

  private _showAttachError(message: string): void {
    this._attachError = message
    if (this._attachErrorTimer) clearTimeout(this._attachErrorTimer)
    this._attachErrorTimer = setTimeout(() => {
      this._attachError = undefined
    }, 3000)
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
          <input
            type="file"
            class="ai-file-input"
            accept=${ACCEPT_STRING}
            multiple
            @change=${this._handleFileChange}
            hidden
          />
          ${this._attachError
            ? html`<div class="ai-attach-error">${this._attachError}</div>`
            : nothing}
          ${this._pendingFiles.length > 0
            ? html`
              <div class="ai-pending-files">
                ${this._pendingFiles.map((f) => html`
                  <span class="ai-file-chip">
                    ${icon('file-text', 12)}
                    <span class="ai-file-chip-name">${f.name}</span>
                    <button class="ai-file-chip-remove" @click=${() => this._handleRemoveFile(f.id)} title="Remove file">
                      ${icon('x', 10)}
                    </button>
                  </span>
                `)}
              </div>`
            : nothing}
          <div class="ai-input-row">
            <button class="ai-attach-btn" @click=${this._handleAttachClick} title="Attach file">
              ${icon('paperclip', 16)}
            </button>
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
                  ?disabled=${!this._inputValue.trim() && this._pendingFiles.length === 0}
                  title="Send message"
                >
                  ${icon('arrow-up', 16)}
                </button>`
            }
          </div>
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
        ${msg.attachments && msg.attachments.length > 0
          ? html`
            <div class="ai-message-attachments">
              ${msg.attachments.map((a) => html`
                <span class="ai-attachment-badge">${icon('file-text', 10)} ${a.name}</span>
              `)}
            </div>`
          : nothing}
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
