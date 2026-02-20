import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { SaveState } from './save-service.js'
import type { ToolbarAction } from '../plugins/types.js'
import { icon } from './icons.js'
import { buildShortcutGroups, type ShortcutGroup } from './shortcuts.js'

const SHORTCUT_GROUPS: ShortcutGroup[] = buildShortcutGroups()

@customElement('epistola-toolbar')
export class EpistolaToolbar extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ type: Boolean }) previewOpen = false
  @property({ type: Boolean }) hasPreview = false
  @property({ type: Boolean }) hasSave = false
  @property({ attribute: false }) saveState?: SaveState
  @property({ attribute: false }) pluginActions?: ToolbarAction[]

  @state() private _currentExampleIndex = 0
  @state() private _shortcutsOpen = false

  private _unsubExample?: () => void
  private _unsubDoc?: () => void
  private _onWindowKeydown = (e: KeyboardEvent) => {
    if (!this._shortcutsOpen) return
    if (e.key !== 'Escape') return
    e.preventDefault()
    this._shortcutsOpen = false
  }
  private _onWindowPointerDown = (e: PointerEvent) => {
    if (!this._shortcutsOpen) return
    const target = e.target as Node | null
    if (target && this.contains(target)) return
    this._shortcutsOpen = false
  }

  override connectedCallback(): void {
    super.connectedCallback()
    this._subscribeToEngine()
    window.addEventListener('keydown', this._onWindowKeydown)
    window.addEventListener('pointerdown', this._onWindowPointerDown)
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('engine')) {
      this._unsubscribeAll()
      this._subscribeToEngine()
    }
  }

  override disconnectedCallback(): void {
    this._unsubscribeAll()
    window.removeEventListener('keydown', this._onWindowKeydown)
    window.removeEventListener('pointerdown', this._onWindowPointerDown)
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

  private _handleForceSave() {
    this.dispatchEvent(new CustomEvent('force-save', { bubbles: true, composed: true }))
  }

  private _handleTogglePreview() {
    this.dispatchEvent(new CustomEvent('toggle-preview', { bubbles: true, composed: true }))
  }

  private _handleExampleChange(e: Event) {
    const select = e.target as HTMLSelectElement
    const index = parseInt(select.value, 10)
    this.engine?.setCurrentExample(index)
  }

  private _toggleShortcutHelp(e: Event) {
    e.stopPropagation()
    this._shortcutsOpen = !this._shortcutsOpen
  }

  openShortcuts(): void {
    this._shortcutsOpen = true
  }

  private _renderShortcutKeys(keys: string) {
    const parts = keys.split('{cmd}')
    return html`${parts.map((part, i) => html`
      ${i > 0 ? html`<span class="shortcut-cmd-icon">${icon('command', 12)}</span>` : nothing}${part}
    `)}`
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

        ${this.hasSave ? this._renderSaveButton() : nothing}

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

        ${this._renderPluginActions()}
      </div>
    `
  }

  private _renderPluginActions() {
    if (!this.pluginActions || this.pluginActions.length === 0) return nothing

    return html`
      <div class="toolbar-separator"></div>
      ${this.pluginActions.map(
        (action) => html`
          <button
            class="toolbar-btn"
            @click=${action.onClick}
            title=${action.label}
          >
            ${icon(action.icon as Parameters<typeof icon>[0])} ${action.label}
          </button>
        `,
      )}
    `
  }

  private _renderSaveButton() {
    const status = this.saveState?.status ?? 'idle'
    const isError = status === 'error'
    const errorMsg = isError ? (this.saveState as { status: 'error'; message: string }).message : ''

    // Determine button attributes based on state
    const disabled = status === 'idle' || status === 'saving' || status === 'saved'
    const cssClass = `toolbar-btn ${status === 'saving' ? 'saving' : ''} ${status === 'saved' ? 'saved' : ''} ${isError ? 'save-error' : ''}`

    let iconName: 'save' | 'check' | 'loader-2' | 'triangle-alert'
    let label: string
    let title: string

    switch (status) {
      case 'idle':
        iconName = 'check'
        label = 'Saved'
        title = 'All changes saved'
        break
      case 'dirty':
        iconName = 'save'
        label = 'Save'
        title = 'Save (Ctrl+S)'
        break
      case 'saving':
        iconName = 'loader-2'
        label = 'Saving...'
        title = 'Saving changes...'
        break
      case 'saved':
        iconName = 'check'
        label = 'Saved'
        title = 'All changes saved'
        break
      case 'error':
        iconName = 'triangle-alert'
        label = 'Save'
        title = `Save failed: ${errorMsg}. Click to retry.`
        break
    }

    return html`
      <div class="toolbar-separator"></div>
      <button
        class=${cssClass}
        ?disabled=${disabled}
        @click=${this._handleForceSave}
        title=${title}
      >
        ${icon(iconName)} ${label}
      </button>
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

        <div class="toolbar-shortcuts">
          <button
            class="toolbar-shortcuts-trigger"
            type="button"
            title="Keyboard shortcuts"
            aria-label="Keyboard shortcuts"
            aria-expanded=${String(this._shortcutsOpen)}
            @click=${this._toggleShortcutHelp}
          >
            ${icon('command')}
          </button>

          ${this._shortcutsOpen
            ? html`
                <div class="toolbar-shortcuts-popover" role="dialog" aria-label="Keyboard shortcuts">
                  <div class="toolbar-shortcuts-title">Keyboard Shortcuts</div>
                  ${SHORTCUT_GROUPS.map((group) => html`
                    <div class="toolbar-shortcuts-group ${group.dividerAfter ? 'with-divider' : ''}">
                      <div class="toolbar-shortcuts-group-title">${group.title}</div>
                      ${group.items.map((item) => html`
                        <div class="toolbar-shortcuts-row">
                          <span class="toolbar-shortcuts-keys">${this._renderShortcutKeys(item.keys)}</span>
                          <span class="toolbar-shortcuts-action">${item.action}</span>
                        </div>
                      `)}
                    </div>
                  `)}
                  <div class="toolbar-shortcuts-footer">Tip: Leader + ? opens this help</div>
                </div>
              `
            : nothing}
        </div>
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
