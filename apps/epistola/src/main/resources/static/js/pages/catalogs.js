// Catalog page behaviors (templates/catalogs/list.html, templates/catalogs/browse.html).
// ADR 0010: no executable inline scripts in templates.
//
// Conventions (same as behaviors.js):
// - Templates declare intent with data-* attributes; behavior lives here.
// - Listeners are installed once on `document` — HTMX events bubble, so handlers
//   work for content present at load AND content swapped in later (dialogs on
//   these pages are injected on demand into always-present containers).

// ── Close a dialog after a successful HTMX form submit ─────────────────────
// Usage: <form hx-post="…" data-close-dialog-on-success="dialog-id">
// Used by the New Catalog dialog (list) and the install-preview dialog (browse,
// where the form itself arrives via an HTMX swap). Combine with
// data-reset-on-success (behaviors.js) to also clear the form.
document.addEventListener('htmx:afterRequest', function (event) {
  const form = event.target.closest && event.target.closest('form[data-close-dialog-on-success]');
  if (!form || !event.detail.successful) return;
  const dialog = document.getElementById(form.getAttribute('data-close-dialog-on-success'));
  if (dialog) dialog.close();
});

// The release / upgrade-preview / export-conflict dialogs are fetched on demand
// (hx-get) into always-present `data-open-dialog-on-swap` containers and opened
// after the swap. That open-on-swap behavior is now app-wide (static/js/
// behaviors.js) — both the legacy `data-open-dialog-on-swap="id"` containers
// used here and the generic `data-dialog-mount` mount point are handled there.

// ── Subscribe dialog: auth type toggles the credential field ────────────────
// Usage: <select data-auth-credential-toggle="credential-group">
// Shows the referenced element unless the selected value is NONE.
document.addEventListener('change', function (event) {
  const select =
    event.target.closest && event.target.closest('select[data-auth-credential-toggle]');
  if (!select) return;
  const group = document.getElementById(select.getAttribute('data-auth-credential-toggle'));
  if (group) group.style.display = select.value === 'NONE' ? 'none' : 'block';
});

// ── Release dialog: version bump buttons fill the version input ─────────────
// Usage: <button data-version="1.2.3"> inside #release-dialog
// (scoped to the release dialog so the attribute name stays local to it).
document.addEventListener('click', function (event) {
  const btn = event.target.closest && event.target.closest('#release-dialog [data-version]');
  if (!btn) return;
  const input = document.getElementById('release-version');
  if (input) input.value = btn.getAttribute('data-version');
});
