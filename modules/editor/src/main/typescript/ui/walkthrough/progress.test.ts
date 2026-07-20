// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { hasSeenIntro, isChapterComplete, markChapterComplete, markIntroSeen } from './progress.js';

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

  it('tracks the intro coach-mark seen flag', () => {
    expect(hasSeenIntro()).toBe(false);
    markIntroSeen();
    expect(hasSeenIntro()).toBe(true);
  });
});
