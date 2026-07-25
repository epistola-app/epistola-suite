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
import {
  firstRunnableTour,
  isTourAvailable,
  nextAvailableTour,
  nextTour,
  tourById,
  TOURS,
  type Tour,
  type TourContext,
} from './registry.js';
import { hasSeenIntro, isChapterComplete, markChapterComplete, markIntroSeen } from './progress.js';
import { clearSession, trackSession } from './session.js';
import { injectStyleOnce } from './styles.js';
import { TOUR_HOOKS, tourHook } from './hooks.js';

const GUIDE_TRIGGER = tourHook(TOUR_HOOKS.guideTrigger);

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
.driver-popover-description strong { font-weight: 600; color: var(--ep-stone-900, #34221f); }
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
/* The rich-text bubble menu sits at --ep-z-dropdown (10), far below driver's
   overlay (10000), so it renders *behind* the backdrop when a text editor is
   spotlighted. While a tour is active, lift it above the backdrop (but below the
   popover) and keep it interactive so it reads as a real floating toolbar. */
.driver-active .pm-bubble-menu { z-index: 1000000; }
.driver-active .pm-bubble-menu, .driver-active .pm-bubble-menu * { pointer-events: auto; }
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
async function runTour(ctx: TourContext, tour: Tour): Promise<void> {
  const { host } = ctx;
  const { driver } = await import('driver.js');
  // The host can detach during that await (an hx-boost swap landing mid-click,
  // e.g. while chaining from onDoneClick). The launcher's disconnectedCallback
  // tears down the *tracked* driver, but a driver constructed after that point
  // would be untracked — a stranded overlay that leaves the page mouse-dead.
  if (!host.isConnected) return;
  ensureDriverStyles();

  // A chapter with an `onComplete` mutates the editor to make its successor runnable
  // (building drops a starter block so editing/styling unlock), so it advertises its raw
  // next chapter for the Done label — computed before the hook runs, availability would
  // still see the old state. Otherwise advertise the next chapter that can run here.
  // Actual chaining re-checks availability after the hook runs (see onDoneClick).
  const upcoming = tour.onComplete ? nextTour(tour.id) : nextAvailableTour(tour.id, ctx);

  let d: Driver;
  // Chapters are passive narration: the user reads each step and advances with Next (or
  // by clicking the backdrop). No auto-advance on user actions — a step's `before` may do
  // benign setup (switch a sidebar tab, open the preview, select a block so the spotlight
  // has something to point at), but the tour never waits on or listens to the user. The
  // spotlighted element is non-interactive so a stray click reads as "next", not an edit.
  const tourSteps = tour.steps(ctx);
  const steps: DriveStep[] = tourSteps.map((step, i) => ({
    element: hostTarget(host, step.target),
    disableActiveInteraction: true,
    onHighlightStarted: step.before ? () => step.before?.(ctx) : undefined,
    popover: {
      title: step.title,
      description: step.body,
      side: step.side,
      align: 'start',
      // Label from OUR step list, not driver's presence probe: driver treats a step
      // as "effectively last" (and shows the done label) when no *following* target
      // currently resolves — but it probes before a pivot step's `before` has run,
      // so a step whose successors it is about to create would wrongly read "Done".
      // Click routing self-corrects (driver re-probes at click time, after the
      // re-render); only the label needs pinning. The key must be OMITTED (not set
      // to undefined) on the last step: driver spreads this object over its computed
      // defaults, and a present-but-undefined nextBtnText would clobber the done
      // label ("Next: <chapter> →") back to plain "Next".
      ...(i < tourSteps.length - 1 ? { nextBtnText: 'Next' } : {}),
    },
    skipMissingElement: true,
  }));

  if (steps.length === 0) return;

  d = driver({
    showProgress: true,
    progressText: '{{current}} of {{total}}',
    allowClose: true,
    // A `before` that switches tabs / opens the preview / selects a block renders its
    // target a tick later. Wait for it (a MutationObserver — shows the instant it appears)
    // instead of skipMissingElement skipping it. Genuinely-absent targets skip after this.
    waitForElement: 400,
    // Click the backdrop to advance rather than close — reads like a gentle slideshow.
    // A custom hook rather than 'nextStep': on the (effectively) last step 'nextStep'
    // routes to onDoneClick, so a stray backdrop click — the universal "dismiss"
    // gesture — would mark the chapter complete, run onComplete (which may mutate the
    // document), and chain into the next chapter. Completion must stay behind the
    // explicitly labeled Done/Next button, so on the last step the backdrop is inert.
    overlayClickBehavior: () => {
      if (!d.isLastStep()) d.moveNext();
    },
    // Config-level (not per-step) so it attaches to whichever step is *effectively*
    // last — a trailing target skipped via skipMissingElement must not orphan
    // completion. The Done button both records completion and, if there is a next
    // chapter, chains straight into it (the "level up" affordance). The X just
    // dismisses without marking complete.
    doneBtnText: upcoming ? `Next: ${upcoming.title} →` : 'Done',
    // A pivot step's `before` (select a block, switch tabs) can remove an *earlier*
    // step's target, and driver checks a step's existence *before* running its
    // `before` — so plain movePrevious would silently skip those steps (or stall and
    // do nothing at all). Recreate the state the tour first arrived with instead:
    // the chapter baseline (`setup`), then the destination step's own `before` so
    // its target exists when driver looks for it. Both are idempotent by contract
    // (they re-run on every replay already); waitForElement bridges the async
    // re-render, same as it does at tour start.
    onPrevClick: () => {
      const idx = d.getActiveIndex();
      tour.setup?.(ctx);
      if (idx !== undefined) tourSteps[idx - 1]?.before?.(ctx);
      d.movePrevious();
    },
    onDoneClick: () => {
      markChapterComplete(tour.id, tour.version);
      // Run the completion hook (e.g. drop a starter block) before chaining, so the next
      // chapter's setup finds the state it needs.
      tour.onComplete?.(ctx);
      d.destroy();
      // Availability reads the engine model, so it is correct here even though the
      // canvas hasn't repainted. If onComplete failed to establish the advertised
      // chapter's preconditions (e.g. the insert dispatch was rejected), fall back
      // to the next chapter that can actually run — or end the chain gracefully,
      // rather than launching a chapter whose every step would be skipped.
      const next =
        upcoming && isTourAvailable(upcoming, ctx) ? upcoming : nextAvailableTour(tour.id, ctx);
      if (next) void runTour(ctx, next);
    },
    onDestroyed: () => {
      clearSession(d);
    },
    steps,
  });
  trackSession(host, d);
  // Establish initial state (select a block, open the document inspector, …) BEFORE
  // driving, so step 0's target exists when driver resolves it. waitForElement bridges
  // the async re-render this triggers.
  tour.setup?.(ctx);
  d.drive();
}

/** Start a specific chapter by id (used by the Guide launcher). No-op if unknown. */
export async function startTour(ctx: TourContext, tourId: string): Promise<void> {
  const tour = tourById(tourId);
  if (tour) await runTour(ctx, tour);
}

/** Start at the first unfinished, runnable chapter, or replay the first when all are done. */
export async function startWalkthrough(ctx: TourContext): Promise<void> {
  const tour = firstRunnableTour(isChapterComplete, ctx) ?? TOURS[0];
  if (tour) await runTour(ctx, tour);
}

/**
 * First-run awareness nudge: a single driver.js spotlight on the Guide button,
 * offering to start the tour or dismiss ("maybe later"). Not an auto-started
 * tour — just a one-step coach-mark, shown once. No-op if already seen or if the
 * Guide button isn't present.
 */
export async function startIntro(ctx: TourContext): Promise<void> {
  const { host } = ctx;
  if (hasSeenIntro()) return;
  if (!host.querySelector(GUIDE_TRIGGER)) return;

  const { driver } = await import('driver.js');
  // Same detach race as runTour: don't build a driver against a swapped-out host.
  if (!host.isConnected) return;
  ensureDriverStyles();

  let d: Driver;
  d = driver({
    allowClose: true,
    // Fires whether the user starts the tour or dismisses — either way, don't nag again.
    onDestroyed: () => {
      clearSession(d);
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
            void startWalkthrough(ctx);
          },
        },
      },
    ],
  });
  trackSession(host, d);
  d.drive();
}
