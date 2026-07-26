// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * The walkthrough is a registry of small, self-contained **chapters** (tours),
 * driven one at a time. This keeps each tour short (3–5 steps) and independently
 * editable, and lets the UI show progress, "next chapter", and per-chapter ✓.
 *
 * Add a chapter by writing a `tours/<id>.ts` and appending it to {@link TOURS}.
 */
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { TourTarget } from './targets.js';
import { orientationTour } from './tours/orientation.js';
import { buildingTour } from './tours/building.js';
import { editingTour } from './tours/editing.js';
import { stylingTour } from './tours/styling.js';

/** Predicate: has the given chapter version been completed? (Injected, not imported —
 * keeps this catalog pure data and independent of how progress is persisted.) */
export type CompletionPredicate = (id: string, version: number) => boolean;

/**
 * Everything a tour callback gets to work with, resolved once by the launcher.
 *
 * The split matters: **selectors resolve against {@link host}** (spotlights are
 * DOM), but **state is read from and written through {@link engine}** — the
 * model is the source of truth for availability and side-effects, and unlike
 * the DOM it is already correct in the same tick as a dispatch (the canvas
 * repaints asynchronously). Tours are also unit-testable against a real
 * `EditorEngine` with no rendered canvas at all.
 */
export interface TourContext {
  /** The `<epistola-editor>` root; step targets are resolved within it. */
  host: HTMLElement;
  /** The editor's engine — read/write the document model through this. */
  engine: EditorEngine;
}

/** Which side of the target the popover is placed. Mirrors driver.js's `Side`. */
export type TourSide = 'top' | 'right' | 'bottom' | 'left';

export interface TourStep {
  /**
   * CSS selector for the element to spotlight, resolved at show time by
   * driver.js against the document. The editor mounts in light DOM, so plain
   * selectors find their target. The {@link TourTarget} grammar is closed to
   * editor-owned extension anchors, walkthrough-owned anchors, and registered
   * Epistola elements, so targeting component internals does not typecheck.
   * `editor-ui-anchors.test.ts` additionally proves each editor anchor is stamped.
   *
   * A step whose target is absent when highlighted is skipped
   * (`skipMissingElement`). Driver also probes *following* steps' targets to
   * decide which step is "effectively last": a step whose successors' targets
   * only appear after its own `before` runs is fine (the runner pins button
   * labels by index, and click routing re-probes after the re-render), but a
   * target that never appears silently shortens the chapter — driver then ends
   * it at the last step that resolves.
   */
  target: TourTarget;
  title: string;
  body: string;
  side?: TourSide;
  /**
   * Optional side-effect run just before the step is shown. Benign setup only —
   * switch a sidebar tab, open the preview, select a block so the spotlight has a
   * target. Chapters are passive narration; steps never wait on the user.
   * Must be idempotent: it re-runs on chapter replay and on backward navigation.
   */
  before?: (ctx: TourContext) => void;
}

export interface Tour {
  id: string;
  /** Chapter name, shown in the launcher and the "Next: …" button. */
  title: string;
  /** One-line description shown in the launcher. */
  summary: string;
  /** Bump when the chapter changes materially so it re-surfaces for returning users. */
  version: number;
  /** Built lazily with the editor context so selectors/side-effects can be host-aware. */
  steps: (ctx: TourContext) => TourStep[];
  /**
   * Establish the initial UI state the *first* steps need, run once before the tour
   * drives. This is where a chapter selects a block, opens the document inspector, etc.
   * It must live here, not in step `before`s: driver checks a step's target BEFORE it
   * runs that step's `before`, so a step can only ever set up a *later* step's target,
   * never its own. `waitForElement` bridges the async re-render this may trigger.
   * Must be idempotent: it re-runs on chapter replay and on backward navigation.
   */
  setup?: (ctx: TourContext) => void;
  /**
   * Run once when the reader *finishes* the chapter (the Done / "Next: …" button),
   * before chaining to the next chapter. Unlike {@link setup} it may leave a lasting
   * edit behind to set the next chapter up — e.g. the building chapter drops a starter
   * block so the block-centric chapters that follow have something to work with. A
   * chapter that declares `onComplete` advertises its raw next chapter as the upcoming
   * one (trusting the hook to make it runnable); the runner re-checks availability
   * against the model after the hook runs before actually chaining.
   */
  onComplete?: (ctx: TourContext) => void;
  /**
   * When it returns false for the current editor, the chapter can't run here —
   * the launcher shows it locked with {@link unavailableHint} and chaining skips
   * it. Absent ⇒ always available. Read the **model** (`ctx.engine`), not the DOM:
   * chaining re-evaluates this right after an `onComplete` dispatch, before the
   * canvas has repainted.
   */
  isAvailable?: (ctx: TourContext) => boolean;
  /** One-line reason shown in the launcher when {@link isAvailable} returns false. */
  unavailableHint?: string;
}

/** Ordered chapters. Order defines the "next chapter" chaining and launcher listing. */
export const TOURS: readonly Tour[] = [orientationTour, buildingTour, editingTour, stylingTour];

export function tourById(id: string): Tour | undefined {
  return TOURS.find((t) => t.id === id);
}

/** The chapter after `id` in {@link TOURS}, or undefined if `id` is last/unknown. */
export function nextTour(id: string): Tour | undefined {
  const i = TOURS.findIndex((t) => t.id === id);
  return i >= 0 ? TOURS[i + 1] : undefined;
}

/** Whether `tour` can run in this editor (D6). Absent predicate ⇒ available. */
export function isTourAvailable(tour: Tour, ctx: TourContext): boolean {
  return tour.isAvailable?.(ctx) ?? true;
}

/**
 * The next chapter after `id` that can actually run in this editor, skipping
 * unavailable ones. `tours` defaults to {@link TOURS} (a seam for tests).
 */
export function nextAvailableTour(
  id: string,
  ctx: TourContext,
  tours: readonly Tour[] = TOURS,
): Tour | undefined {
  const start = tours.findIndex((t) => t.id === id);
  if (start < 0) return undefined;
  for (let i = start + 1; i < tours.length; i++) {
    const t = tours[i];
    if (isTourAvailable(t, ctx)) return t;
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
  ctx: TourContext,
  tours: readonly Tour[] = TOURS,
): Tour | undefined {
  return (
    tours.find((t) => !isComplete(t.id, t.version) && isTourAvailable(t, ctx)) ??
    tours.find((t) => isTourAvailable(t, ctx))
  );
}
