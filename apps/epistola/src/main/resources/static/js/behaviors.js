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

// ── Dialog open / close ─────────────────────────────────────────────────────
// Usage: <button data-open-dialog="my-dialog">            → showModal()
//        <button data-close-dialog>                       → closes closest <dialog>
//        <button data-close-dialog="my-dialog">           → closes that dialog
//        add data-then-reload to reload the page after closing
document.addEventListener('click', function (event) {
  if (!event.target.closest) return;

  const opener = event.target.closest('[data-open-dialog]');
  if (opener) {
    const dialog = document.getElementById(opener.getAttribute('data-open-dialog'));
    if (dialog) dialog.showModal();
    return;
  }

  const closer = event.target.closest('[data-close-dialog]');
  if (closer) {
    const id = closer.getAttribute('data-close-dialog');
    const dialog = id ? document.getElementById(id) : closer.closest('dialog');
    if (dialog) dialog.close();
    if (closer.hasAttribute('data-then-reload')) window.location.reload();
  }
});

// ── Confirm dialog for destructive actions ──────────────────────────────────
// Usage: <button data-confirm-url="…" data-confirm-title="…"
//                data-confirm-message="…" data-confirm-target="#rows">
// (openConfirmDialog in app-shell.js reads the data-confirm-* attributes.)
document.addEventListener('click', function (event) {
  const trigger = event.target.closest && event.target.closest('[data-confirm-url]');
  if (trigger) window.openConfirmDialog(trigger);
});

// ── Catalog filter navigation on list pages ─────────────────────────────────
// Usage: <select data-catalog-filter-base="/tenants/acme/templates">
document.addEventListener('change', function (event) {
  const select = event.target.closest && event.target.closest('select[data-catalog-filter-base]');
  if (!select) return;
  let url = select.getAttribute('data-catalog-filter-base');
  if (select.value) url += '?catalog=' + encodeURIComponent(select.value);
  window.location.href = url;
});
