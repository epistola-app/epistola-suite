import { LitElement, html } from 'lit'
import { customElement } from 'lit/decorators.js'

const STORAGE_KEY = 'ep:preview-width'
const MIN_WIDTH = 200
const MAX_WIDTH = 800
const DEFAULT_WIDTH = 400

/**
 * <epistola-resize-handle> — Draggable divider between canvas and preview.
 *
 * On pointer-drag, updates `--ep-preview-width` on the closest `.editor-main`
 * ancestor. Width is persisted to localStorage.
 *
 * Light DOM — styled by preview.css.
 */
@customElement('epistola-resize-handle')
export class EpistolaResizeHandle extends LitElement {
  override createRenderRoot() {
    return this
  }

  private _dragging = false
  private _startX = 0
  private _startWidth = 0

  /** Read persisted width, or fall back to default. */
  static getPersistedWidth(): number {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored) {
        const parsed = parseInt(stored, 10)
        if (!isNaN(parsed)) return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, parsed))
      }
    } catch {
      // localStorage may be unavailable
    }
    return DEFAULT_WIDTH
  }

  private _onPointerDown = (e: PointerEvent) => {
    e.preventDefault()
    this._dragging = true
    this._startX = e.clientX

    const main = this.closest('.editor-main') as HTMLElement | null
    if (!main) return

    const currentWidth = parseInt(
      getComputedStyle(main).getPropertyValue('--ep-preview-width') || String(DEFAULT_WIDTH),
      10,
    )
    this._startWidth = isNaN(currentWidth) ? DEFAULT_WIDTH : currentWidth

    // Capture pointer for reliable tracking even outside the element
    this.setPointerCapture(e.pointerId)

    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
  }

  private _onPointerMove = (e: PointerEvent) => {
    if (!this._dragging) return

    // Dragging left (negative delta) → panel grows; dragging right → shrinks
    const delta = this._startX - e.clientX
    const newWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, this._startWidth + delta))

    const main = this.closest('.editor-main') as HTMLElement | null
    if (main) {
      main.style.setProperty('--ep-preview-width', `${newWidth}px`)
    }
  }

  private _onPointerUp = (e: PointerEvent) => {
    if (!this._dragging) return
    this._dragging = false

    this.releasePointerCapture(e.pointerId)

    document.body.style.cursor = ''
    document.body.style.userSelect = ''

    // Persist final width
    const main = this.closest('.editor-main') as HTMLElement | null
    if (main) {
      const finalWidth = getComputedStyle(main).getPropertyValue('--ep-preview-width')
      try {
        localStorage.setItem(STORAGE_KEY, parseInt(finalWidth, 10).toString())
      } catch {
        // localStorage may be unavailable
      }
    }
  }

  override connectedCallback(): void {
    super.connectedCallback()
    this.addEventListener('pointerdown', this._onPointerDown)
    this.addEventListener('pointermove', this._onPointerMove)
    this.addEventListener('pointerup', this._onPointerUp)
    this.addEventListener('pointercancel', this._onPointerUp)
  }

  override disconnectedCallback(): void {
    this.removeEventListener('pointerdown', this._onPointerDown)
    this.removeEventListener('pointermove', this._onPointerMove)
    this.removeEventListener('pointerup', this._onPointerUp)
    this.removeEventListener('pointercancel', this._onPointerUp)
    super.disconnectedCallback()
  }

  override render() {
    return html`<div class="resize-handle"><div class="resize-handle-grip"></div></div>`
  }
}

export { STORAGE_KEY, MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH }

declare global {
  interface HTMLElementTagNameMap {
    'epistola-resize-handle': EpistolaResizeHandle
  }
}
