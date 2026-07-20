/** Shared helpers for tour step side-effects. */

/** Switch the sidebar to a tab by id (`blocks` | `structure` | `inspector`). No-op if absent. */
export function clickSidebarTab(host: HTMLElement, tabId: string): void {
  const el = host.querySelector(`[data-tour="tab-${tabId}"]`);
  if (el instanceof HTMLElement) el.click();
}
