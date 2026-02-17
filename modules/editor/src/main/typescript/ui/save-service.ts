/**
 * SaveService — Pure logic for save/autosave lifecycle.
 *
 * Manages debounced autosave, dirty tracking, state transitions,
 * and concurrent save prevention. No DOM or Lit dependency.
 *
 * State machine:
 *   idle → dirty (on markDirty)
 *   dirty → saving (on autosave timer or forceSave)
 *   saving → saved (on success) → idle (after 2s)
 *   saving → error (on failure)
 *   saved → dirty (on markDirty before idle transition)
 *   error → dirty (on markDirty)
 */

import type { TemplateDocument } from '../types/index.js'

// ---------------------------------------------------------------------------
// State types
// ---------------------------------------------------------------------------

export type SaveState =
  | { status: 'idle' }
  | { status: 'dirty' }
  | { status: 'saving' }
  | { status: 'saved' }
  | { status: 'error'; message: string }

export type SaveFn = (doc: TemplateDocument) => Promise<void>

export type OnSaveStateChange = (state: SaveState) => void

// ---------------------------------------------------------------------------
// SaveService
// ---------------------------------------------------------------------------

export class SaveService {
  private _state: SaveState = { status: 'idle' }
  private _debounceTimer: ReturnType<typeof setTimeout> | null = null
  private _savedTimer: ReturnType<typeof setTimeout> | null = null
  private _disposed = false
  private _saving = false
  private _pendingDoc: TemplateDocument | null = null

  readonly _saveFn: SaveFn
  readonly _onChange: OnSaveStateChange
  readonly _debounceMs: number
  readonly _savedDisplayMs: number

  constructor(
    saveFn: SaveFn,
    onChange: OnSaveStateChange,
    debounceMs: number = 3000,
    savedDisplayMs: number = 2000,
  ) {
    this._saveFn = saveFn
    this._onChange = onChange
    this._debounceMs = debounceMs
    this._savedDisplayMs = savedDisplayMs
  }

  get state(): SaveState {
    return this._state
  }

  /**
   * Mark document as dirty. Transitions immediately to 'dirty' state
   * so the UI reflects changes on first keystroke.
   */
  markDirty(): void {
    if (this._disposed) return
    // Always transition to dirty (even from saving — we track pending)
    if (this._state.status !== 'dirty' && this._state.status !== 'saving') {
      this._clearSavedTimer()
      this._setState({ status: 'dirty' })
    } else if (this._state.status === 'saving') {
      // During save, we stay in 'saving' state but note we're dirty again
      // The post-save handler will check _pendingDoc
    }
  }

  /**
   * Schedule an autosave after the debounce period. Coalesces rapid
   * changes — only the last document version is saved.
   */
  scheduleAutoSave(doc: TemplateDocument): void {
    if (this._disposed) return
    this._clearDebounce()
    this._pendingDoc = doc
    this._debounceTimer = setTimeout(() => {
      this._debounceTimer = null
      this._doSave(doc)
    }, this._debounceMs)
  }

  /**
   * Immediately save the document (Ctrl+S, button click).
   * No-op when clean (idle or saved with no pending changes).
   */
  forceSave(doc: TemplateDocument): void {
    if (this._disposed) return
    // No-op when there's nothing to save
    if (
      (this._state.status === 'idle' || this._state.status === 'saved') &&
      !this._pendingDoc
    ) {
      return
    }
    this._clearDebounce()
    this._doSave(doc)
  }

  /**
   * Whether we have unsaved changes (dirty or currently saving).
   * Used for beforeunload warning.
   */
  get isDirtyOrSaving(): boolean {
    return (
      this._state.status === 'dirty' ||
      this._state.status === 'saving' ||
      this._pendingDoc !== null
    )
  }

  /**
   * Clean up all resources: clear timers, prevent further saves.
   */
  dispose(): void {
    this._disposed = true
    this._clearDebounce()
    this._clearSavedTimer()
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private _setState(state: SaveState): void {
    this._state = state
    this._onChange(state)
  }

  private _clearDebounce(): void {
    if (this._debounceTimer !== null) {
      clearTimeout(this._debounceTimer)
      this._debounceTimer = null
    }
  }

  private _clearSavedTimer(): void {
    if (this._savedTimer !== null) {
      clearTimeout(this._savedTimer)
      this._savedTimer = null
    }
  }

  private async _doSave(doc: TemplateDocument): Promise<void> {
    // Prevent concurrent saves — queue as pending
    if (this._saving) {
      this._pendingDoc = doc
      return
    }

    this._saving = true
    this._pendingDoc = null
    this._setState({ status: 'saving' })

    try {
      await this._saveFn(doc)

      if (this._disposed) return

      // If changes arrived during save, go to dirty and auto-save again
      if (this._pendingDoc) {
        const pendingDoc = this._pendingDoc
        this._saving = false
        this._setState({ status: 'dirty' })
        this.scheduleAutoSave(pendingDoc)
        return
      }

      this._saving = false
      this._setState({ status: 'saved' })

      // Auto-transition saved → idle after display period
      this._savedTimer = setTimeout(() => {
        this._savedTimer = null
        if (this._disposed) return
        if (this._state.status === 'saved') {
          this._setState({ status: 'idle' })
        }
      }, this._savedDisplayMs)
    } catch (err: unknown) {
      if (this._disposed) return
      this._saving = false
      this._pendingDoc = null
      const message = err instanceof Error ? err.message : 'Save failed'
      this._setState({ status: 'error', message })
    }
  }
}
