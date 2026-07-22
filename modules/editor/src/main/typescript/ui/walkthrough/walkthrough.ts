/**
 * Guided editor walkthrough (driver.js) — chapter runner.
 *
 * The walkthrough is a registry of small chapters (see {@link ./registry}). This
 * module drives one chapter at a time and chains to the next on completion.
 *
 * This whole module is behind a gated dynamic import in {@link EpistolaEditor}:
 * it (and driver.js) only download when the `editorWalkthrough` feature flag is
 * on. driver.js's value is itself `await import`ed here so it stays a separate
 * lazy chunk; its types are erased type-only imports.
 *
 * driver.css is imported with Vite's `?inline` suffix and injected as a single
 * <style> rather than emitted as a stylesheet — keeps the CSS inside this lazy
 * chunk and avoids lib-mode async-CSS quirks. `style-src` permits inline styles.
 */
import driverCss from 'driver.js/dist/driver.css?inline';
import type { Driver, DriveStep } from 'driver.js';
import { firstIncompleteTour, nextTour, tourById, TOURS, type Tour } from './registry.js';
import { hasSeenIntro, isChapterComplete, markChapterComplete, markIntroSeen } from './progress.js';
import { getActiveDriver, setActiveDriver } from './session.js';
import { injectStyleOnce } from './styles.js';

const GUIDE_TRIGGER = '[data-tour="guide-trigger"]';

const STYLE_ID = 'ep-driver-css';

// driver.js ships an unbranded popover (its own font, greys, and pill buttons).
// Re-skin it with the design-system tokens so the tour matches the editor. Appended
// after driver's own CSS so these rules win at equal specificity. Fallbacks are kept
// because this is injected raw into <head>. The arrow is left alone — driver draws it
// white, which already matches the white card. No dark theme, so the card stays light.
const POPOVER_OVERRIDES = `
.driver-popover {
  max-width: 450px;
  padding: var(--ep-space-4, 1rem);
  background: var(--ep-white, #fff);
  color: var(--ep-stone-800, #46302b);
  border: 1px solid var(--ep-stone-200, #e9dace);
  border-radius: var(--ep-radius-lg, 0.75rem);
  box-shadow: var(--ep-shadow-xl, 0 10px 15px -3px rgb(0 0 0 / 0.1), 0 4px 6px -4px rgb(0 0 0 / 0.1));
  font-family: var(--ep-font-sans, inherit);
}
.driver-popover-title { font-size: var(--ep-text-lg, 1.125rem); font-weight: 600; color: var(--ep-stone-900, #34221f); }
.driver-popover-description { font-size: var(--ep-text-sm, 0.875rem); line-height: 1.5; color: var(--ep-stone-700, #573f38); }
.driver-popover-progress-text { font-size: var(--ep-text-xs, 0.75rem); color: var(--ep-stone-500, #8c7163); }
.driver-popover-footer-btn {
  border-radius: var(--ep-radius-md, 0.5rem);
  padding: var(--ep-space-1-5, 0.375rem) var(--ep-space-3, 0.75rem);
  font-size: var(--ep-text-sm, 0.875rem); font-weight: 500; font-family: var(--ep-font-sans, inherit);
  transition: background-color 0.15s ease, border-color 0.15s ease;
}
.driver-popover-prev-btn { background: var(--ep-white, #fff); color: var(--ep-stone-800, #46302b); border: 1px solid var(--ep-stone-300, #d8c2b2); }
.driver-popover-prev-btn:hover { background: var(--ep-stone-100, #f6ede6); }
.driver-popover-next-btn { background: var(--ep-primary, #b6684e); color: var(--ep-primary-foreground, #fff); border: 1px solid transparent; }
.driver-popover-next-btn:hover { background: var(--ep-primary-strong, #9d5741); }
.driver-popover-close-btn { color: var(--ep-stone-400, #b9a595); }
.driver-popover-close-btn:hover { color: var(--ep-stone-800, #46302b); }
`;

function ensureDriverStyles(): void {
  injectStyleOnce(STYLE_ID, driverCss + POPOVER_OVERRIDES);
}

/**
 * Resolve a step's target within this editor host (not the whole document), so
 * spotlights land on the right editor when more than one is mounted. Returns
 * `null` when absent; driver's `skipMissingElement` then skips the step.
 */
function hostTarget(host: HTMLElement, selector: string): () => Element {
  // driver's resolver type is `() => Element`, but at runtime it tolerates a
  // missing one (skipMissingElement / centered fallback). The query legitimately
  // returns null for an absent target, so this narrowing is safe.
  // oxlint-disable-next-line no-unsafe-type-assertion
  return () => host.querySelector(selector) as Element;
}

/** Run a single chapter, chaining to the next chapter when the user finishes it. */
async function runTour(host: HTMLElement, tour: Tour): Promise<void> {
  const { driver } = await import('driver.js');
  ensureDriverStyles();

  const upcoming = nextTour(tour.id);

  const steps: DriveStep[] = tour.steps(host).map((step) => ({
    element: hostTarget(host, step.target),
    onHighlightStarted: step.before ? () => step.before?.(host) : undefined,
    popover: {
      title: step.title,
      description: step.body,
      side: step.side,
      align: 'start',
    },
    skipMissingElement: true,
  }));

  if (steps.length === 0) return;

  let d: Driver;
  d = driver({
    showProgress: true,
    progressText: '{{current}} of {{total}}',
    allowClose: true,
    // Config-level (not per-step) so it attaches to whichever step is *effectively*
    // last — a trailing target skipped via skipMissingElement must not orphan
    // completion. The Done button both records completion and, if there is a next
    // chapter, chains straight into it (the "level up" affordance). The X just
    // dismisses without marking complete.
    doneBtnText: upcoming ? `Next: ${upcoming.title} →` : 'Done',
    onDoneClick: () => {
      markChapterComplete(tour.id, tour.version);
      d.destroy();
      if (upcoming) void runTour(host, upcoming);
    },
    onDestroyed: () => {
      if (getActiveDriver() === d) setActiveDriver(null);
    },
    steps,
  });
  setActiveDriver(d);
  d.drive();
}

/** Start a specific chapter by id (used by the Guide launcher). No-op if unknown. */
export async function startTour(host: HTMLElement, tourId: string): Promise<void> {
  const tour = tourById(tourId);
  if (tour) await runTour(host, tour);
}

/** Start at the first unfinished chapter, or replay from the top when all are done. */
export async function startWalkthrough(host: HTMLElement): Promise<void> {
  const tour = firstIncompleteTour(isChapterComplete) ?? TOURS[0];
  if (tour) await runTour(host, tour);
}

/**
 * First-run awareness nudge: a single driver.js spotlight on the Guide button,
 * offering to start the tour or dismiss ("maybe later"). Not an auto-started
 * tour — just a one-step coach-mark, shown once. No-op if already seen or if the
 * Guide button isn't present.
 */
export async function startIntro(host: HTMLElement): Promise<void> {
  if (hasSeenIntro()) return;
  if (!host.querySelector(GUIDE_TRIGGER)) return;

  const { driver } = await import('driver.js');
  ensureDriverStyles();

  let d: Driver;
  d = driver({
    allowClose: true,
    // Fires whether the user starts the tour or dismisses — either way, don't nag again.
    onDestroyed: () => {
      if (getActiveDriver() === d) setActiveDriver(null);
      markIntroSeen();
    },
    steps: [
      {
        element: hostTarget(host, GUIDE_TRIGGER),
        popover: {
          title: 'Take the tour',
          description:
            'New here? A short guided walkthrough of the editor lives in this Guide button — ' +
            'start it now, or open it whenever you like.',
          side: 'bottom',
          align: 'end',
          showButtons: ['next', 'close'],
          doneBtnText: 'Start the tour',
          onDoneClick: () => {
            d.destroy();
            void startWalkthrough(host);
          },
        },
      },
    ],
  });
  setActiveDriver(d);
  d.drive();
}
