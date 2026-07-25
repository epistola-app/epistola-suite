// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Driver } from 'driver.js';
import { clearSession, stopTour, trackSession } from './session.js';

function fakeDriver(): Driver {
  return { destroy: vi.fn() } as unknown as Driver;
}

function host(): HTMLElement {
  return document.createElement('div');
}

describe('walkthrough session', () => {
  afterEach(() => stopTour());

  it('stop is a no-op when nothing is running', () => {
    expect(() => stopTour()).not.toThrow();
  });

  it('destroys the tracked driver on an unscoped stop', () => {
    const d = fakeDriver();
    trackSession(host(), d);
    stopTour();
    expect(d.destroy).toHaveBeenCalledOnce();
  });

  it('a host-scoped stop only destroys that host’s tour', () => {
    const d = fakeDriver();
    const h = host();
    trackSession(h, d);

    stopTour(host()); // some other editor's launcher
    expect(d.destroy).not.toHaveBeenCalled();

    stopTour(h); // the owning editor's launcher
    expect(d.destroy).toHaveBeenCalledOnce();
  });

  it('tracking a new session destroys the previous one (tours are exclusive)', () => {
    const d1 = fakeDriver();
    const d2 = fakeDriver();
    trackSession(host(), d1);
    trackSession(host(), d2);
    expect(d1.destroy).toHaveBeenCalledOnce();

    stopTour();
    expect(d2.destroy).toHaveBeenCalledOnce();
  });

  it('re-tracking the same driver does not destroy it', () => {
    const d = fakeDriver();
    const h = host();
    trackSession(h, d);
    trackSession(h, d);
    expect(d.destroy).not.toHaveBeenCalled();
  });

  it('clearSession forgets the tracked driver so stop no longer destroys it', () => {
    const d = fakeDriver();
    trackSession(host(), d);
    clearSession(d);
    stopTour();
    expect(d.destroy).not.toHaveBeenCalled();
  });

  it('clearSession ignores a driver that is not the tracked one', () => {
    const d = fakeDriver();
    trackSession(host(), d);
    clearSession(fakeDriver());
    stopTour();
    expect(d.destroy).toHaveBeenCalledOnce();
  });
});
