/** Shared helpers for tour step side-effects. */

/** Switch the sidebar to a tab by id (`blocks` | `structure` | `inspector`). No-op if absent. */
export function clickSidebarTab(host: HTMLElement, tabId: string): void {
  const el = host.querySelector(`[data-tour="tab-${tabId}"]`);
  if (el instanceof HTMLElement) el.click();
}

/**
 * Switch the sidebar tab on the next frame. The sidebar force-switches to the
 * Inspector whenever the *selection changes* to a node, so a synchronous switch
 * right after adding/selecting a block gets clobbered in the same render batch.
 * Deferring one frame lets that auto-switch settle, so our tab click lands in a
 * clean render and sticks — and the block stays selected (the tree highlights it).
 */
export function clickSidebarTabNextFrame(host: HTMLElement, tabId: string): void {
  requestAnimationFrame(() => clickSidebarTab(host, tabId));
}

/**
 * Open the live PDF preview if it isn't already, so edits are visible as they
 * render. No-op when there's no preview (the toggle is absent) or it's already open.
 */
export function openPreview(host: HTMLElement): void {
  const toggle = host.querySelector('[data-tour="preview-toggle"]');
  if (toggle instanceof HTMLElement && !toggle.classList.contains('active')) {
    toggle.click();
  }
}
