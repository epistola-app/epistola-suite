// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { firstIncompleteTour, nextTour, tourById, TOURS } from './registry.js';
import { markChapterComplete } from './progress.js';

describe('walkthrough registry', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('exposes ordered chapters starting with orientation', () => {
    expect(TOURS.length).toBeGreaterThanOrEqual(2);
    expect(TOURS[0].id).toBe('orientation');
  });

  it('nextTour returns the following chapter, undefined past the end or for unknown ids', () => {
    expect(nextTour('orientation')?.id).toBe('building');
    expect(nextTour(TOURS[TOURS.length - 1].id)).toBeUndefined();
    expect(nextTour('does-not-exist')).toBeUndefined();
  });

  it('tourById finds a chapter by id', () => {
    expect(tourById('building')?.title).toBe('Building your document');
    expect(tourById('does-not-exist')).toBeUndefined();
  });

  it('firstIncompleteTour advances as chapters are completed', () => {
    expect(firstIncompleteTour()?.id).toBe('orientation');
    markChapterComplete('orientation', 1);
    expect(firstIncompleteTour()?.id).toBe('building');
    for (const t of TOURS) markChapterComplete(t.id, t.version);
    expect(firstIncompleteTour()).toBeUndefined();
  });
});
