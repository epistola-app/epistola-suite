/**
 * The walkthrough is a registry of small, self-contained **chapters** (tours),
 * driven one at a time. This keeps each tour short (3–5 steps) and independently
 * editable, and lets the UI show progress, "next chapter", and per-chapter ✓.
 *
 * Add a chapter by writing a `tours/<id>.ts` and appending it to {@link TOURS}.
 */
import { isChapterComplete } from './progress.js';
import { orientationTour } from './tours/orientation.js';
import { buildingTour } from './tours/building.js';

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

/** The first chapter the user has not completed, or undefined when all are done. */
export function firstIncompleteTour(): Tour | undefined {
  return TOURS.find((t) => !isChapterComplete(t.id, t.version));
}
