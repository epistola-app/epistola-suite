/**
 * The one live tour's driver handle, kept in a driver-free module so the launcher
 * can query and tear it down without statically importing the runner (which would
 * pull driver.js into the always-loaded launcher chunk). The `Driver` import is
 * type-only and erased at build; calling `.destroy()` needs no driver.js code.
 */
import type { Driver } from 'driver.js';

let activeDriver: Driver | null = null;

export function setActiveDriver(d: Driver | null): void {
  activeDriver = d;
}

export function getActiveDriver(): Driver | null {
  return activeDriver;
}

/** Whether a walkthrough (chapter or intro) is currently on screen. */
export function isTourActive(): boolean {
  return activeDriver !== null;
}

/** Destroy any live tour. Safe to call when nothing is running. */
export function stopActiveTour(): void {
  activeDriver?.destroy();
  activeDriver = null;
}
