// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * <epistola-walkthrough-launcher> — the "Guide" button + chapter menu.
 *
 * Registered by the separately loaded walkthrough plugin only when the
 * `editorWalkthrough` flag is on. It statically imports the registry/progress
 * (both driver-free), and only pulls in the runner — and therefore driver.js —
 * when the first-run coach mark or a chapter starts. Its own popover styles are
 * injected once as a <style> so nothing lands in the core editor stylesheet.
 */
import { html, LitElement, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { icon } from '../../ui/icons.js';
import {
  firstRunnableTour,
  isTourAvailable,
  tourById,
  TOURS,
  type TourContext,
} from './registry.js';
import type { EpistolaEditor } from '../../ui/EpistolaEditor.js';
import { hasSeenIntro, isChapterComplete, subscribeProgress } from './progress.js';
import { stopTour } from './session.js';
import { injectStyleOnce } from './styles.js';
import launcherCss from './launcher.css?inline';
import { WALKTHROUGH_ANCHORS } from './targets.js';

interface ChapterView {
  id: string;
  title: string;
  summary: string;
  complete: boolean;
  current: boolean;
  /** Whether the chapter can run in this editor (D6). */
  available: boolean;
  /** Shown in place of the summary when the chapter is locked. */
  hint?: string;
}

const STYLE_ID = 'ep-wt-launcher-css';

// Selectors for the launcher's own light-DOM parts. Private to this component —
// deliberately not exported; nothing else should query the launcher's internals.
const TRIGGER_SELECTOR = '[data-testid="walkthrough-guide-trigger"]';
const MENU_ITEM_SELECTOR = '.ep-wt-item';

// Loaded as a raw string (`?inline`) and injected once - see launcher.css.

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
    // Escape closes and hands focus back to the trigger — without the refocus, a
    // reader focused on a menu item would be dropped to <body> when it unrenders.
    if (this._open && e.key === 'Escape') this._close();
  };

  override connectedCallback(): void {
    super.connectedCallback();
    injectStyleOnce(STYLE_ID, launcherCss);
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
    // Tear down this editor's live tour so a DOM/HTMX swap can't strand the driver
    // overlay (which leaves the whole page mouse-dead until Escape/reload). Scoped
    // to our host so unmounting one editor never kills a tour another mounted
    // editor is running; if the host can't be resolved anymore, stop any tour —
    // better a dismissed tour elsewhere than a stranded overlay here.
    stopTour(this._hostEl ?? undefined);
    super.disconnectedCallback();
  }

  /** First-run awareness nudge pointing at this Guide button (shown once). */
  override firstUpdated(): void {
    if (hasSeenIntro()) return;
    const ctx = this._ctx;
    if (!ctx) return;
    // Defer a frame so the button has laid out before driver.js measures it. Bail
    // if we've since disconnected — otherwise the intro would resolve the Guide
    // button against a detached tree and render as an orphan centered modal over
    // whatever swapped in (also marking the one-time intro "seen" out of context).
    requestAnimationFrame(() => {
      if (!this.isConnected) return;
      void import('./walkthrough.js')
        .then((m) => {
          if (this.isConnected) void m.startIntro(ctx);
        })
        .catch((e) => console.warn('Walkthrough intro failed to start:', e));
    });
  }

  /** The editor root this launcher belongs to (resolvable even without an engine). */
  private get _hostEl(): EpistolaEditor | null {
    return this.closest('epistola-editor');
  }

  /**
   * The tour context (editor root + engine), resolved once at this boundary —
   * everything below it (registry, tours, runner) takes a {@link TourContext}.
   * Null before the editor has initialized its engine (no tour can run then).
   */
  private get _ctx(): TourContext | null {
    const host = this._hostEl;
    const engine = host?.engine;
    return host && engine ? { host, engine } : null;
  }

  /** Chapters with completion, availability, and which one is "current". */
  private get _chapters(): ChapterView[] {
    const ctx = this._ctx;
    // Single source of truth for "current" — same rule startWalkthrough() drives.
    const currentId = ctx ? firstRunnableTour(isChapterComplete, ctx)?.id : undefined;
    return TOURS.map((t) => {
      const available = ctx ? isTourAvailable(t, ctx) : true;
      return {
        id: t.id,
        title: t.title,
        summary: t.summary,
        complete: isChapterComplete(t.id, t.version),
        current: available && t.id === currentId,
        available,
        hint: available ? undefined : t.unavailableHint,
      };
    });
  }

  private readonly _toggle = (): void => {
    // Tear down this editor's tracked tour first. If this button is clickable at all,
    // no live overlay is covering it — so either nothing is running, or the session is
    // stale (a chapter that self-destroyed without firing onDestroyed). Clearing it here
    // keeps the Guide button from dead-ending; a genuinely-active tour spotlighting this
    // very button (the intro) is simply dismissed, which is the right outcome.
    stopTour(this._hostEl ?? undefined);
    this._open = !this._open;
    // Menu-button pattern: opening moves focus into the menu (the current chapter,
    // or the first item). Items are tabindex="-1"; the arrows walk them.
    if (this._open) {
      void this.updateComplete.then(() => {
        const items = this._items();
        (items.find((i) => i.classList.contains('is-current')) ?? items[0])?.focus();
      });
    }
  };

  /** Close the menu; hand focus back to the trigger unless told otherwise. */
  private _close(refocusTrigger = true): void {
    if (!this._open) return;
    this._open = false;
    if (refocusTrigger) {
      this.querySelector<HTMLButtonElement>(TRIGGER_SELECTOR)?.focus();
    }
  }

  private _items(): HTMLButtonElement[] {
    return Array.from(this.querySelectorAll<HTMLButtonElement>(MENU_ITEM_SELECTOR));
  }

  /** Menu keyboard contract: arrows walk (and wrap), Home/End jump, Tab exits. */
  private readonly _onMenuKeydown = (e: KeyboardEvent): void => {
    const items = this._items();
    if (items.length === 0) return;
    const idx = items.indexOf(document.activeElement as HTMLButtonElement);
    const move = (next: number): void => {
      e.preventDefault();
      items[next]?.focus();
    };
    if (e.key === 'ArrowDown') move(idx < 0 ? 0 : (idx + 1) % items.length);
    else if (e.key === 'ArrowUp')
      move(idx < 0 ? items.length - 1 : (idx - 1 + items.length) % items.length);
    else if (e.key === 'Home') move(0);
    else if (e.key === 'End') move(items.length - 1);
    else if (e.key === 'Tab') {
      // Per the menu-button pattern Tab leaves the menu: close, restore the trigger
      // as the tab stop, and let the user tab onward from there.
      e.preventDefault();
      this._close();
    }
    // Escape is handled by the document-level listener (closes + refocuses).
  };

  private _run(id: string): void {
    const ctx = this._ctx;
    if (!ctx) return;
    // Locked chapters are focusable on purpose (aria-disabled, not disabled — so
    // keyboard and screen-reader users can reach them and hear the unlock hint);
    // activating one is a no-op that keeps the menu open.
    const tour = tourById(id);
    if (tour && !isTourAvailable(tour, ctx)) return;
    this._close();
    // Never stack drivers: tear down anything still tracked — on ANY editor, since
    // driver.js runs one overlay per page — before starting. (trackSession enforces
    // the same exclusivity, but stopping here clears the old overlay immediately on
    // click rather than after the runner chunk loads.)
    stopTour();
    // Pulls in the runner (and driver.js) only now, when a chapter actually runs.
    // The click handler doesn't await this, so swallow failures here: log, and
    // reopen the menu so the user can retry rather than being left with nothing.
    void import('./walkthrough.js')
      .then((m) => m.startTour(ctx, id))
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
          data-walkthrough-anchor=${WALKTHROUGH_ANCHORS.guideTrigger}
          data-testid="walkthrough-guide-trigger"
          title="Guided walkthrough"
          aria-label="Guided walkthrough"
          aria-haspopup="menu"
          aria-expanded=${String(this._open)}
          @click=${this._toggle}
        >
          ${icon('compass')}
        </button>
        ${this._open
          ? html`
              <div
                class="ep-wt-popover"
                role="menu"
                aria-label="Guided walkthrough"
                data-testid="walkthrough-launcher"
                @keydown=${this._onMenuKeydown}
              >
                <div class="ep-wt-title">Walkthrough</div>
                <ul class="ep-wt-list" role="none">
                  ${this._chapters.map(
                    (c) => html`
                      <li role="none">
                        <button
                          class="ep-wt-item ${c.current ? 'is-current' : ''}"
                          type="button"
                          role="menuitem"
                          tabindex="-1"
                          aria-disabled=${c.available ? nothing : 'true'}
                          data-testid=${`walkthrough-chapter-${c.id}`}
                          aria-current=${c.current ? 'step' : nothing}
                          aria-label=${`${c.title}: ${
                            !c.available
                              ? 'locked'
                              : c.complete
                                ? 'completed'
                                : c.current
                                  ? 'current chapter'
                                  : 'not started'
                          }. ${c.hint ?? c.summary}`}
                          @click=${() => this._run(c.id)}
                        >
                          <span
                            class="ep-wt-mark ${c.complete && c.available ? 'is-done' : ''}"
                            data-state=${!c.available
                              ? 'locked'
                              : c.complete
                                ? 'done'
                                : c.current
                                  ? 'current'
                                  : 'pending'}
                            aria-hidden="true"
                            >${!c.available
                              ? icon('lock')
                              : c.complete
                                ? icon('circle-check')
                                : c.current
                                  ? icon('circle-dot')
                                  : icon('circle')}</span
                          >
                          <span class="ep-wt-text">
                            <span class="ep-wt-name">${c.title}</span>
                            <span class="ep-wt-summary">${c.hint ?? c.summary}</span>
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
