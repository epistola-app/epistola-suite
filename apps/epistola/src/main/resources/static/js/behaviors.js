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

// ── Auto-open a server-sent dialog (the one open-on-swap/load routine) ───────
// App-wide open side of the server-driven dialog lifecycle (the close side is
// HX-Trigger("closeDialog") in app-shell.js). Both are driven purely by the
// server response, so a handler can open, close, or intentionally NOT close a
// dialog — the last is what the api-key reveal needs: it swaps its formless
// panel in and simply omits closeDialog, so nothing here forces it shut.
//
// One routine, three conventions (this replaces the per-page copies that used
// to live in pages/catalogs.js and pages/template-detail.js):
//   [data-dialog-mount]         Preferred. <div id="dialog-mount" data-dialog-mount>
//                               The server renders a <dialog> into it; it opens.
//                               Works BOTH when an hx-get swap targets the mount
//                               AND when the host page embeds the dialog in the
//                               mount at load (direct navigation / shared link).
//   [data-open-dialog-on-swap]  Legacy catalog containers: open the <dialog>
//                               with the id named by the attribute, once the
//                               server has swapped it into the container.
//   [data-show-dialog-on-swap]  The attribute sits on an element INSIDE a static
//                               <dialog>; after content is swapped into it, open
//                               that ancestor <dialog>.
//
// CONTRACT: the server always renders a PLAIN <dialog> (never <dialog open>);
// the client makes it modal via showModal() so it gets a backdrop. A bare
// `open` attribute would open the dialog NON-modally and be skipped below.
function openDialogModal(dialog) {
  // Idempotent: showModal() throws on an already-open dialog, so skip one that
  // is already open (via showModal or a bare `open` attribute).
  if (dialog && !dialog.open) dialog.showModal();
}

// Resolve + open the dialog for a recognized element. The element must BE the
// swap target (the listeners use matches(), not closest()) — otherwise a
// nested/in-place swap under a mount would re-open a dialog the user already
// dismissed (closeDialog only .close()s it; it stays in the mount DOM).
function openDialogFor(el) {
  if (el.matches('[data-dialog-mount]')) {
    openDialogModal(el.querySelector('dialog'));
  } else if (el.matches('[data-open-dialog-on-swap]')) {
    openDialogModal(document.getElementById(el.getAttribute('data-open-dialog-on-swap')));
  } else if (el.matches('[data-show-dialog-on-swap]')) {
    openDialogModal(el.closest('dialog'));
  }
}

// Swap-driven: the in-app trigger's hx-target IS one of the recognized elements.
document.addEventListener('htmx:afterSwap', function (event) {
  const target = event.detail.target;
  if (target && target.matches) openDialogFor(target);
});

// Load-driven (direct navigation / shared link): the host page embeds the dialog
// inside the mount at load, so no swap targets the mount. htmx:load fires for the
// initial page (elt = page body) and for hx-boosted navigations (elt = the
// swapped-in page content); open any not-yet-open dialog that sits inside a mount
// within the freshly loaded subtree. Scoped to that subtree + idempotent, so it
// never reopens a dismissed dialog (whose mount is outside the loaded subtree).
// The swap-into-mount case is already handled above, so this only needs the mount
// convention, not the two legacy lazy-loaded ones.
document.addEventListener('htmx:load', function (event) {
  const root = event.detail && event.detail.elt;
  if (!root || !root.querySelectorAll) return;
  root.querySelectorAll('[data-dialog-mount] dialog').forEach(openDialogModal);
});

// ── Restore the list URL + remove a mount dialog when it closes ─────────────
// A server-sent dialog lands in [data-dialog-mount] and stays there after it
// closes (closeDialog / Cancel / ESC only .close() it). If it lingers, a later
// htmx:load whose subtree contains the mount would re-open the dismissed dialog
// (the load path can't use matches() the way the swap path does). Remove it on
// close so nothing can reopen it — for both the load and swap paths.
//
// URL-addressable dialog history (docs/dialog-forms.md): OPENING pushes the
// /…/new URL via the trigger's hx-push-url (htmx-native, so its boost snapshot
// stays consistent). CLOSING restores the dialog's data-close-url (the list URL)
// with history.replaceState — NOT pushState — so closing does not add a third
// history entry; the two states remain [list, /…/new] and Back returns to the
// list. Only replace when the current path actually differs, to avoid redundant
// history churn (e.g. a dialog closed on the list URL after Back already fired).
// Pressing Back is handled natively by htmx's boosted popstate/snapshot restore.
//
// Scoped to dialogs INSIDE the mount only: the legacy data-open-dialog-on-swap /
// data-show-dialog-on-swap dialogs live outside the mount and must not be
// removed (they are reused). Reveal (stay-open) dialogs never fire close, so
// they are unaffected. The <dialog> `close` event does not bubble → capture.
// CR7: opening a create dialog pushes the bare /…/new URL (hx-push-url), which
// drops any list filter/sort/paging query string that was in the address bar.
// Capture the full list URL when a mount-dialog trigger is clicked so the close
// listener below can put that query back when it restores the list URL.
let dialogReturnUrl = null;
document.addEventListener(
  'click',
  function (event) {
    const trigger =
      event.target.closest && event.target.closest('[hx-target="#dialog-mount"][hx-push-url]');
    if (trigger) dialogReturnUrl = window.location.href;
  },
  true,
);

document.addEventListener(
  'close',
  function (event) {
    const dialog = event.target;
    if (!dialog || !dialog.matches || !dialog.matches('dialog')) return;
    if (!dialog.closest('[data-dialog-mount]')) return;
    const closeUrl = dialog.getAttribute('data-close-url');
    if (closeUrl) {
      const target = new URL(closeUrl, window.location.origin);
      // data-close-url is the bare list path; the list's query string lives only in
      // the URL we captured at open time. Restore it when it's for the same path so
      // Cancel/ESC returns to the FILTERED list, not the unfiltered one (CR7).
      if (!target.search && dialogReturnUrl) {
        const captured = new URL(dialogReturnUrl, window.location.origin);
        if (captured.pathname === target.pathname) target.search = captured.search;
      }
      if (
        target.pathname !== window.location.pathname ||
        target.search !== window.location.search
      ) {
        history.replaceState(history.state, '', target.href);
      }
    }
    dialogReturnUrl = null;
    dialog.remove();
  },
  true,
);

// ── History restore: re-promote restored dialogs to modal ────────────────────
// htmx's history cache serialises `<dialog open>` but NOT showModal()'s
// top-layer state (backdrop, centering, ESC-to-close). On Back/Forward a mount
// dialog comes back open-but-NON-modal, and the openDialogModal guard skips it
// because it is already `open`. Re-promote it here so the backdrop/centering/ESC
// return. Use removeAttribute('open') — NOT dialog.close() — because close()
// fires the `close` event above, which removes the dialog from the mount before
// we can reopen it; removing the attribute is silent, then showModal() re-opens
// it modally. Scoped to mount dialogs (the ones we open modally in the first
// place); htmx:historyRestore fires after the snapshot DOM is in place.
document.addEventListener('htmx:historyRestore', function () {
  document.querySelectorAll('[data-dialog-mount] dialog[open]').forEach(function (dialog) {
    if (!dialog.matches(':modal')) {
      dialog.removeAttribute('open');
      dialog.showModal();
    }
  });
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
  const base = select.getAttribute('data-catalog-filter-base') || '';
  // Only navigate to a same-origin absolute path. Resolving through the URL API
  // against the current origin guarantees an http(s) URL; a base that isn't a
  // rooted path (e.g. a "javascript:"/"data:" scheme) is rejected before it can
  // reach the navigation sink.
  if (!base.startsWith('/')) return;
  const url = new URL(base, window.location.origin);
  if (select.value) url.searchParams.set('catalog', select.value);
  window.location.assign(url.href);
});

// ── Confirm-then-submit for plain (non-HTMX) forms ──────────────────────────
// Usage: <button data-confirm-submit[="form-id"] data-confirm-submit-message="…"
//                data-confirm-submit-title="…" data-confirm-submit-label="Delete"
//                [data-confirm-submit-class="ep-btn-destructive"]>
// Without a form id, submits the closest enclosing <form>.
document.addEventListener('click', function (event) {
  const button = event.target.closest && event.target.closest('[data-confirm-submit]');
  if (!button) return;
  const formId = button.getAttribute('data-confirm-submit');
  const form = formId ? document.getElementById(formId) : button.closest('form');
  if (!form) return;
  window
    .epistolaConfirm(button.getAttribute('data-confirm-submit-message') || 'Are you sure?', {
      title: button.getAttribute('data-confirm-submit-title') || 'Confirm',
      confirmLabel: button.getAttribute('data-confirm-submit-label') || 'Delete',
      confirmClass: button.getAttribute('data-confirm-submit-class') || 'ep-btn-destructive',
    })
    .then(function (ok) {
      if (ok) form.submit();
    });
});

// ── Copy input value to clipboard ────────────────────────────────────────────
// Usage: <button data-copy-source="input-id" data-copy-status="status-el-id">
document.addEventListener('click', function (event) {
  const button = event.target.closest && event.target.closest('[data-copy-source]');
  if (!button) return;
  const input = document.getElementById(button.getAttribute('data-copy-source'));
  const status = document.getElementById(button.getAttribute('data-copy-status'));
  if (!input) return;

  function done(ok) {
    if (status) {
      status.textContent = ok
        ? 'Copied to clipboard.'
        : 'Copy failed — select the value and copy it manually.';
    }
  }

  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(input.value).then(
      function () {
        done(true);
      },
      function () {
        done(false);
      },
    );
  } else {
    input.removeAttribute('readonly');
    input.select();
    let ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (e) {
      ok = false;
    }
    input.setAttribute('readonly', 'readonly');
    done(ok);
  }
});
