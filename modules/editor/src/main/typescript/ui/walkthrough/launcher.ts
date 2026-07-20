/**
 * <epistola-walkthrough-launcher> — the "Guide" button + chapter menu.
 *
 * Lazy-registered by the toolbar only when the `editorWalkthrough` flag is on
 * (so flag-off users never load it). It statically imports the registry/progress
 * (both driver-free), and only pulls in the runner — and therefore driver.js —
 * when the user actually launches a chapter. Its own popover styles are injected
 * once as a <style> so nothing lands in the always-loaded editor.css.
 */
import { html, LitElement, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { TOURS } from './registry.js';
import { isChapterComplete } from './progress.js';

interface ChapterView {
  id: string;
  title: string;
  summary: string;
  complete: boolean;
  current: boolean;
}

const STYLE_ID = 'ep-wt-launcher-css';

const CSS = `
.ep-wt { position: relative; display: inline-flex; }
.ep-wt-popover {
  position: absolute; top: calc(100% + var(--ep-space-2, 8px)); right: 0; z-index: 50;
  width: 18rem; max-width: 80vw; padding: var(--ep-space-2, 8px);
  background: var(--ep-surface, #fff); color: var(--ep-text, inherit);
  border: 1px solid var(--ep-border, rgba(0,0,0,0.15));
  border-radius: var(--ep-radius, 8px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.18);
}
.ep-wt-title { font-weight: 600; padding: var(--ep-space-1, 4px) var(--ep-space-2, 8px); }
.ep-wt-list { list-style: none; margin: 0; padding: 0; }
.ep-wt-item {
  display: flex; gap: var(--ep-space-2, 8px); align-items: flex-start; width: 100%;
  text-align: left; padding: var(--ep-space-2, 8px); border: 0; border-radius: var(--ep-radius-sm, 6px);
  background: transparent; color: inherit; cursor: pointer;
}
.ep-wt-item:hover, .ep-wt-item:focus-visible { background: var(--ep-surface-hover, rgba(0,0,0,0.06)); }
.ep-wt-item.is-current { background: var(--ep-accent-subtle, rgba(59,130,246,0.12)); }
.ep-wt-mark { flex: 0 0 auto; width: 1.25rem; text-align: center; opacity: 0.85; }
.ep-wt-text { display: flex; flex-direction: column; gap: 2px; }
.ep-wt-name { font-weight: 500; }
.ep-wt-summary { font-size: 0.85em; opacity: 0.7; }
`;

function ensureStyles(): void {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = CSS;
  document.head.appendChild(style);
}

@customElement('epistola-walkthrough-launcher')
export class WalkthroughLauncher extends LitElement {
  /** Light DOM so global editor styles and the injected popover CSS apply. */
  override createRenderRoot(): HTMLElement {
    return this;
  }

  @state() private _open = false;

  private readonly _onDocPointerDown = (e: Event): void => {
    if (!this._open) return;
    const target = e.target;
    if (target instanceof Node && this.contains(target)) return;
    this._open = false;
  };

  private readonly _onKeydown = (e: KeyboardEvent): void => {
    if (this._open && e.key === 'Escape') this._open = false;
  };

  override connectedCallback(): void {
    super.connectedCallback();
    ensureStyles();
    document.addEventListener('pointerdown', this._onDocPointerDown);
    document.addEventListener('keydown', this._onKeydown);
  }

  override disconnectedCallback(): void {
    document.removeEventListener('pointerdown', this._onDocPointerDown);
    document.removeEventListener('keydown', this._onKeydown);
    super.disconnectedCallback();
  }

  /** The editor root, used to scope the tour's spotlights. */
  private get _host(): HTMLElement | null {
    return this.closest('epistola-editor');
  }

  /** Chapters with completion + which one is "current" (first not-yet-complete). */
  private get _chapters(): ChapterView[] {
    let currentTaken = false;
    return TOURS.map((t) => {
      const complete = isChapterComplete(t.id, t.version);
      const current = !complete && !currentTaken;
      if (current) currentTaken = true;
      return { id: t.id, title: t.title, summary: t.summary, complete, current };
    });
  }

  private readonly _toggle = (): void => {
    this._open = !this._open;
  };

  private async _run(id: string): Promise<void> {
    this._open = false;
    const host = this._host;
    if (!host) return;
    // Pulls in the runner (and driver.js) only now, when a chapter actually runs.
    const { startTour } = await import('./walkthrough.js');
    await startTour(host, id);
  }

  override render(): unknown {
    return html`
      <div class="ep-wt">
        <button
          class="ep-btn ep-btn-outline ep-btn-sm"
          type="button"
          data-tour="guide-trigger"
          data-testid="walkthrough-guide-trigger"
          title="Guided walkthrough"
          aria-haspopup="dialog"
          aria-expanded=${String(this._open)}
          @click=${this._toggle}
        >
          Guide
        </button>
        ${this._open
          ? html`
              <div
                class="ep-wt-popover"
                role="dialog"
                aria-label="Guided walkthrough"
                data-testid="walkthrough-launcher"
              >
                <div class="ep-wt-title">Walkthrough</div>
                <ul class="ep-wt-list">
                  ${this._chapters.map(
                    (c) => html`
                      <li>
                        <button
                          class="ep-wt-item ${c.current ? 'is-current' : ''}"
                          type="button"
                          data-testid=${`walkthrough-chapter-${c.id}`}
                          @click=${() => this._run(c.id)}
                        >
                          <span class="ep-wt-mark" aria-hidden="true"
                            >${c.complete ? '✓' : c.current ? '▶' : '•'}</span
                          >
                          <span class="ep-wt-text">
                            <span class="ep-wt-name">${c.title}</span>
                            <span class="ep-wt-summary">${c.summary}</span>
                          </span>
                        </button>
                      </li>
                    `,
                  )}
                </ul>
              </div>
            `
          : nothing}
      </div>
    `;
  }
}
