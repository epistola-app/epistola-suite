/**
 * EpistolaThemeEditor — Root Lit element for the theme editor.
 *
 * Two-column layout: editing panel (left, scrollable) + placeholder (right).
 * Status bar at bottom with save state indicator.
 * Autosave with 2-second debounce, Ctrl+S for manual save,
 * beforeunload warning when dirty.
 */

import { LitElement, html } from 'lit'
import { customElement, state } from 'lit/decorators.js'
import { ThemeEditorState, type ThemeData } from './ThemeEditorState.js'
import { renderBasicInfoSection } from './sections/BasicInfoSection.js'
import { renderDocumentStylesSection } from './sections/DocumentStylesSection.js'
import { renderPageSettingsSection } from './sections/PageSettingsSection.js'
import { renderPresetsSection } from './sections/PresetsSection.js'

type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'error'

@customElement('epistola-theme-editor')
export class EpistolaThemeEditor extends LitElement {
  override createRenderRoot() {
    return this
  }

  // Injected from outside
  themeState?: ThemeEditorState
  onSave?: (payload: object) => Promise<void>

  @state() private _saveState: SaveState = 'idle'
  @state() private _errorMessage = ''
  @state() private _expandedPresets = new Set<string>()

  private _autosaveTimer?: ReturnType<typeof setTimeout>
  private _savedTimer?: ReturnType<typeof setTimeout>
  private _boundKeydown = this._handleKeydown.bind(this)
  private _boundBeforeUnload = this._handleBeforeUnload.bind(this)

  init(themeData: ThemeData, onSave: (payload: object) => Promise<void>): void {
    this.themeState = new ThemeEditorState(themeData)
    this.onSave = onSave

    this.themeState.addEventListener('change', () => {
      this._saveState = 'dirty'
      this._scheduleAutosave()
      this.requestUpdate()
    })
  }

  override connectedCallback() {
    super.connectedCallback()
    document.addEventListener('keydown', this._boundKeydown)
    window.addEventListener('beforeunload', this._boundBeforeUnload)
  }

  override disconnectedCallback() {
    super.disconnectedCallback()
    document.removeEventListener('keydown', this._boundKeydown)
    window.removeEventListener('beforeunload', this._boundBeforeUnload)
    if (this._autosaveTimer) clearTimeout(this._autosaveTimer)
    if (this._savedTimer) clearTimeout(this._savedTimer)
  }

  override render() {
    if (!this.themeState) {
      return html`<div class="theme-editor-empty">No theme loaded</div>`
    }

    return html`
      <div class="theme-editor-layout">
        <div class="theme-editor-panel">
          ${renderBasicInfoSection(this.themeState)}
          ${renderDocumentStylesSection(this.themeState)}
          ${renderPageSettingsSection(this.themeState)}
          ${renderPresetsSection(
            this.themeState,
            this._expandedPresets,
            (name) => this._togglePreset(name),
          )}
        </div>
        <div class="theme-editor-preview">
          <div class="theme-editor-preview-placeholder">
            Preview panel coming soon
          </div>
        </div>
      </div>
      ${this._renderStatusBar()}
    `
  }

  private _renderStatusBar(): unknown {
    const stateLabels: Record<SaveState, string> = {
      idle: 'No changes',
      dirty: 'Unsaved changes',
      saving: 'Saving...',
      saved: 'Saved',
      error: this._errorMessage || 'Save failed',
    }

    return html`
      <div class="theme-status-bar">
        <span class="theme-status-indicator theme-status-${this._saveState}">
          ${stateLabels[this._saveState]}
        </span>
        <button
          class="ep-btn-primary theme-save-btn"
          ?disabled=${this._saveState === 'saving' || this._saveState === 'idle' || this._saveState === 'saved'}
          @click=${() => this._save()}
        >
          ${this._saveState === 'saving' ? 'Saving...' : 'Save'}
        </button>
      </div>
    `
  }

  // -----------------------------------------------------------------------
  // Save logic
  // -----------------------------------------------------------------------

  private _scheduleAutosave(): void {
    if (this._autosaveTimer) clearTimeout(this._autosaveTimer)
    this._autosaveTimer = setTimeout(() => this._save(), 2000)
  }

  private async _save(): Promise<void> {
    if (!this.themeState || !this.onSave) return
    if (this._saveState === 'saving') return
    if (!this.themeState.isDirty) return

    if (this._autosaveTimer) clearTimeout(this._autosaveTimer)
    if (this._savedTimer) clearTimeout(this._savedTimer)

    const payload = this.themeState.computePatchPayload()
    if (Object.keys(payload).length === 0) return

    this._saveState = 'saving'
    this.requestUpdate()

    try {
      await this.onSave(payload)
      this.themeState.markSaved()
      this._saveState = 'saved'
      this._savedTimer = setTimeout(() => {
        this._saveState = 'idle'
        this.requestUpdate()
      }, 2000)
    } catch (err) {
      this._saveState = 'error'
      this._errorMessage = err instanceof Error ? err.message : 'Save failed'
    }
    this.requestUpdate()
  }

  // -----------------------------------------------------------------------
  // Event handlers
  // -----------------------------------------------------------------------

  private _handleKeydown(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') {
      e.preventDefault()
      this._save()
    }
  }

  private _handleBeforeUnload(e: BeforeUnloadEvent): void {
    if (this.themeState?.isDirty) {
      e.preventDefault()
    }
  }

  private _togglePreset(name: string): void {
    if (this._expandedPresets.has(name)) {
      this._expandedPresets.delete(name)
    } else {
      this._expandedPresets.add(name)
    }
    this.requestUpdate()
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-theme-editor': EpistolaThemeEditor
  }
}
