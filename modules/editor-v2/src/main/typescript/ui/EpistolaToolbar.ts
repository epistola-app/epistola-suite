import { LitElement, html } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import type { EditorEngine } from '../engine/EditorEngine.js'

@customElement('epistola-toolbar')
export class EpistolaToolbar extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine

  private _handleUndo() {
    this.engine?.undo()
    this.requestUpdate()
  }

  private _handleRedo() {
    this.engine?.redo()
    this.requestUpdate()
  }

  override render() {
    const canUndo = this.engine?.canUndo ?? false
    const canRedo = this.engine?.canRedo ?? false

    return html`
      <div class="epistola-toolbar">
        <span class="toolbar-title">Template Editor</span>

        <div class="toolbar-actions">
          <button
            class="toolbar-btn"
            ?disabled=${!canUndo}
            @click=${this._handleUndo}
            title="Undo (Ctrl+Z)"
          >
            Undo
          </button>
          <button
            class="toolbar-btn"
            ?disabled=${!canRedo}
            @click=${this._handleRedo}
            title="Redo (Ctrl+Shift+Z)"
          >
            Redo
          </button>
        </div>
      </div>
    `
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-toolbar': EpistolaToolbar
  }
}
