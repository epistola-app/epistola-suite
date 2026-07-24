// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Audit page behavior (CSP-safe: no inline scripts — ADR 0010).
 *
 * Included from templates/audit/list.html as <script src=…>. HTMX re-executes
 * the tag every time the fragment is swapped in (boosted navigation), so the
 * __epAuditInit guard keeps listener installation idempotent; per-swap work
 * runs via htmx:load, which fires for the initial page and for every
 * swapped-in element.
 *
 * Hooks:
 * - #audit-tz                → filled with the browser timezone, sent so the
 *   server interprets the From/To wall-clock values in it
 * - .audit-time[data-ts]     → re-rendered in the browser's timezone
 *   (falls back to the server-rendered UTC text when JavaScript is unavailable)
 */
(function () {
  if (window.__epAuditInit) return;
  window.__epAuditInit = true;

  var fmt = new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });

  function fillTimezone() {
    var tz = document.getElementById('audit-tz');
    if (tz && !tz.value) {
      try {
        tz.value = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
      } catch (e) {
        /* leave blank → server falls back to UTC */
      }
    }
  }

  function formatAuditTimes() {
    document.querySelectorAll('.audit-time[data-ts]').forEach(function (el) {
      if (el.dataset.tsDone) return;
      var d = new Date(el.getAttribute('data-ts'));
      if (!isNaN(d.getTime())) {
        el.textContent = fmt.format(d);
        el.title = el.getAttribute('data-ts');
        el.dataset.tsDone = '1';
      }
    });
  }

  function init() {
    fillTimezone();
    formatAuditTimes();
  }

  document.addEventListener('htmx:load', init);
  init();
})();
