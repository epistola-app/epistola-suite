import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import { icon } from './icons.js'

@customElement('epistola-toolbar')
export class EpistolaToolbar extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ type: Boolean }) previewOpen = false
  @property({ type: Boolean }) hasPreview = false

  @state() private _currentExampleIndex = 0

  private _unsubExample?: () => void
  private _unsubDoc?: () => void

  override connectedCallback(): void {
    super.connectedCallback()
    this._subscribeToEngine()
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('engine')) {
      this._unsubscribeAll()
      this._subscribeToEngine()
    }
  }

  override disconnectedCallback(): void {
    this._unsubscribeAll()
    super.disconnectedCallback()
  }

  private _unsubscribeAll(): void {
    this._unsubExample?.()
    this._unsubDoc?.()
  }

  private _subscribeToEngine(): void {
    if (!this.engine) return
    this._currentExampleIndex = this.engine.currentExampleIndex
    this._unsubExample = this.engine.events.on('example:change', ({ index }) => {
      this._currentExampleIndex = index
    })
    // Re-render on doc changes so undo/redo button state stays in sync
    this._unsubDoc = this.engine.events.on('doc:change', () => {
      this.requestUpdate()
    })
  }

  private _handleUndo() {
    this.engine?.undo()
    this.requestUpdate()
  }

  private _handleRedo() {
    this.engine?.redo()
    this.requestUpdate()
  }

  private _handleTogglePreview() {
    this.dispatchEvent(new CustomEvent('toggle-preview', { bubbles: true, composed: true }))
  }

  private _handleExampleChange(e: Event) {
    const select = e.target as HTMLSelectElement
    const index = parseInt(select.value, 10)
    this.engine?.setCurrentExample(index)
  }

  override render() {
    const canUndo = this.engine?.canUndo ?? false
    const canRedo = this.engine?.canRedo ?? false
    const examples = this.engine?.dataExamples
    const hasExamples = examples && examples.length > 0

    return html`
      <div class="epistola-toolbar">
        <span class="toolbar-title">Template Editor</span>

        <div class="toolbar-separator"></div>

        <div class="toolbar-actions">
          <button
            class="toolbar-btn"
            ?disabled=${!canUndo}
            @click=${this._handleUndo}
            title="Undo (Ctrl+Z)"
          >
            ${icon('undo-2')} Undo
          </button>
          <button
            class="toolbar-btn"
            ?disabled=${!canRedo}
            @click=${this._handleRedo}
            title="Redo (Ctrl+Shift+Z)"
          >
            ${icon('redo-2')} Redo
          </button>
        </div>

        ${this.hasPreview
          ? html`
              <div class="toolbar-separator"></div>
              <button
                class="toolbar-btn ${this.previewOpen ? 'active' : ''}"
                @click=${this._handleTogglePreview}
                title="${this.previewOpen ? 'Hide preview' : 'Show preview'}"
              >
                ${this.previewOpen ? icon('eye-off') : icon('eye')} Preview
              </button>
            `
          : nothing}

        ${hasExamples ? this._renderExampleSelector(examples!) : nothing}
      </div>
    `
  }

  private _renderExampleSelector(examples: object[]) {
    return html`
      <div class="toolbar-example-selector">
        <label class="toolbar-example-label" for="example-select">Data</label>
        <select
          id="example-select"
          class="toolbar-example-select"
          .value=${String(this._currentExampleIndex)}
          @change=${this._handleExampleChange}
        >
          ${examples.map((ex, i) => {
            const label = this._getExampleLabel(ex, i)
            return html`<option value=${i} ?selected=${i === this._currentExampleIndex}>${label}</option>`
          })}
        </select>
      </div>
    `
  }

  /**
   * Derive a display label for an example. Uses `name` or `label` field
   * if present, otherwise falls back to "Example N".
   */
  private _getExampleLabel(example: object, index: number): string {
    const obj = example as Record<string, unknown>
    if (typeof obj.name === 'string' && obj.name) return obj.name
    if (typeof obj.label === 'string' && obj.label) return obj.label
    return `Example ${index + 1}`
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-toolbar': EpistolaToolbar
  }
}
