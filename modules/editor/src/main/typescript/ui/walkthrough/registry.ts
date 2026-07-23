/**
 * The walkthrough is a registry of small, self-contained **chapters** (tours),
 * driven one at a time. This keeps each tour short (3–5 steps) and independently
 * editable, and lets the UI show progress, "next chapter", and per-chapter ✓.
 *
 * Add a chapter by writing a `tours/<id>.ts` and appending it to {@link TOURS}.
 */
import { orientationTour } from './tours/orientation.js';
import { buildingTour } from './tours/building.js';

/** Predicate: has the given chapter version been completed? (Injected, not imported —
 * keeps this catalog pure data and independent of how progress is persisted.) */
export type CompletionPredicate = (id: string, version: number) => boolean;

/** Which side of the target the popover is placed. Mirrors driver.js's `Side`. */
export type TourSide = 'top' | 'right' | 'bottom' | 'left';

export interface TourStep {
  /**
   * CSS selector for the element to spotlight, resolved at show time by
   * driver.js against the document. The editor mounts in light DOM, so plain
   * selectors (custom-element tags, `[data-tour="…"]` hooks) find their target.
   * A step whose target is absent is skipped (`skipMissingElement`).
   */
  target: string;
  title: string;
  body: string;
  side?: TourSide;
  /** Optional side-effect run just before the step is shown (e.g. switch a sidebar tab). */
  before?: (host: HTMLElement) => void;
  /**
   * Interactive advance hook (D1). When set, the step guides the user to *do*
   * something rather than just narrating it: the runner invokes this once the
   * step is shown, wiring a listener; call the provided `advance` when the user
   * completes the action and the tour moves on. Return a cleanup that removes the
   * listener. Next/Skip stay enabled throughout — this only *auto*-advances, it
   * never gates. Build these with the helpers in `../signals.ts`.
   */
  advance?: (host: HTMLElement, advance: () => void) => () => void;
}

export interface Tour {
  id: string;
  /** Chapter name, shown in the launcher and the "Next: …" button. */
  title: string;
  /** One-line description shown in the launcher. */
  summary: string;
  /** Bump when the chapter changes materially so it re-surfaces for returning users. */
  version: number;
  /** Built lazily with the editor host so selectors/side-effects can be host-aware. */
  steps: (host: HTMLElement) => TourStep[];
  /**
   * Passive overview chapter (D4): steps are non-interactive (regions aren't
   * clickable) and clicking the backdrop advances instead of closing the tour.
   */
  passive?: boolean;
  /**
   * When it returns false for the current editor, the chapter can't run here —
   * the launcher shows it locked with {@link unavailableHint} and chaining skips
   * it. Absent ⇒ always available.
   */
  isAvailable?: (host: HTMLElement) => boolean;
  /** One-line reason shown in the launcher when {@link isAvailable} returns false. */
  unavailableHint?: string;
}

/** Ordered chapters. Order defines the "next chapter" chaining and launcher listing. */
export const TOURS: readonly Tour[] = [orientationTour, buildingTour];

export function tourById(id: string): Tour | undefined {
  return TOURS.find((t) => t.id === id);
}

/** The chapter after `id` in {@link TOURS}, or undefined if `id` is last/unknown. */
export function nextTour(id: string): Tour | undefined {
  const i = TOURS.findIndex((t) => t.id === id);
  return i >= 0 ? TOURS[i + 1] : undefined;
}

/** The first chapter not yet completed per `isComplete`, or undefined when all are done. */
export function firstIncompleteTour(isComplete: CompletionPredicate): Tour | undefined {
  return TOURS.find((t) => !isComplete(t.id, t.version));
}

/** Whether `tour` can run in this editor host (D6). Absent predicate ⇒ available. */
export function isTourAvailable(tour: Tour, host: HTMLElement): boolean {
  return tour.isAvailable?.(host) ?? true;
}

/**
 * The next chapter after `id` that can actually run in this host, skipping
 * unavailable ones. `tours` defaults to {@link TOURS} (a seam for tests).
 */
export function nextAvailableTour(
  id: string,
  host: HTMLElement,
  tours: readonly Tour[] = TOURS,
): Tour | undefined {
  const start = tours.findIndex((t) => t.id === id);
  if (start < 0) return undefined;
  for (let i = start + 1; i < tours.length; i++) {
    const t = tours[i];
    if (isTourAvailable(t, host)) return t;
  }
  return undefined;
}

/**
 * The chapter a "Start walkthrough" should open: the first not-yet-completed one
 * that can run here, or — when all are done — the first runnable chapter to
 * replay. `tours` defaults to {@link TOURS} (a seam for tests).
 */
export function firstRunnableTour(
  isComplete: CompletionPredicate,
  host: HTMLElement,
  tours: readonly Tour[] = TOURS,
): Tour | undefined {
  return (
    tours.find((t) => !isComplete(t.id, t.version) && isTourAvailable(t, host)) ??
    tours.find((t) => isTourAvailable(t, host))
  );
}
