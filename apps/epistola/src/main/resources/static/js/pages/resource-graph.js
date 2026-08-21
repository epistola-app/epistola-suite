// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

document.addEventListener('htmx:load', (event) => {
  const root = event.detail?.elt ?? document;
  if (!root.querySelector?.('ep-resource-graph') && !root.matches?.('ep-resource-graph')) return;
  import('/editor/resource-graph.js');
});
