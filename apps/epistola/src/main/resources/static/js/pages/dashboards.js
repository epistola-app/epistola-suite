// Dashboard page behaviors (ADR 0010: no executable inline scripts in templates).
//
// Covers: cluster/dashboard.html, consumers/dashboard.html, logs/list.html.
//
// Listeners are installed once on `document`; per-content initialization runs on
// `htmx:load` (htmx fires it for the initial page AND for every swapped-in
// element) and is idempotent, so the periodic `hx-trigger="every …"` refreshes
// and boosted navigation can re-deliver the markup safely. The polling itself is
// htmx-owned and stops automatically when the dashboard element leaves the DOM —
// this file creates no timers.

(function () {
  'use strict';

  // querySelectorAll, but also matching the root element itself (outerHTML swaps
  // can deliver the hooked element as the htmx:load root).
  function queryAll(root, selector) {
    if (!root.querySelectorAll) return [];
    const matches = Array.prototype.slice.call(root.querySelectorAll(selector));
    if (root.matches && root.matches(selector)) matches.unshift(root);
    return matches;
  }

  // ── Cluster dashboard: render timestamps in the browser timezone ──────────
  // Usage: <span data-local-datetime="2026-01-01T00:00:00Z">   → text + title
  //        <span data-local-datetime-title="…">                → title only
  const clusterDateTimeFormatter = new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZoneName: 'short',
  });

  function formatClusterDateTime(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : clusterDateTimeFormatter.format(date);
  }

  function initClusterDateTimes(root) {
    queryAll(root, '[data-local-datetime]').forEach(function (element) {
      const formatted = formatClusterDateTime(element.dataset.localDatetime);
      if (formatted) {
        element.textContent = formatted;
        element.title = formatted;
      }
    });
    queryAll(root, '[data-local-datetime-title]').forEach(function (element) {
      const formatted = formatClusterDateTime(element.dataset.localDatetimeTitle);
      if (formatted) {
        element.title = formatted;
      }
    });
  }

  // ── Consumers dashboard: persist <details> open state across refreshes ────
  // Usage: <details data-consumer-id="…">
  // Open/closed state is stored per consumer in localStorage so the 10s
  // hx-trigger refresh (outerHTML swap) restores what the user expanded.
  const CONSUMER_OPEN_PREFIX = 'consumer-open:';

  function consumerOpenKey(id) {
    return CONSUMER_OPEN_PREFIX + id;
  }

  function initConsumerCards(root) {
    queryAll(root, 'details[data-consumer-id]').forEach(function (details) {
      try {
        if (localStorage.getItem(consumerOpenKey(details.dataset.consumerId)) === '1') {
          details.open = true;
        }
      } catch (e) {
        /* localStorage may be blocked; ignore */
      }
    });
  }

  // `toggle` does not bubble → listen in the capture phase.
  document.addEventListener(
    'toggle',
    function (event) {
      const details = event.target;
      if (!details.matches || !details.matches('details[data-consumer-id]')) return;
      const key = consumerOpenKey(details.dataset.consumerId);
      try {
        if (details.open) localStorage.setItem(key, '1');
        else localStorage.removeItem(key);
      } catch (e) {
        /* ignore */
      }
    },
    true,
  );

  // Usage: <button data-consumer-set-open="true">Expand all</button>
  //        <button data-consumer-set-open="false">Collapse all</button>
  // Setting `open` fires `toggle`, so the persistence listener above records it.
  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;
    const button = event.target.closest('[data-consumer-set-open]');
    if (!button) return;
    const open = button.getAttribute('data-consumer-set-open') === 'true';
    const scope = button.closest('#consumer-results') || document;
    scope.querySelectorAll('details[data-consumer-id]').forEach(function (details) {
      details.open = open;
    });
  });

  // ── Logs page: browser timezone + local time rendering ────────────────────
  // Usage: <input type="hidden" name="tz" data-browser-tz />   → filled with the
  //        browser timezone so the server interprets From/To wall-clock values.
  //        <td class="log-time" data-ts="…">                   → local time text
  //        (falls back to the server-rendered UTC text when JS is unavailable).
  const logTimeFormatter = new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });

  function initBrowserTimezoneInputs(root) {
    queryAll(root, 'input[data-browser-tz]').forEach(function (input) {
      try {
        input.value = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
      } catch (e) {
        /* leave blank → server falls back to UTC */
      }
    });
  }

  function initLogTimes(root) {
    queryAll(root, '.log-time[data-ts]').forEach(function (element) {
      if (element.dataset.tsDone) return;
      const date = new Date(element.getAttribute('data-ts'));
      if (!Number.isNaN(date.getTime())) {
        element.textContent = logTimeFormatter.format(date);
        element.title = element.getAttribute('data-ts');
        element.dataset.tsDone = '1';
      }
    });
  }

  // ── Per-content initialization ─────────────────────────────────────────────
  document.addEventListener('htmx:load', function (event) {
    const root = event.detail.elt;
    initClusterDateTimes(root);
    initConsumerCards(root);
    initBrowserTimezoneInputs(root);
    initLogTimes(root);
  });
})();
