import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { EditorEngine } from '../engine/EditorEngine.js';
import { icon } from './icons.js';

const POPOVER_SELECTOR = '.template-json-viewer-popover';
const COPY_FEEDBACK_MS = 1200;

/**
 * Power-user debug affordance: a read-only viewer for the **effective template
 * JSON** — `engine.doc`, the authored node/slot graph that is persisted and sent
 * to preview.
 *
 * Intentionally self-contained and chrome-free: it has no toolbar trigger and is
 * opened only via the `Leader + J` shortcut (registered in the core shortcut
 * registry so it surfaces in the shortcuts-help dialog). The host renders the
 * element unconditionally and opens it by calling `open()`; the element owns its
 * own visibility and dismissal (Escape / outside-click / close button), so the
 * host holds no viewer state.
 */
@customElement('epistola-template-json-viewer')
export class EpistolaTemplateJsonViewer extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) engine?: EditorEngine;

  @state() private _open = false;
  @state() private _copyState: 'idle' | 'copied' | 'error' = 'idle';

  private _unsubDoc?: () => void;
  private _copyTimeout: ReturnType<typeof setTimeout> | null = null;

  private _onWindowKeydown = (e: KeyboardEvent) => {
    if (!this._open) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      this.close();
    }
  };

  private _onWindowPointerDown = (e: PointerEvent) => {
    if (!this._open) return;
    const target = e.target;
    if (target instanceof Element && target.closest('.template-json-viewer')) return;
    this.close();
  };

  /** Open the viewer. The only entry point — there is no toolbar trigger. */
  open(): void {
    this._open = true;
    this._clearCopyFeedback();
    this._focusPopoverAfterRender();
  }

  close(): void {
    if (!this._open) return;
    this._open = false;
    this._clearCopyFeedback();
  }

  toggle(): void {
    if (this._open) {
      this.close();
    } else {
      this.open();
    }
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this._subscribeToEngine();
    window.addEventListener('keydown', this._onWindowKeydown);
    window.addEventListener('pointerdown', this._onWindowPointerDown, true);
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
    this._clearCopyFeedback();
    super.disconnectedCallback();
  }

  private _subscribeToEngine(): void {
    if (!this.engine) return;
    // Keep the JSON live while the viewer is open as the document is edited.
    this._unsubDoc = this.engine.events.on('doc:change', () => {
      if (this._open) this.requestUpdate();
    });
  }

  private _unsubscribe(): void {
    this._unsubDoc?.();
    this._unsubDoc = undefined;
  }

  private _handleCloseClick(e: Event): void {
    e.stopPropagation();
    this.close();
  }

  private _focusPopoverAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const popover = this.querySelector<HTMLElement>(POPOVER_SELECTOR);
        popover?.focus({ preventScroll: true });
      });
    });
  }

  private _clearCopyFeedback(): void {
    if (this._copyTimeout) {
      clearTimeout(this._copyTimeout);
      this._copyTimeout = null;
    }
    this._copyState = 'idle';
  }

  private _scheduleCopyFeedbackReset(): void {
    if (this._copyTimeout) {
      clearTimeout(this._copyTimeout);
    }
    this._copyTimeout = setTimeout(() => {
      this._copyState = 'idle';
      this._copyTimeout = null;
    }, COPY_FEEDBACK_MS);
  }

  private async _handleCopy(e: Event): Promise<void> {
    e.stopPropagation();
    const { json } = this._resolveJson();
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

  private _resolveJson(): { header: string; json: string | null } {
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

  override render() {
    if (!this._open) return nothing;

    const { header, json } = this._resolveJson();
    const canCopy = json !== null;
    const copyClass = [
      'template-json-viewer-copy',
      this._copyState === 'copied' ? 'is-success' : '',
      this._copyState === 'error' ? 'is-error' : '',
    ]
      .filter(Boolean)
      .join(' ');

    return html`
      <div class="template-json-viewer">
        <div
          class="template-json-viewer-popover"
          data-testid="template-json-popover"
          role="dialog"
          aria-label="Effective template JSON"
          tabindex="-1"
        >
          <div class="template-json-viewer-header">
            <div class="template-json-viewer-title">Template JSON</div>

            <div class="template-json-viewer-actions">
              <button
                class=${`ep-btn ep-btn-outline ep-btn-sm ${copyClass}`}
                data-testid="template-json-copy"
                type="button"
                ?disabled=${!canCopy}
                @click=${(e: Event) => void this._handleCopy(e)}
              >
                ${this._copyButtonLabel()}
              </button>

              <button
                class="ep-btn ep-btn-outline ep-btn-sm ep-btn-icon"
                data-testid="template-json-close"
                type="button"
                title="Close"
                aria-label="Close template JSON viewer"
                @click=${this._handleCloseClick}
              >
                ${icon('x', 14)}
              </button>
            </div>
          </div>

          <div class="template-json-viewer-meta">${header}</div>

          ${json !== null
            ? html`
                <textarea
                  class="template-json-viewer-json"
                  data-testid="template-json-textarea"
                  readonly
                  spellcheck="false"
                  aria-label="Effective template JSON"
                  .value=${json}
                ></textarea>
              `
            : html`<div class="template-json-viewer-empty">No template loaded.</div>`}
        </div>
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-template-json-viewer': EpistolaTemplateJsonViewer;
  }
}
