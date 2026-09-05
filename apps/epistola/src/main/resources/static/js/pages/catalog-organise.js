// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Loaded on htmx:load rather than from the page's own head: the shell uses hx-boost, so navigating
// to this page is a body swap and a per-page script would never (re)run. See ADR 0010.
document.addEventListener('htmx:load', (event) => {
  const root = event.detail?.elt ?? document;
  if (!root.querySelector?.('ep-catalog-organise') && !root.matches?.('ep-catalog-organise'))
    return;
  void import('/editor/catalog-organise.js');
});
