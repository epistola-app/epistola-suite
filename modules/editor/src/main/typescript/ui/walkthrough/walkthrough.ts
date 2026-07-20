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
import { hasSeenIntro, markChapterComplete, markIntroSeen } from './progress.js';

const GUIDE_TRIGGER = '[data-tour="guide-trigger"]';

const STYLE_ID = 'ep-driver-css';

function ensureDriverStyles(): void {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = driverCss;
  document.head.appendChild(style);
}

/** Run a single chapter, chaining to the next chapter when the user finishes it. */
async function runTour(host: HTMLElement, tour: Tour): Promise<void> {
  const { driver } = await import('driver.js');
  ensureDriverStyles();

  const upcoming = nextTour(tour.id);
  let d: Driver;

  const steps: DriveStep[] = tour.steps(host).map((step, i, all) => {
    const isLast = i === all.length - 1;
    return {
      element: step.target,
      onHighlightStarted: step.before ? () => step.before?.(host) : undefined,
      popover: {
        title: step.title,
        description: step.body,
        side: step.side,
        align: 'start',
        // The last step's Done button both records completion and, if there is a
        // next chapter, chains straight into it — the "level up" affordance. The
        // X (onCloseClick, default) just dismisses without marking complete.
        ...(isLast
          ? {
              doneBtnText: upcoming ? `Next: ${upcoming.title} →` : 'Done',
              onDoneClick: () => {
                markChapterComplete(tour.id, tour.version);
                d.destroy();
                if (upcoming) void runTour(host, upcoming);
              },
            }
          : {}),
      },
      skipMissingElement: true,
    };
  });

  if (steps.length === 0) return;

  d = driver({
    showProgress: true,
    progressText: '{{current}} of {{total}}',
    allowClose: true,
    steps,
  });
  d.drive();
}

/** Start a specific chapter by id (used by the Guide launcher). No-op if unknown. */
export async function startTour(host: HTMLElement, tourId: string): Promise<void> {
  const tour = tourById(tourId);
  if (tour) await runTour(host, tour);
}

/** Start at the first unfinished chapter, or replay from the top when all are done. */
export async function startWalkthrough(host: HTMLElement): Promise<void> {
  const tour = firstIncompleteTour() ?? TOURS[0];
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
    onDestroyed: () => markIntroSeen(),
    steps: [
      {
        element: GUIDE_TRIGGER,
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
  d.drive();
}
