import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { SaveState } from './save-service.js'
import type { ToolbarAction } from '../plugins/types.js'
import { icon } from './icons.js'
import { buildShortcutGroupsProjection, type ShortcutGroup } from './shortcuts.js'
import { normalizeShortcutEvent } from '../shortcuts/resolver.js'
import {
  EDITOR_SHORTCUT_COMMAND_IDS,
  getShortcutDisplayForCommandId,
} from '../shortcuts/editor-runtime.js'

function toTooltipShortcutLabel(helpKeys: string): string {
  return helpKeys.replaceAll('{cmd}', 'Ctrl/Cmd')
}

const UNDO_SHORTCUT_HELP = getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.undo)
const REDO_SHORTCUT_HELP = getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.redo)
const SAVE_SHORTCUT_HELP = getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.save)

const SHORTCUT_ACTIVE_FEEDBACK_MS = 650
const SHORTCUTS_TRIGGER_SELECTOR = '.toolbar-shortcuts-trigger'
const SHORTCUTS_SEARCH_SELECTOR = '.toolbar-shortcuts-search-input'
const SHORTCUTS_POPOVER_ID = 'epistola-toolbar-shortcuts-popover'

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
  @state() private _shortcutsQuery = ''
  @state() private _activeShortcutStrokes: string[] = []

  private _unsubExample?: () => void
  private _unsubDoc?: () => void
  private _activeShortcutClearTimeout: ReturnType<typeof setTimeout> | null = null
  private _onWindowKeydown = (e: KeyboardEvent) => {
    if (!this._shortcutsOpen) return
    if (e.key === 'Escape') {
      e.preventDefault()
      this._closeShortcuts({ restoreFocus: true })
      return
    }

    if (e.key === 'Shift' || e.key === 'Control' || e.key === 'Alt' || e.key === 'Meta') {
      return
    }

    const normalized = normalizeShortcutEvent(e)
    const activeStrokes = [normalized.keyStroke, normalized.codeStroke]
      .map((stroke) => stroke.trim().toLowerCase())
      .filter((stroke) => stroke.length > 0)
    this._activeShortcutStrokes = [...new Set(activeStrokes)]
    this._scheduleClearActiveShortcutFeedback()
  }
  private _onWindowPointerDown = (e: PointerEvent) => {
    if (!this._shortcutsOpen) return
    const target = e.target
    if (target instanceof Element && target.closest('.toolbar-shortcuts')) return
    this._closeShortcuts()
  }

  override connectedCallback(): void {
    super.connectedCallback()
    this._subscribeToEngine()
    window.addEventListener('keydown', this._onWindowKeydown)
    window.addEventListener('pointerdown', this._onWindowPointerDown, true)
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
    window.removeEventListener('pointerdown', this._onWindowPointerDown, true)
    this._clearActiveShortcutFeedback()
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

  private _scheduleClearActiveShortcutFeedback(): void {
    if (this._activeShortcutClearTimeout) {
      clearTimeout(this._activeShortcutClearTimeout)
    }
    this._activeShortcutClearTimeout = setTimeout(() => {
      this._activeShortcutStrokes = []
      this._activeShortcutClearTimeout = null
    }, SHORTCUT_ACTIVE_FEEDBACK_MS)
  }

  private _clearActiveShortcutFeedback(): void {
    if (this._activeShortcutClearTimeout) {
      clearTimeout(this._activeShortcutClearTimeout)
      this._activeShortcutClearTimeout = null
    }
    this._activeShortcutStrokes = []
  }

  private _focusShortcutSearchAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const input = this.querySelector<HTMLInputElement>(SHORTCUTS_SEARCH_SELECTOR)
        input?.focus({ preventScroll: true })
      })
    })
  }

  private _focusShortcutTriggerAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const trigger = this.querySelector<HTMLElement>(SHORTCUTS_TRIGGER_SELECTOR)
        trigger?.focus({ preventScroll: true })
      })
    })
  }

  private _openShortcuts(): void {
    this._shortcutsOpen = true
    this._clearActiveShortcutFeedback()
    this._focusShortcutSearchAfterRender()
  }

  private _closeShortcuts(options: { restoreFocus?: boolean } = {}): void {
    if (!this._shortcutsOpen) return
    this._shortcutsOpen = false
    this._shortcutsQuery = ''
    this._clearActiveShortcutFeedback()
    if (options.restoreFocus) {
      this._focusShortcutTriggerAfterRender()
    }
  }

  private _toggleShortcutHelp(e: Event) {
    e.stopPropagation()
    if (this._shortcutsOpen) {
      this._closeShortcuts()
      return
    }
    this._openShortcuts()
  }

  openShortcuts(): void {
    this._openShortcuts()
  }

  private _handleShortcutSearchInput(e: Event): void {
    const target = e.target as HTMLInputElement
    this._shortcutsQuery = target.value
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
            title=${`Undo (${toTooltipShortcutLabel(UNDO_SHORTCUT_HELP)})`}
          >
            ${icon('undo-2')} Undo
          </button>
          <button
            class="toolbar-btn"
            ?disabled=${!canRedo}
            @click=${this._handleRedo}
            title=${`Redo (${toTooltipShortcutLabel(REDO_SHORTCUT_HELP)})`}
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
        title = `Save (${toTooltipShortcutLabel(SAVE_SHORTCUT_HELP)})`
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
    const shortcutProjection = buildShortcutGroupsProjection({
      query: this._shortcutsQuery,
      activeStrokes: this._activeShortcutStrokes,
    })
    const shortcutGroups = shortcutProjection.groups

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
            data-testid="shortcuts-trigger"
            type="button"
            title="Keyboard shortcuts"
            aria-label="Keyboard shortcuts"
            aria-haspopup="dialog"
            aria-controls=${SHORTCUTS_POPOVER_ID}
            aria-expanded=${String(this._shortcutsOpen)}
            @click=${this._toggleShortcutHelp}
          >
            ${icon('command')}
          </button>

          ${this._shortcutsOpen
            ? html`
                <div
                  id=${SHORTCUTS_POPOVER_ID}
                  class="toolbar-shortcuts-popover"
                  data-testid="shortcuts-popover"
                  role="dialog"
                  aria-label="Keyboard shortcuts"
                >
                  <div class="toolbar-shortcuts-title">Keyboard Shortcuts</div>
                  <div class="toolbar-shortcuts-search">
                    <input
                      class="toolbar-shortcuts-search-input"
                      type="search"
                      placeholder="Search shortcuts"
                      .value=${this._shortcutsQuery}
                      aria-label="Filter keyboard shortcuts"
                      @input=${this._handleShortcutSearchInput}
                    />
                  </div>
                  <div class="toolbar-shortcuts-groups">
                    ${shortcutGroups.length === 0
                      ? html`<div class="toolbar-shortcuts-empty">No shortcuts found for this filter.</div>`
                      : shortcutGroups.map((group: ShortcutGroup) => html`
                      <div class="toolbar-shortcuts-group ${group.fullWidth ? 'is-full-width' : ''}">
                        <div class="toolbar-shortcuts-group-title">${group.title}</div>
                        <div class="toolbar-shortcuts-items ${group.layout === 'two-column' ? 'layout-two-column' : 'layout-one-column'}">
                          ${group.items.map((item) => html`
                            <div class="toolbar-shortcuts-row ${item.active ? 'is-active' : ''}">
                              <span class="toolbar-shortcuts-keys">${this._renderShortcutKeys(item.keys)}</span>
                              <span class="toolbar-shortcuts-action">${item.action}</span>
                            </div>
                          `)}
                        </div>
                      </div>
                    `)}
                  </div>
                  <div class="toolbar-shortcuts-footer">Tip: ${shortcutProjection.footerTip} opens this help</div>
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
