import { LitElement, html } from 'lit'
import { customElement } from 'lit/decorators.js'

const STORAGE_KEY = 'ep:preview-width'
const MIN_WIDTH = 200
const MAX_WIDTH = 800
const DEFAULT_WIDTH = 400
const KEYBOARD_STEP = 24

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

  private _getMain(): HTMLElement | null {
    return this.closest('.editor-main') as HTMLElement | null
  }

  private _readCurrentWidth(main: HTMLElement): number {
    const value = parseInt(getComputedStyle(main).getPropertyValue('--ep-preview-width') || String(DEFAULT_WIDTH), 10)
    if (isNaN(value)) return DEFAULT_WIDTH
    return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, value))
  }

  private _persistWidth(width: number): void {
    try {
      localStorage.setItem(STORAGE_KEY, String(width))
    } catch {
      // localStorage may be unavailable
    }
  }

  private _setWidth(main: HTMLElement, width: number): number {
    const clamped = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width))
    main.style.setProperty('--ep-preview-width', `${clamped}px`)
    this._persistWidth(clamped)
    return clamped
  }

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

    const main = this._getMain()
    if (!main) return

    this._startWidth = this._readCurrentWidth(main)

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

    const main = this._getMain()
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
    const main = this._getMain()
    if (main) {
      this._persistWidth(this._readCurrentWidth(main))
    }
  }

  private _onKeydown = (e: KeyboardEvent) => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return

    const main = this._getMain()
    if (!main) return

    e.preventDefault()
    const currentWidth = this._readCurrentWidth(main)

    if (e.key === 'ArrowLeft') {
      this._setWidth(main, currentWidth + KEYBOARD_STEP)
      return
    }

    if (currentWidth <= MIN_WIDTH) {
      this.dispatchEvent(new CustomEvent('request-close-preview', { bubbles: true, composed: true }))
      return
    }

    this._setWidth(main, currentWidth - KEYBOARD_STEP)
  }

  override connectedCallback(): void {
    super.connectedCallback()
    if (!this.hasAttribute('tabindex')) this.tabIndex = 0
    this.setAttribute('role', 'separator')
    this.setAttribute('aria-label', 'Resize preview panel')
    this.setAttribute('aria-orientation', 'vertical')
    this.addEventListener('pointerdown', this._onPointerDown)
    this.addEventListener('pointermove', this._onPointerMove)
    this.addEventListener('pointerup', this._onPointerUp)
    this.addEventListener('pointercancel', this._onPointerUp)
    this.addEventListener('keydown', this._onKeydown)
  }

  override disconnectedCallback(): void {
    this.removeEventListener('pointerdown', this._onPointerDown)
    this.removeEventListener('pointermove', this._onPointerMove)
    this.removeEventListener('pointerup', this._onPointerUp)
    this.removeEventListener('pointercancel', this._onPointerUp)
    this.removeEventListener('keydown', this._onKeydown)
    super.disconnectedCallback()
  }

  override render() {
    return html`<div class="resize-handle"><div class="resize-handle-grip"></div></div>`
  }
}

export { STORAGE_KEY, MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH, KEYBOARD_STEP }

declare global {
  interface HTMLElementTagNameMap {
    'epistola-resize-handle': EpistolaResizeHandle
  }
}
