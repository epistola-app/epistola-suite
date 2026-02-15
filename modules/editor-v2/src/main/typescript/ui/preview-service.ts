/**
 * PreviewService — Pure logic for PDF preview lifecycle.
 *
 * Manages debounced fetch requests, abort signals, blob URL lifecycle,
 * and state transitions. No DOM or Lit dependency (except URL.createObjectURL).
 *
 * State machine: idle → loading → success | error
 * Any new scheduleRefresh() while loading aborts the in-flight request.
 */

import type { TemplateDocument } from '../types/index.js'

// ---------------------------------------------------------------------------
// State types
// ---------------------------------------------------------------------------

export type PreviewState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; blobUrl: string }
  | { status: 'error'; message: string }

export type FetchPreviewFn = (
  doc: TemplateDocument,
  data: object | undefined,
  signal: AbortSignal,
) => Promise<Blob>

export type OnStateChange = (state: PreviewState) => void

// ---------------------------------------------------------------------------
// PreviewService
// ---------------------------------------------------------------------------

export class PreviewService {
  private _state: PreviewState = { status: 'idle' }
  private _debounceTimer: ReturnType<typeof setTimeout> | null = null
  private _abortController: AbortController | null = null
  private _currentBlobUrl: string | null = null
  private _disposed = false

  readonly _fetchFn: FetchPreviewFn
  readonly _onChange: OnStateChange
  readonly _debounceMs: number

  constructor(fetchFn: FetchPreviewFn, onChange: OnStateChange, debounceMs: number = 500) {
    this._fetchFn = fetchFn
    this._onChange = onChange
    this._debounceMs = debounceMs
  }

  get state(): PreviewState {
    return this._state
  }

  /**
   * Schedule a debounced preview refresh. Resets the timer on each call,
   * so rapid changes coalesce into a single fetch.
   */
  scheduleRefresh(doc: TemplateDocument, data: object | undefined): void {
    if (this._disposed) return
    this._clearDebounce()
    this._debounceTimer = setTimeout(() => {
      this._debounceTimer = null
      this._doFetch(doc, data)
    }, this._debounceMs)
  }

  /**
   * Immediately trigger a preview refresh (e.g. retry button).
   */
  forceRefresh(doc: TemplateDocument, data: object | undefined): void {
    if (this._disposed) return
    this._clearDebounce()
    this._doFetch(doc, data)
  }

  /**
   * Clean up all resources: abort in-flight, revoke blob, clear timers.
   */
  dispose(): void {
    this._disposed = true
    this._clearDebounce()
    this._abort()
    this._revokeBlobUrl()
  }

  // ---------------------------------------------------------------------------
  // Internal
  // ---------------------------------------------------------------------------

  private _setState(state: PreviewState): void {
    this._state = state
    this._onChange(state)
  }

  private _clearDebounce(): void {
    if (this._debounceTimer !== null) {
      clearTimeout(this._debounceTimer)
      this._debounceTimer = null
    }
  }

  private _abort(): void {
    if (this._abortController) {
      this._abortController.abort()
      this._abortController = null
    }
  }

  private _revokeBlobUrl(): void {
    if (this._currentBlobUrl) {
      URL.revokeObjectURL(this._currentBlobUrl)
      this._currentBlobUrl = null
    }
  }

  private async _doFetch(doc: TemplateDocument, data: object | undefined): Promise<void> {
    // Abort any in-flight request
    this._abort()

    const controller = new AbortController()
    this._abortController = controller

    this._setState({ status: 'loading' })

    try {
      const blob = await this._fetchFn(doc, data, controller.signal)

      // Check if we were disposed or aborted during the await
      if (this._disposed || controller.signal.aborted) return

      // Revoke previous blob URL before creating new one
      this._revokeBlobUrl()

      const blobUrl = URL.createObjectURL(blob)
      this._currentBlobUrl = blobUrl
      this._abortController = null
      this._setState({ status: 'success', blobUrl })
    } catch (err: unknown) {
      // Silently ignore AbortError — it means we intentionally cancelled
      if (err instanceof DOMException && err.name === 'AbortError') return
      if (this._disposed) return

      this._abortController = null
      const message = err instanceof Error ? err.message : 'Preview failed'
      this._setState({ status: 'error', message })
    }
  }
}
