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
import { icon } from '../icons.js';
import { firstIncompleteTour, TOURS } from './registry.js';
import { hasSeenIntro, isChapterComplete, subscribeProgress } from './progress.js';
import { isTourActive, stopActiveTour } from './session.js';
import { injectStyleOnce } from './styles.js';

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
  position: absolute; top: calc(100% + var(--ep-space-2)); right: 0; z-index: 50;
  width: 18rem; max-width: 80vw; padding: var(--ep-space-1);
  background: var(--ep-white); color: var(--ep-stone-800);
  border: 1px solid var(--ep-stone-200); border-radius: var(--ep-radius-md);
  box-shadow: var(--ep-shadow-lg);
  font-family: var(--ep-font-sans);
  animation: ep-wt-in 140ms ease-out;
}
@keyframes ep-wt-in { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; transform: none; } }
.ep-wt-title {
  font-size: var(--ep-text-xs); font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em;
  color: var(--ep-stone-500); padding: var(--ep-space-1-5) var(--ep-space-2) var(--ep-space-1);
}
.ep-wt-list { list-style: none; margin: 0; padding: 0; }
.ep-wt-item {
  display: flex; gap: var(--ep-space-2); align-items: flex-start; width: 100%;
  text-align: left; padding: var(--ep-space-2); border: 0; border-radius: var(--ep-radius-sm);
  background: transparent; color: inherit; cursor: pointer;
  transition: background-color var(--ep-transition-fast);
}
.ep-wt-item:hover { background: var(--ep-stone-100); }
.ep-wt-item:focus-visible { outline: none; box-shadow: var(--ep-ring); }
.ep-wt-item.is-current { background: var(--ep-terracotta-50); }
.ep-wt-mark {
  flex: 0 0 auto; display: inline-flex; align-items: center; justify-content: center;
  width: 1.25rem; height: 1.4rem; color: var(--ep-stone-400);
}
.ep-wt-mark.is-done { color: var(--ep-success); }
.ep-wt-item.is-current .ep-wt-mark { color: var(--ep-primary-strong); }
.ep-wt-text { display: flex; flex-direction: column; gap: var(--ep-space-0-5); }
.ep-wt-name { font-weight: 500; color: var(--ep-stone-800); }
.ep-wt-summary { font-size: var(--ep-text-xs); color: var(--ep-stone-600); line-height: 1.4; }
`;

@customElement('epistola-walkthrough-launcher')
export class WalkthroughLauncher extends LitElement {
  /** Light DOM so global editor styles and the injected popover CSS apply. */
  override createRenderRoot(): HTMLElement {
    return this;
  }

  @state() private _open = false;

  private _unsubscribeProgress?: () => void;

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
    injectStyleOnce(STYLE_ID, CSS);
    document.addEventListener('pointerdown', this._onDocPointerDown);
    document.addEventListener('keydown', this._onKeydown);
    // Refresh the ✓/▶ marks whenever completion changes, even while the menu is open.
    this._unsubscribeProgress = subscribeProgress(() => this.requestUpdate());
  }

  override disconnectedCallback(): void {
    document.removeEventListener('pointerdown', this._onDocPointerDown);
    document.removeEventListener('keydown', this._onKeydown);
    this._unsubscribeProgress?.();
    this._unsubscribeProgress = undefined;
    // Tear down any live tour so a DOM/HTMX swap can't strand the driver overlay
    // (which leaves the whole page mouse-dead until Escape/reload).
    stopActiveTour();
    super.disconnectedCallback();
  }

  /** First-run awareness nudge pointing at this Guide button (shown once). */
  override firstUpdated(): void {
    if (hasSeenIntro()) return;
    const host = this._host;
    if (!host) return;
    // Defer a frame so the button has laid out before driver.js measures it. Bail
    // if we've since disconnected — otherwise the intro would resolve the Guide
    // button against a detached tree and render as an orphan centered modal over
    // whatever swapped in (also marking the one-time intro "seen" out of context).
    requestAnimationFrame(() => {
      if (!this.isConnected) return;
      void import('./walkthrough.js')
        .then((m) => {
          if (this.isConnected) void m.startIntro(host);
        })
        .catch((e) => console.warn('Walkthrough intro failed to start:', e));
    });
  }

  /** The editor root, used to scope the tour's spotlights. */
  private get _host(): HTMLElement | null {
    return this.closest('epistola-editor');
  }

  /** Chapters with completion + which one is "current" (first not-yet-complete). */
  private get _chapters(): ChapterView[] {
    // Single source of truth for "current" — same rule startWalkthrough() drives.
    const currentId = firstIncompleteTour(isChapterComplete)?.id;
    return TOURS.map((t) => ({
      id: t.id,
      title: t.title,
      summary: t.summary,
      complete: isChapterComplete(t.id, t.version),
      current: t.id === currentId,
    }));
  }

  private readonly _toggle = (): void => {
    // Ignore while a tour/intro overlay is up: the menu would open as a sibling of
    // the spotlighted button, land under driver's pointer-events:none, and be dead.
    if (isTourActive()) return;
    this._open = !this._open;
  };

  private _run(id: string): void {
    this._open = false;
    const host = this._host;
    if (!host) return;
    // Pulls in the runner (and driver.js) only now, when a chapter actually runs.
    // The click handler doesn't await this, so swallow failures here: log, and
    // reopen the menu so the user can retry rather than being left with nothing.
    void import('./walkthrough.js')
      .then((m) => m.startTour(host, id))
      .catch((e) => {
        console.warn('Walkthrough tour failed to start:', e);
        this._open = true;
      });
  }

  override render(): unknown {
    return html`
      <div class="ep-wt">
        <button
          class="ep-btn ep-btn-outline ep-btn-sm ep-btn-icon"
          type="button"
          data-tour="guide-trigger"
          data-testid="walkthrough-guide-trigger"
          title="Guided walkthrough"
          aria-label="Guided walkthrough"
          aria-haspopup="dialog"
          aria-expanded=${String(this._open)}
          @click=${this._toggle}
        >
          ${icon('compass')}
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
                          aria-current=${c.current ? 'step' : nothing}
                          aria-label=${`${c.title}: ${
                            c.complete ? 'completed' : c.current ? 'current chapter' : 'not started'
                          }. ${c.summary}`}
                          @click=${() => this._run(c.id)}
                        >
                          <span
                            class="ep-wt-mark ${c.complete ? 'is-done' : ''}"
                            data-state=${c.complete ? 'done' : c.current ? 'current' : 'pending'}
                            aria-hidden="true"
                            >${c.complete
                              ? icon('circle-check')
                              : c.current
                                ? icon('circle-dot')
                                : icon('circle')}</span
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
