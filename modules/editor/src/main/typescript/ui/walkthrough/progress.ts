/**
 * Walkthrough progress, persisted per browser in localStorage.
 *
 * Two things are tracked, both best-effort (a disabled/unavailable localStorage
 * degrades to "nothing remembered", never an error):
 *  - per-chapter completion, stored as the chapter *version* that was completed,
 *    so bumping a chapter's version re-surfaces just that chapter for people who
 *    finished the older one;
 *  - whether the first-run intro coach-mark has been shown.
 *
 * Writes notify subscribers so any live UI (the launcher's ✓/▶ marks) can refresh
 * instead of relying on an incidental re-render.
 */
const NS = 'ep:editor-walkthrough:';
const INTRO_KEY = `${NS}intro-seen`;

const listeners = new Set<() => void>();

/** Subscribe to progress changes; returns an unsubscribe function. */
export function subscribeProgress(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function notify(): void {
  for (const listener of listeners) listener();
}

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Non-fatal: progress simply isn't remembered.
  }
  notify();
}

/** A chapter is complete when a completion at least as new as `version` is stored. */
export function isChapterComplete(id: string, version: number): boolean {
  const raw = read(`${NS}chapter:${id}`);
  return raw !== null && Number(raw) >= version;
}

export function markChapterComplete(id: string, version: number): void {
  const key = `${NS}chapter:${id}`;
  const stored = Number(read(key));
  // Never downgrade: a stale bundle replaying an older chapter must not overwrite
  // a newer completion and re-surface the chapter for everyone.
  const next = Math.max(Number.isFinite(stored) ? stored : 0, version);
  write(key, String(next));
}

/** Whether the first-run intro coach-mark (pointing at the Guide button) has been shown. */
export function hasSeenIntro(): boolean {
  return read(INTRO_KEY) === 'true';
}

export function markIntroSeen(): void {
  write(INTRO_KEY, 'true');
}
