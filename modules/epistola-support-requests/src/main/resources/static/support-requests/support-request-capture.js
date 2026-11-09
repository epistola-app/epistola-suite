// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Console log and request trace capture for feedback submissions.
 *
 * Monkey-patches console.log/warn/error/info to buffer the last 100 entries
 * (window.__epistola_console_buffer), and keeps the W3C trace ids of the last 20
 * requests (window.__epistola_trace_buffer): the page's own, from
 * `<meta name="traceparent">`, and every HTMX answer's `traceresponse` header.
 * Both are the host contract of docs/support-requests.md; the form sends them along.
 */
(function () {
  // The including fragment is re-swapped on every boosted navigation, which
  // re-executes this script: patch the console (and reset the buffer) only once.
  if (window.__epistola_console_buffer) return;

  const MAX_TRACES = 20;
  const traces = [];
  window.__epistola_trace_buffer = traces;

  /** Adds the trace id of a `traceparent`/`traceresponse` value (`00-<trace id>-<span id>-<flags>`). */
  function rememberTrace(value) {
    const traceId = (value || '').split('-')[1];
    if (!traceId || !/^[0-9a-f]{32}$/.test(traceId)) return;
    const known = traces.indexOf(traceId);
    if (known !== -1) traces.splice(known, 1);
    traces.push(traceId);
    if (traces.length > MAX_TRACES) traces.shift();
  }

  rememberTrace(document.querySelector('meta[name="traceparent"]')?.content);
  document.addEventListener('htmx:afterRequest', (event) => {
    rememberTrace(event.detail?.xhr?.getResponseHeader('traceresponse'));
  });

  const MAX_ENTRIES = 100;
  const buffer = [];

  window.__epistola_console_buffer = buffer;

  const methods = ['log', 'warn', 'error', 'info'];
  const originals = {};

  methods.forEach((method) => {
    originals[method] = console[method];
    console[method] = function (...args) {
      buffer.push({
        level: method,
        timestamp: new Date().toISOString(),
        message: args
          .map((arg) => {
            try {
              return typeof arg === 'object' ? JSON.stringify(arg) : String(arg);
            } catch {
              return String(arg);
            }
          })
          .join(' '),
      });

      // Keep only the last MAX_ENTRIES
      if (buffer.length > MAX_ENTRIES) {
        buffer.shift();
      }

      // Call the original method
      originals[method].apply(console, args);
    };
  });
})();
