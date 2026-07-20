/**
 * Walkthrough progress, persisted per browser in localStorage.
 *
 * Two things are tracked, both best-effort (a disabled/unavailable localStorage
 * degrades to "nothing remembered", never an error):
 *  - per-chapter completion, stored as the chapter *version* that was completed,
 *    so bumping a chapter's version re-surfaces just that chapter for people who
 *    finished the older one;
 *  - whether the first-run intro coach-mark has been shown.
 */
const NS = 'ep:editor-walkthrough:';
const INTRO_KEY = `${NS}intro-seen`;

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
}

/** A chapter is complete when a completion at least as new as `version` is stored. */
export function isChapterComplete(id: string, version: number): boolean {
  const raw = read(`${NS}chapter:${id}`);
  return raw !== null && Number(raw) >= version;
}

export function markChapterComplete(id: string, version: number): void {
  write(`${NS}chapter:${id}`, String(version));
}

/** Whether the first-run intro coach-mark (pointing at the Guide button) has been shown. */
export function hasSeenIntro(): boolean {
  return read(INTRO_KEY) === 'true';
}

export function markIntroSeen(): void {
  write(INTRO_KEY, 'true');
}
