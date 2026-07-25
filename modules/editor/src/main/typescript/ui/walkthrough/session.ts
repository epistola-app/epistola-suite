// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * The one live tour session, kept in a driver-free module so the launcher can
 * tear it down without statically importing the runner (which would pull
 * driver.js into the always-loaded launcher chunk). The `Driver` import is
 * type-only and erased at build; calling `.destroy()` needs no driver.js code.
 *
 * The session is page-global — driver.js is a singleton at heart (one overlay,
 * body classes, window listeners), so two tours can never run at once — but it
 * remembers WHICH editor host the tour belongs to, so a launcher tearing down
 * "its" tour (on disconnect, on opening its menu) can never kill a tour that a
 * different mounted editor is running.
 */
import type { Driver } from 'driver.js';

interface TourSession {
  host: HTMLElement;
  driver: Driver;
}

let active: TourSession | null = null;

/**
 * Track `driver` as the live tour for `host`. Destroys any other live tour
 * first — starting is always exclusive, because driver.js can only run one
 * overlay per page.
 */
export function trackSession(host: HTMLElement, driver: Driver): void {
  if (active && active.driver !== driver) active.driver.destroy();
  active = { host, driver };
}

/** Forget `driver` if it is the tracked one (its onDestroyed hook calls this). */
export function clearSession(driver: Driver): void {
  if (active?.driver === driver) active = null;
}

/**
 * Destroy the live tour. With `host` given, only when the tour belongs to that
 * host — a launcher must not kill another editor's tour. Without, any tour.
 * Safe to call when nothing is running.
 */
export function stopTour(host?: HTMLElement): void {
  if (!active) return;
  if (host && active.host !== host) return;
  const { driver } = active;
  active = null;
  driver.destroy();
}
