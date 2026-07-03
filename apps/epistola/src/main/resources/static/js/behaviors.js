// Delegated UI behaviors (ADR 0010: no executable inline scripts in templates).
//
// Conventions:
// - Templates declare intent with data-* attributes; behavior lives here.
// - Listeners are installed once on `document` — HTMX events bubble, so handlers
//   work for content present at load AND content swapped in later. Never bind
//   directly to elements that HTMX may replace.
// - Server data reaches JS via inert <script type="application/json"> islands,
//   parsed on htmx:load for newly settled content.

// ── Form reset after successful HTMX submit ────────────────────────────────
// Usage: <form hx-post="…" data-reset-on-success>
document.addEventListener('htmx:afterRequest', function (event) {
  const form = event.target.closest && event.target.closest('form[data-reset-on-success]');
  if (form && event.detail.successful) form.reset();
});
