// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Driver } from 'driver.js';
import { getActiveDriver, isTourActive, setActiveDriver, stopActiveTour } from './session.js';

function fakeDriver(): Driver {
  return { destroy: vi.fn() } as unknown as Driver;
}

describe('walkthrough session', () => {
  afterEach(() => setActiveDriver(null));

  it('reports no active tour by default', () => {
    expect(isTourActive()).toBe(false);
    expect(getActiveDriver()).toBeNull();
  });

  it('tracks the active driver once set', () => {
    const d = fakeDriver();
    setActiveDriver(d);
    expect(isTourActive()).toBe(true);
    expect(getActiveDriver()).toBe(d);
  });

  it('destroys and clears the active driver on stop', () => {
    const d = fakeDriver();
    setActiveDriver(d);
    stopActiveTour();
    expect(d.destroy).toHaveBeenCalledOnce();
    expect(isTourActive()).toBe(false);
  });

  it('stop is a no-op when nothing is running', () => {
    expect(() => stopActiveTour()).not.toThrow();
    expect(isTourActive()).toBe(false);
  });
});
