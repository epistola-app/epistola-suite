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
      <div class="epistola-toolbar flex items-center gap-2 px-4 py-2 border-b border-gray-200 bg-white">
        <span class="text-sm font-semibold text-gray-700">Template Editor</span>

        <div class="flex items-center gap-1 ml-4">
          <button
            class="px-2 py-1 text-xs rounded border ${canUndo ? 'border-gray-300 hover:bg-gray-100 text-gray-700' : 'border-gray-100 text-gray-300 cursor-not-allowed'}"
            ?disabled=${!canUndo}
            @click=${this._handleUndo}
            title="Undo (Ctrl+Z)"
          >
            Undo
          </button>
          <button
            class="px-2 py-1 text-xs rounded border ${canRedo ? 'border-gray-300 hover:bg-gray-100 text-gray-700' : 'border-gray-100 text-gray-300 cursor-not-allowed'}"
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
