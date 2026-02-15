import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import { PreviewService, type PreviewState, type FetchPreviewFn } from './preview-service.js'
import { icon } from './icons.js'

/**
 * <epistola-preview> — PDF preview panel.
 *
 * Subscribes to engine `doc:change` and `example:change` events to trigger
 * debounced preview refreshes. Renders an iframe with the blob URL on success,
 * or appropriate loading/error/idle states.
 *
 * Light DOM — styled by preview.css.
 */
@customElement('epistola-preview')
export class EpistolaPreview extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) fetchPreview?: FetchPreviewFn

  @state() private _previewState: PreviewState = { status: 'idle' }

  private _service?: PreviewService
  private _unsubDoc?: () => void
  private _unsubExample?: () => void

  override connectedCallback(): void {
    super.connectedCallback()
    this._setupService()
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('engine') || changed.has('fetchPreview')) {
      this._teardown()
      this._setupService()
    }
  }

  override disconnectedCallback(): void {
    this._teardown()
    super.disconnectedCallback()
  }

  private _setupService(): void {
    if (!this.engine || !this.fetchPreview) return

    this._service = new PreviewService(this.fetchPreview, (state) => {
      this._previewState = state
    })

    this._unsubDoc = this.engine.events.on('doc:change', ({ doc }) => {
      this._service?.scheduleRefresh(doc, this.engine?.currentExample)
    })

    this._unsubExample = this.engine.events.on('example:change', ({ example }) => {
      if (this.engine) {
        this._service?.scheduleRefresh(this.engine.doc, example)
      }
    })

    // Trigger initial preview
    this._service.scheduleRefresh(this.engine.doc, this.engine.currentExample)
  }

  private _teardown(): void {
    this._unsubDoc?.()
    this._unsubExample?.()
    this._service?.dispose()
    this._service = undefined
    this._unsubDoc = undefined
    this._unsubExample = undefined
  }

  private _handleRetry(): void {
    if (this.engine && this._service) {
      this._service.forceRefresh(this.engine.doc, this.engine.currentExample)
    }
  }

  override render() {
    return html`
      <div class="preview-header">
        <span class="preview-header-title">Preview</span>
        ${this._previewState.status === 'loading'
          ? html`<span class="preview-header-status">${icon('loader-2', 14)} Loading...</span>`
          : nothing}
      </div>
      <div class="preview-content">${this._renderContent()}</div>
    `
  }

  private _renderContent() {
    switch (this._previewState.status) {
      case 'idle':
        return html`<div class="preview-placeholder">
          <span class="preview-placeholder-icon">${icon('eye', 32)}</span>
          <span>Preview will appear here</span>
        </div>`

      case 'loading':
        return html`<div class="preview-placeholder">
          <span class="preview-spinner">${icon('loader-2', 32)}</span>
          <span>Generating preview...</span>
        </div>`

      case 'success':
        return html`<iframe
          class="preview-iframe"
          .src=${this._previewState.blobUrl}
          title="PDF Preview"
        ></iframe>`

      case 'error':
        return html`<div class="preview-error">
          <span class="preview-error-icon">${icon('alert-circle', 32)}</span>
          <span class="preview-error-message">${this._previewState.message}</span>
          <button class="preview-retry-btn" @click=${this._handleRetry}>
            ${icon('refresh-cw', 14)} Retry
          </button>
        </div>`
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-preview': EpistolaPreview
  }
}
