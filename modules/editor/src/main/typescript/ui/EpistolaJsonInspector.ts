// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { EditorEngine } from '../engine/EditorEngine.js';
import { icon } from './icons.js';

const POPOVER_SELECTOR = '.json-inspector-popover';
const TRIGGER_SELECTOR = '.json-inspector-trigger';
const POPOVER_ID = 'epistola-json-inspector-popover';
const PINNED_STORAGE_KEY = 'ep:inspector:pinned';
const POSITION_STORAGE_KEY = 'ep:inspector:position';
const VIEWPORT_MARGIN = 12;
const DEFAULT_WIDTH = 640;
const DEFAULT_HEIGHT = 400;
const COPY_FEEDBACK_MS = 1200;

type InspectorView = 'data' | 'template';
type Position = { x: number; y: number };
type JsonObject = Record<string, unknown>;

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null;
}

/**
 * `<epistola-json-inspector>` — a toolbar JSON inspector.
 *
 * A trigger button + popover that shows, with a segmented toggle, either the
 * current **data example** payload or the **effective template model**
 * (`engine.doc` — the authored node/slot graph that is persisted and sent to
 * preview). The popover is read-only, copyable, pinnable and draggable, and the
 * template view stays live as the document is edited.
 *
 * Opened from the toolbar trigger or via shortcuts: the host calls `openData()`
 * (Leader + E) or `openTemplate()` (Leader + J). The data view/tab is only
 * available when the template has data examples; the template view is always
 * available. The component owns its own state and dismissal (Escape /
 * outside-click) so the toolbar carries none of it.
 */
@customElement('epistola-json-inspector')
export class EpistolaJsonInspector extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) engine?: EditorEngine;

  @state() private _open = false;
  @state() private _pinned = false;
  @state() private _position: Position | null = null;
  @state() private _copyState: 'idle' | 'copied' | 'error' = 'idle';
  // Which payload is shown. 'data' degrades to 'template' when the template has
  // no examples (see _effectiveView).
  @state() private _view: InspectorView = 'data';
  @state() private _currentExampleIndex = 0;

  private _unsubExample?: () => void;
  private _unsubDoc?: () => void;
  private _copyTimeout: ReturnType<typeof setTimeout> | null = null;
  private _dragging = false;
  private _dragOffsetX = 0;
  private _dragOffsetY = 0;

  private _onWindowKeydown = (e: KeyboardEvent) => {
    if (!this._open) return;
    if (e.key === 'Escape' && !this._pinned) {
      e.preventDefault();
      this._close({ restoreFocus: true });
    }
  };

  private _onWindowPointerDown = (e: PointerEvent) => {
    if (!this._open || this._pinned) return;
    const target = e.target;
    if (target instanceof Element && target.closest('.json-inspector')) return;
    this._close();
  };

  private _onWindowResize = () => {
    if (!this._open || !this._pinned) return;
    this._normalizePositionAfterRender();
  };

  private _onDragMove = (e: PointerEvent) => {
    if (!this._dragging || !this._pinned) return;
    const size = this._getSize();
    this._position = this._clampPosition(
      { x: e.clientX - this._dragOffsetX, y: e.clientY - this._dragOffsetY },
      size,
    );
  };

  private _onDragEnd = () => {
    this._stopDrag();
    this._persistState();
  };

  override connectedCallback(): void {
    super.connectedCallback();
    this._subscribeToEngine();
    this._restoreState();
    window.addEventListener('keydown', this._onWindowKeydown);
    window.addEventListener('pointerdown', this._onWindowPointerDown, true);
    window.addEventListener('resize', this._onWindowResize);
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('engine')) {
      this._unsubscribe();
      this._subscribeToEngine();
    }
  }

  override disconnectedCallback(): void {
    this._unsubscribe();
    window.removeEventListener('keydown', this._onWindowKeydown);
    window.removeEventListener('pointerdown', this._onWindowPointerDown, true);
    window.removeEventListener('resize', this._onWindowResize);
    this._stopDrag();
    this._clearCopyFeedback();
    super.disconnectedCallback();
  }

  private _subscribeToEngine(): void {
    if (!this.engine) return;
    this._currentExampleIndex = this.engine.currentExampleIndex;
    this._unsubExample = this.engine.events.on('example:change', ({ index }) => {
      this._currentExampleIndex = index;
      this._clearCopyFeedback();
    });
    // Keep the template view live as the document changes while open.
    this._unsubDoc = this.engine.events.on('doc:change', () => {
      if (this._open && this._effectiveView() === 'template') this.requestUpdate();
    });
  }

  private _unsubscribe(): void {
    this._unsubExample?.();
    this._unsubDoc?.();
    this._unsubExample = undefined;
    this._unsubDoc = undefined;
  }

  // ---------------------------------------------------------------------------
  // Public API (called by the toolbar / shortcut runtime)
  // ---------------------------------------------------------------------------

  /** Open the inspector on the data-example view (Leader + E). */
  openData(): void {
    this._view = 'data';
    this._openPopover();
  }

  /** Open the inspector on the template-model view (Leader + J). */
  openTemplate(): void {
    this._view = 'template';
    this._openPopover();
  }

  // ---------------------------------------------------------------------------
  // View resolution
  // ---------------------------------------------------------------------------

  private _hasExamples(): boolean {
    const examples = this.engine?.dataExamples;
    return !!examples && examples.length > 0;
  }

  private _effectiveView(): InspectorView {
    return this._view === 'data' && this._hasExamples() ? 'data' : 'template';
  }

  private _setView(view: InspectorView, e: Event): void {
    e.stopPropagation();
    if (view === 'data' && !this._hasExamples()) return;
    if (this._view === view) return;
    this._view = view;
    this._clearCopyFeedback();
  }

  private _resolveContent(): { header: string; json: string | null } {
    return this._effectiveView() === 'template'
      ? this._resolveTemplateJson()
      : this._resolveExampleJson();
  }

  private _resolveTemplateJson(): { header: string; json: string | null } {
    const doc = this.engine?.doc;
    if (!doc) {
      return { header: 'No template loaded', json: null };
    }
    const header = `Effective template document (modelVersion ${doc.modelVersion})`;
    try {
      return { header, json: JSON.stringify(doc, null, 2) };
    } catch {
      return { header, json: String(doc) };
    }
  }

  private _currentExampleRaw(): JsonObject | undefined {
    const examples = this.engine?.dataExamples;
    if (!examples || examples.length === 0) return undefined;
    const example = examples[this._currentExampleIndex];
    return isJsonObject(example) ? example : undefined;
  }

  private _currentExampleName(example: JsonObject | undefined): string {
    if (!example) return `Example ${this._currentExampleIndex + 1}`;
    if (typeof example.name === 'string' && example.name.trim().length > 0) return example.name;
    if (typeof example.label === 'string' && example.label.trim().length > 0) return example.label;
    return `Example ${this._currentExampleIndex + 1}`;
  }

  private _resolveExampleJson(): { header: string; json: string | null } {
    const examples = this.engine?.dataExamples;
    if (!examples || examples.length === 0) {
      return { header: 'No examples available', json: null };
    }
    const name = this._currentExampleName(this._currentExampleRaw());
    const position = `${this._currentExampleIndex + 1}/${examples.length}`;
    const payload = this.engine?.getExampleData();
    if (payload === undefined) {
      return { header: `${name} (${position})`, json: null };
    }
    try {
      return { header: `${name} (${position})`, json: JSON.stringify(payload, null, 2) };
    } catch {
      return { header: `${name} (${position})`, json: String(payload) };
    }
  }

  // ---------------------------------------------------------------------------
  // Open / close / copy
  // ---------------------------------------------------------------------------

  private _openPopover(): void {
    if (this._pinned && !this._position) {
      this._position = this._deriveDefaultPosition();
    }
    this._open = true;
    this._clearCopyFeedback();
    if (this._pinned) this._normalizePositionAfterRender();
    this._focusPopoverAfterRender();
  }

  private _close(options: { restoreFocus?: boolean; force?: boolean } = {}): void {
    if (!this._open) return;
    if (this._pinned && !options.force) return;
    this._stopDrag();
    this._clearCopyFeedback();
    this._open = false;
    if (options.restoreFocus) this._focusTriggerAfterRender();
  }

  private _toggle(e: Event): void {
    e.stopPropagation();
    if (this._open) {
      if (this._pinned) {
        this._focusPopoverAfterRender();
        return;
      }
      this._close();
      return;
    }
    this._openPopover();
  }

  private _togglePinned(e: Event): void {
    e.stopPropagation();
    const popover = this.querySelector<HTMLElement>(POPOVER_SELECTOR);
    const currentRect = popover?.getBoundingClientRect();

    this._pinned = !this._pinned;
    if (this._pinned) {
      const size = this._getSize();
      this._position = currentRect
        ? this._clampPosition({ x: currentRect.left, y: currentRect.top }, size)
        : this._deriveDefaultPosition();
      this._open = true;
      this._normalizePositionAfterRender();
      this._focusPopoverAfterRender();
    } else {
      this._stopDrag();
    }
    this._persistState();
  }

  private async _copy(e: Event): Promise<void> {
    e.stopPropagation();
    const { json } = this._resolveContent();
    if (json === null) return;
    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard API unavailable');
      }
      await navigator.clipboard.writeText(json);
      this._copyState = 'copied';
    } catch {
      this._copyState = 'error';
    }
    this._scheduleCopyFeedbackReset();
  }

  private _copyButtonLabel(): string {
    if (this._copyState === 'copied') return 'Copied';
    if (this._copyState === 'error') return 'Copy failed';
    return 'Copy JSON';
  }

  private _clearCopyFeedback(): void {
    if (this._copyTimeout) {
      clearTimeout(this._copyTimeout);
      this._copyTimeout = null;
    }
    this._copyState = 'idle';
  }

  private _scheduleCopyFeedbackReset(): void {
    if (this._copyTimeout) clearTimeout(this._copyTimeout);
    this._copyTimeout = setTimeout(() => {
      this._copyState = 'idle';
      this._copyTimeout = null;
    }, COPY_FEEDBACK_MS);
  }

  // ---------------------------------------------------------------------------
  // Focus / position / drag (pinned floating mode)
  // ---------------------------------------------------------------------------

  private _focusPopoverAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        this.querySelector<HTMLElement>(POPOVER_SELECTOR)?.focus({ preventScroll: true });
      });
    });
  }

  private _focusTriggerAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        this.querySelector<HTMLElement>(TRIGGER_SELECTOR)?.focus({ preventScroll: true });
      });
    });
  }

  private _restoreState(): void {
    try {
      const pinned = localStorage.getItem(PINNED_STORAGE_KEY) === 'true';
      const position = this._readStoredPosition();
      this._pinned = pinned;
      this._position = position;
      if (pinned) {
        this._open = true;
        if (!position) this._position = this._deriveDefaultPosition();
        this._normalizePositionAfterRender();
      }
    } catch {
      // localStorage may be unavailable
    }
  }

  private _readStoredPosition(): Position | null {
    const raw = localStorage.getItem(POSITION_STORAGE_KEY);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as { x?: unknown; y?: unknown };
      if (typeof parsed.x !== 'number' || typeof parsed.y !== 'number') return null;
      return this._clampPosition(
        { x: parsed.x, y: parsed.y },
        { width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT },
      );
    } catch {
      return null;
    }
  }

  private _persistState(): void {
    try {
      localStorage.setItem(PINNED_STORAGE_KEY, String(this._pinned));
      if (this._position) {
        localStorage.setItem(POSITION_STORAGE_KEY, JSON.stringify(this._position));
      } else {
        localStorage.removeItem(POSITION_STORAGE_KEY);
      }
    } catch {
      // localStorage may be unavailable
    }
  }

  private _getSize(): { width: number; height: number } {
    const popover = this.querySelector<HTMLElement>(POPOVER_SELECTOR);
    if (popover) {
      const rect = popover.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        return { width: rect.width, height: rect.height };
      }
    }
    return { width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT };
  }

  private _clampPosition(position: Position, size: { width: number; height: number }): Position {
    const maxX = Math.max(VIEWPORT_MARGIN, window.innerWidth - size.width - VIEWPORT_MARGIN);
    const maxY = Math.max(VIEWPORT_MARGIN, window.innerHeight - size.height - VIEWPORT_MARGIN);
    return {
      x: Math.round(Math.min(Math.max(position.x, VIEWPORT_MARGIN), maxX)),
      y: Math.round(Math.min(Math.max(position.y, VIEWPORT_MARGIN), maxY)),
    };
  }

  private _deriveDefaultPosition(): Position {
    const size = this._getSize();
    const trigger = this.querySelector<HTMLElement>(TRIGGER_SELECTOR);
    if (trigger) {
      const rect = trigger.getBoundingClientRect();
      return this._clampPosition(
        { x: Math.round(rect.right - size.width), y: Math.round(rect.bottom + 8) },
        size,
      );
    }
    return this._clampPosition({ x: window.innerWidth - size.width - 24, y: 72 }, size);
  }

  private _normalizePositionAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        if (!this._open || !this._pinned) return;
        const size = this._getSize();
        const next = this._clampPosition(this._position ?? this._deriveDefaultPosition(), size);
        const current = this._position;
        if (!current || current.x !== next.x || current.y !== next.y) {
          this._position = next;
          this._persistState();
        }
      });
    });
  }

  private _startDrag(e: PointerEvent): void {
    if (!this._pinned || e.button !== 0) return;
    const popover = this.querySelector<HTMLElement>(POPOVER_SELECTOR);
    if (!popover) return;
    const rect = popover.getBoundingClientRect();
    this._dragging = true;
    this._dragOffsetX = e.clientX - rect.left;
    this._dragOffsetY = e.clientY - rect.top;
    document.body.style.userSelect = 'none';
    window.addEventListener('pointermove', this._onDragMove);
    window.addEventListener('pointerup', this._onDragEnd);
    window.addEventListener('pointercancel', this._onDragEnd);
    e.preventDefault();
    e.stopPropagation();
  }

  private _stopDrag(): void {
    if (!this._dragging) return;
    this._dragging = false;
    this._dragOffsetX = 0;
    this._dragOffsetY = 0;
    document.body.style.userSelect = '';
    window.removeEventListener('pointermove', this._onDragMove);
    window.removeEventListener('pointerup', this._onDragEnd);
    window.removeEventListener('pointercancel', this._onDragEnd);
  }

  // ---------------------------------------------------------------------------
  // Render
  // ---------------------------------------------------------------------------

  override render() {
    const view = this._effectiveView();
    const hasExamples = this._hasExamples();
    const content = this._resolveContent();
    const popoverClass = ['json-inspector-popover', this._pinned ? 'is-pinned' : '']
      .filter(Boolean)
      .join(' ');
    const popoverStyle =
      this._pinned && this._position
        ? `left: ${this._position.x}px; top: ${this._position.y}px;`
        : '';
    const pinLabel = this._pinned ? 'Unpin inspector' : 'Pin inspector';
    const canCopy = content.json !== null;
    const copyClass = [
      'json-inspector-copy',
      this._copyState === 'copied' ? 'is-success' : '',
      this._copyState === 'error' ? 'is-error' : '',
    ]
      .filter(Boolean)
      .join(' ');
    const emptyText =
      view === 'template' ? 'No template loaded.' : 'No payload available for this example.';

    return html`
      <div class="json-inspector">
        <button
          class="ep-btn ep-btn-outline ep-btn-sm ep-btn-icon json-inspector-trigger"
          data-testid="inspector-trigger"
          type="button"
          title="Inspect JSON"
          aria-label="Inspect JSON"
          aria-haspopup="dialog"
          aria-controls=${POPOVER_ID}
          aria-expanded=${String(this._open)}
          @click=${this._toggle}
        >
          ${icon('braces')}
        </button>

        ${this._open
          ? html`
              <div
                id=${POPOVER_ID}
                class=${popoverClass}
                style=${popoverStyle}
                data-testid="inspector-popover"
                role="dialog"
                aria-label="JSON inspector"
                tabindex="-1"
              >
                <div class="json-inspector-header">
                  <div class="json-inspector-tabs" role="tablist" aria-label="Inspect">
                    <button
                      class="json-inspector-tab ${view === 'template' ? 'is-active' : ''}"
                      data-testid="inspector-tab-template"
                      role="tab"
                      type="button"
                      aria-selected=${String(view === 'template')}
                      @click=${(e: Event) => this._setView('template', e)}
                    >
                      Template
                    </button>
                    <button
                      class="json-inspector-tab ${view === 'data' ? 'is-active' : ''}"
                      data-testid="inspector-tab-data"
                      role="tab"
                      type="button"
                      ?disabled=${!hasExamples}
                      title=${hasExamples
                        ? 'Current data example'
                        : 'This template has no data examples'}
                      aria-selected=${String(view === 'data')}
                      @click=${(e: Event) => this._setView('data', e)}
                    >
                      Data
                    </button>
                  </div>

                  <div class="json-inspector-actions">
                    ${this._pinned
                      ? html`
                          <button
                            class="ep-btn ep-btn-outline ep-btn-sm json-inspector-drag-handle"
                            data-testid="inspector-drag-handle"
                            type="button"
                            title="Drag to move"
                            aria-label="Drag to move"
                            @pointerdown=${(e: PointerEvent) => this._startDrag(e)}
                          >
                            ${icon('grip-vertical', 14)} Drag
                          </button>
                        `
                      : nothing}

                    <button
                      class=${`ep-btn ep-btn-outline ep-btn-sm ${copyClass}`}
                      data-testid="inspector-copy"
                      type="button"
                      ?disabled=${!canCopy}
                      @click=${(e: Event) => void this._copy(e)}
                    >
                      ${this._copyButtonLabel()}
                    </button>

                    <button
                      class="ep-btn ep-btn-outline ep-btn-sm ep-btn-icon json-inspector-pin ${this
                        ._pinned
                        ? 'is-active'
                        : ''}"
                      data-testid="inspector-pin"
                      type="button"
                      title=${pinLabel}
                      aria-label=${pinLabel}
                      aria-pressed=${String(this._pinned)}
                      @click=${this._togglePinned}
                    >
                      ${icon('paperclip', 14)}
                    </button>
                  </div>
                </div>

                ${!this._pinned
                  ? html`
                      <div class="json-inspector-drag-hint">
                        ${icon('paperclip', 12)} Pin to keep this viewer open and movable
                      </div>
                    `
                  : nothing}

                <div class="json-inspector-meta">${content.header}</div>

                ${content.json !== null
                  ? html`
                      <textarea
                        class="json-inspector-json"
                        data-testid="inspector-json"
                        readonly
                        spellcheck="false"
                        aria-label=${view === 'template'
                          ? 'Effective template JSON'
                          : 'Current data example JSON'}
                        .value=${content.json}
                      ></textarea>
                    `
                  : html`<div class="json-inspector-empty">${emptyText}</div>`}
              </div>
            `
          : nothing}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-json-inspector': EpistolaJsonInspector;
  }
}
