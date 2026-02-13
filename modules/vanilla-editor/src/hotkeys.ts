/**
 * Hotkey integration — installs @github/hotkey on elements with `data-hotkey` attributes.
 *
 * Call `installHotkeys(container)` after mount and `uninstallHotkeys(container)` on destroy.
 * Elements with `data-hotkey` attributes will trigger their click handler on the specified key.
 *
 * Standard hotkeys:
 * - `Mod+z` — Undo
 * - `Mod+y` — Redo
 * - `Mod+s` — Save
 * - `Delete` — Delete selected block
 * - `Escape` — Clear selection
 */

import { install, uninstall } from '@github/hotkey';

/**
 * Install hotkeys on all `[data-hotkey]` elements within a container.
 * Returns a cleanup function that uninstalls all hotkeys.
 */
export function installHotkeys(container: HTMLElement): () => void {
  const elements = container.querySelectorAll<HTMLElement>('[data-hotkey]');
  for (const el of elements) {
    install(el);
  }

  return () => uninstallHotkeys(container);
}

/** Uninstall hotkeys from all `[data-hotkey]` elements within a container. */
export function uninstallHotkeys(container: HTMLElement): void {
  const elements = container.querySelectorAll<HTMLElement>('[data-hotkey]');
  for (const el of elements) {
    uninstall(el);
  }
}
