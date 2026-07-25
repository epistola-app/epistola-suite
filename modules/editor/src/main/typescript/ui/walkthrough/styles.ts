// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/** Inject a stylesheet into <head> exactly once, keyed by id. CSP allows inline styles. */
export function injectStyleOnce(id: string, css: string): void {
  if (document.getElementById(id)) return;
  const style = document.createElement('style');
  style.id = id;
  style.textContent = css;
  document.head.appendChild(style);
}
