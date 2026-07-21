// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  hasSeenIntro,
  isChapterComplete,
  markChapterComplete,
  markIntroSeen,
  subscribeProgress,
} from './progress.js';

describe('walkthrough progress', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('marks and reads chapter completion', () => {
    expect(isChapterComplete('a', 1)).toBe(false);
    markChapterComplete('a', 1);
    expect(isChapterComplete('a', 1)).toBe(true);
  });

  it('re-surfaces a chapter when its version is bumped past the completed one', () => {
    markChapterComplete('a', 1);
    expect(isChapterComplete('a', 2)).toBe(false);
    markChapterComplete('a', 2);
    expect(isChapterComplete('a', 2)).toBe(true);
  });

  it('never downgrades a newer stored completion (stale bundle replay)', () => {
    markChapterComplete('a', 3);
    // A stale bundle replays the chapter at its older version...
    markChapterComplete('a', 1);
    // ...and must not re-surface it for someone who finished the newer one.
    expect(isChapterComplete('a', 3)).toBe(true);
  });

  it('notifies subscribers on writes and stops after unsubscribe', () => {
    const spy = vi.fn();
    const unsubscribe = subscribeProgress(spy);
    markChapterComplete('a', 1);
    expect(spy).toHaveBeenCalledTimes(1);
    markIntroSeen();
    expect(spy).toHaveBeenCalledTimes(2);
    unsubscribe();
    markChapterComplete('b', 1);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('tracks the intro coach-mark seen flag', () => {
    expect(hasSeenIntro()).toBe(false);
    markIntroSeen();
    expect(hasSeenIntro()).toBe(true);
  });
});
