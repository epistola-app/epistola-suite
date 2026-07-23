// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  firstRunnableTour,
  isTourAvailable,
  nextAvailableTour,
  nextTour,
  type Tour,
  tourById,
  TOURS,
} from './registry.js';
import { isChapterComplete, markChapterComplete } from './progress.js';

function fakeTour(id: string, available = true): Tour {
  return {
    id,
    title: id,
    summary: id,
    version: 1,
    steps: () => [],
    ...(available ? {} : { isAvailable: () => false, unavailableHint: 'locked' }),
  };
}

const notComplete = (): boolean => false;
const allComplete = (): boolean => true;

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

  it('firstRunnableTour advances as chapters are completed, then replays the first', () => {
    const host = document.createElement('div');
    expect(firstRunnableTour(isChapterComplete, host)?.id).toBe('orientation');
    markChapterComplete('orientation', 1);
    expect(firstRunnableTour(isChapterComplete, host)?.id).toBe('building');
    for (const t of TOURS) markChapterComplete(t.id, t.version);
    // All done → replay from the first chapter.
    expect(firstRunnableTour(isChapterComplete, host)?.id).toBe('orientation');
  });
});

describe('walkthrough availability', () => {
  const host = document.createElement('div');

  it('isTourAvailable defaults to true and honours the predicate', () => {
    expect(isTourAvailable(fakeTour('a'), host)).toBe(true);
    expect(isTourAvailable(fakeTour('a', false), host)).toBe(false);
  });

  it('nextAvailableTour skips locked chapters', () => {
    const tours = [fakeTour('a'), fakeTour('b', false), fakeTour('c')];
    expect(nextAvailableTour('a', host, tours)?.id).toBe('c');
    expect(nextAvailableTour('c', host, tours)).toBeUndefined();
    expect(nextAvailableTour('missing', host, tours)).toBeUndefined();
  });

  it('firstRunnableTour skips locked chapters (incomplete and replay)', () => {
    const tours = [fakeTour('a', false), fakeTour('b'), fakeTour('c')];
    // First not-yet-complete AND runnable.
    expect(firstRunnableTour(notComplete, host, tours)?.id).toBe('b');
    // All complete → first runnable to replay (still skips the locked one).
    expect(firstRunnableTour(allComplete, host, tours)?.id).toBe('b');
  });

  it('firstRunnableTour returns undefined when nothing can run', () => {
    const tours = [fakeTour('a', false), fakeTour('b', false)];
    expect(firstRunnableTour(notComplete, host, tours)).toBeUndefined();
  });
});
