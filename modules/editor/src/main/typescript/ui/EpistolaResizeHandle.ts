import { LitElement, html } from 'lit'
import { customElement } from 'lit/decorators.js'
import {
  EDITOR_SHORTCUTS_CONFIG,
  type ResizeKeyboardShortcutConfig,
  type ResizeShortcutId,
} from '../shortcuts-config.js'

const STORAGE_KEY = 'ep:preview-width'

const { minWidth: MIN_WIDTH, maxWidth: MAX_WIDTH, defaultWidth: DEFAULT_WIDTH, keyboardStep: KEYBOARD_RESIZE_STEP } =
  EDITOR_SHORTCUTS_CONFIG.resize.dimensions

const RESIZE_SHORTCUTS_BY_ID = new Map(
  EDITOR_SHORTCUTS_CONFIG.resize.keyboard.map((shortcut) => [shortcut.id, shortcut] as const),
)

function getResizeShortcut(shortcutId: ResizeShortcutId): ResizeKeyboardShortcutConfig {
  const shortcut = RESIZE_SHORTCUTS_BY_ID.get(shortcutId)
  if (!shortcut) {
    throw new Error(`Missing resize shortcut config for "${shortcutId}"`)
  }
  return shortcut
}

const GROW_PREVIEW_WIDTH_SHORTCUT = getResizeShortcut('grow-preview-width')
const SHRINK_PREVIEW_WIDTH_SHORTCUT = getResizeShortcut('shrink-preview-width')
const CLOSE_PREVIEW_AT_MIN_WIDTH_SHORTCUT = getResizeShortcut('close-preview-when-min-width')

interface ResizeKeyResult {
  nextWidth: number
  closePreview: boolean
}

function clampWidth(width: number): number {
  return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width))
}

export function getResizeResultForKey(
  key: string,
  currentWidth: number,
  step = KEYBOARD_RESIZE_STEP,
): ResizeKeyResult | null {
  if (key === CLOSE_PREVIEW_AT_MIN_WIDTH_SHORTCUT.key && currentWidth <= MIN_WIDTH) {
    return { nextWidth: MIN_WIDTH, closePreview: true }
  }

  if (key === GROW_PREVIEW_WIDTH_SHORTCUT.key) {
    return { nextWidth: clampWidth(currentWidth + step), closePreview: false }
  }

  if (key === SHRINK_PREVIEW_WIDTH_SHORTCUT.key) {
    return { nextWidth: clampWidth(currentWidth - step), closePreview: false }
  }

  return null
}

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

  private _editorMain(): HTMLElement | null {
    return this.closest('.editor-main') as HTMLElement | null
  }

  private _readPreviewWidth(main: HTMLElement): number {
    const currentWidth = parseInt(getComputedStyle(main).getPropertyValue('--ep-preview-width'), 10)
    if (isNaN(currentWidth)) return DEFAULT_WIDTH
    return clampWidth(currentWidth)
  }

  private _persistWidth(width: number): void {
    try {
      localStorage.setItem(STORAGE_KEY, String(width))
    } catch {
      // localStorage may be unavailable
    }
  }

  private _syncAriaValue(width: number): void {
    this.setAttribute('aria-valuenow', String(width))
  }

  private _setPreviewWidth(main: HTMLElement, width: number, persist = false): number {
    const clampedWidth = clampWidth(width)
    main.style.setProperty('--ep-preview-width', `${clampedWidth}px`)
    this._syncAriaValue(clampedWidth)
    if (persist) {
      this._persistWidth(clampedWidth)
    }
    return clampedWidth
  }

  /** Read persisted width, or fall back to default. */
  static getPersistedWidth(): number {
    try {
      const stored = localStorage.getItem(STORAGE_KEY)
      if (stored) {
        const parsed = parseInt(stored, 10)
        if (!isNaN(parsed)) return clampWidth(parsed)
      }
    } catch {
      // localStorage may be unavailable
    }
    return DEFAULT_WIDTH
  }

  private _onPointerDown = (e: PointerEvent) => {
    const main = this._editorMain()
    if (!main) return

    this._dragging = true
    this._startX = e.clientX

    this._startWidth = this._readPreviewWidth(main)

    // Capture pointer for reliable tracking even outside the element
    this.setPointerCapture(e.pointerId)

    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
  }

  private _onPointerMove = (e: PointerEvent) => {
    if (!this._dragging) return

    // Dragging left (negative delta) → panel grows; dragging right → shrinks
    const delta = this._startX - e.clientX
    const newWidth = this._startWidth + delta

    const main = this._editorMain()
    if (main) {
      this._setPreviewWidth(main, newWidth)
    }
  }

  private _onPointerUp = (e: PointerEvent) => {
    if (!this._dragging) return
    this._dragging = false

    if (this.hasPointerCapture(e.pointerId)) {
      this.releasePointerCapture(e.pointerId)
    }

    document.body.style.cursor = ''
    document.body.style.userSelect = ''

    // Persist final width
    const main = this._editorMain()
    if (main) {
      this._persistWidth(this._readPreviewWidth(main))
    }
  }

  private _onKeydown = (e: KeyboardEvent) => {
    const main = this._editorMain()
    if (!main) return

    const currentWidth = this._readPreviewWidth(main)
    const result = getResizeResultForKey(e.key, currentWidth)
    if (!result) return

    e.preventDefault()

    if (result.closePreview) {
      this.dispatchEvent(new CustomEvent('toggle-preview', { bubbles: true, composed: true }))
      return
    }

    this._setPreviewWidth(main, result.nextWidth, true)
  }

  override connectedCallback(): void {
    super.connectedCallback()
    if (!this.hasAttribute('tabindex')) {
      this.tabIndex = 0
    }
    if (!this.hasAttribute('role')) {
      this.setAttribute('role', 'separator')
    }
    this.setAttribute('aria-orientation', 'vertical')
    this.setAttribute('aria-valuemin', String(MIN_WIDTH))
    this.setAttribute('aria-valuemax', String(MAX_WIDTH))
    if (!this.hasAttribute('aria-label')) {
      this.setAttribute('aria-label', 'Resize preview panel')
    }
    this._syncAriaValue(EpistolaResizeHandle.getPersistedWidth())

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

export { STORAGE_KEY, MIN_WIDTH, MAX_WIDTH, DEFAULT_WIDTH, KEYBOARD_RESIZE_STEP }

declare global {
  interface HTMLElementTagNameMap {
    'epistola-resize-handle': EpistolaResizeHandle
  }
}
