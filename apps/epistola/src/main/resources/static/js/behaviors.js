// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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

// ── Corner notices (#477): auto-dismiss + early dismiss ─────────────────────
// Server-sent feedback fragments (epistola-web/notice) arrive as OOB swaps
// into the fixed #notices region. Every notice auto-dismisses after a uniform
// timeout; the × button (data-notice-dismiss) closes early. Both paths exit
// via .notice-leaving (slide-out in notice.css) and remove on animationend —
// the timeout fallback covers reduced-motion, where animation:none never
// fires the event. htmx:load fires for every batch of newly settled content
// (incl. OOB insertions), so the timer scan is guarded per notice by
// data-notice-mounted.
const NOTICE_TIMEOUT_MS = 5000;

function dismissNotice(notice) {
  if (notice.classList.contains('notice-leaving')) return;
  notice.classList.add('notice-leaving');
  const remove = function () {
    notice.remove();
  };
  notice.addEventListener('animationend', remove, { once: true });
  setTimeout(remove, 300);
}

document.addEventListener('click', function (event) {
  const dismiss = event.target.closest && event.target.closest('[data-notice-dismiss]');
  if (dismiss) {
    const notice = dismiss.closest('[data-notice]');
    if (notice) dismissNotice(notice);
  }
});

// The auto-dismiss timer is deadline-based rather than clearTimeout/reset so
// a deduped notice can be kept alive: the safety net (app-shell.js) refreshes
// a recurring failure's notice via epistola:notice-refresh, which only bumps
// data-notice-deadline; the timer re-checks on fire and reschedules the
// remainder. Refreshes are race-free writes to one attribute — no timer
// handle to track across N refreshes, no cancel/rearm window — and the
// notice dismisses NOTICE_TIMEOUT_MS after the LAST occurrence, not the first.
function scheduleNoticeDismiss(notice, delay) {
  setTimeout(function () {
    if (!notice.isConnected) return;
    const remaining = Number(notice.dataset.noticeDeadline) - Date.now();
    if (remaining > 0) scheduleNoticeDismiss(notice, remaining);
    else dismissNotice(notice);
  }, delay);
}

document.addEventListener('epistola:notice-refresh', function (event) {
  const notice = event.target.closest && event.target.closest('[data-notice]');
  if (notice) notice.dataset.noticeDeadline = String(Date.now() + NOTICE_TIMEOUT_MS);
});

// htmx:load covers server-sent notices (OOB swaps); epistola:notice-added is
// dispatched by the app-shell.js safety net for client-cloned ones, where no
// swap happened and htmx:load never fires.
['htmx:load', 'epistola:notice-added'].forEach(function (eventName) {
  document.addEventListener(eventName, function () {
    document
      .querySelectorAll('[data-notice]:not([data-notice-mounted])')
      .forEach(function (notice) {
        notice.setAttribute('data-notice-mounted', '');
        notice.dataset.noticeDeadline = String(Date.now() + NOTICE_TIMEOUT_MS);
        scheduleNoticeDismiss(notice, NOTICE_TIMEOUT_MS);
      });
    syncNoticeRegionPlacement();
  });
});

// ── Notices above modal dialogs (#477) ──────────────────────────────────────
//
// An open modal <dialog> paints in the browser TOP LAYER, over the fixed
// #notices region — a notice arriving while a dialog is open would sit behind
// the ::backdrop, invisible until the dialog closes. The fix is the documented
// platform pattern for toasts-over-modals, and BOTH halves are load-bearing:
//
//   1. Move the region INTO the open dialog. The dialog's subtree is exempt
//      from showModal()'s inert, so the dismiss × stays clickable (a popover
//      left outside the dialog is painted but inert). #notices travels with
//      its id, so server OOB swaps (afterbegin:#notices) keep working.
//   2. Show it as a MANUAL popover. Re-entering the top layer after the
//      dialog paints the region above the dialog and its backdrop; `manual`
//      means no light-dismiss stealing clicks.
//
// Placement is synced from the dialog `toggle` events (ToggleEvent, baseline
// 2024; capture — they don't bubble) plus the notice mount scan above as a
// safety net. In a browser without dialog toggle events nothing engages and
// notices simply keep the behind-the-backdrop behavior. The region's normal
// (no-dialog) state is completely untouched: no popover attribute, plain
// fixed positioning.

// Where the region belongs when no dialog is open; captured before the first
// hoist. The parent can be gone after a body swap — document.body is a safe
// fallback anchor since the region is position:fixed (placement only matters
// for the body:has() geometry rules, which any body descendant satisfies).
let noticeRegionHome = null;

function syncNoticeRegionPlacement() {
  const region = document.getElementById('notices');
  if (!region || typeof region.showPopover !== 'function') return;

  const openModals = Array.prototype.filter.call(
    document.querySelectorAll('dialog[open]'),
    function (d) {
      return d.matches(':modal');
    },
  );
  const host = openModals[openModals.length - 1] || null;

  if (host) {
    if (region.parentElement === host) return;
    if (!noticeRegionHome) {
      noticeRegionHome = { parent: region.parentNode, next: region.nextSibling };
    }
    if (region.matches(':popover-open')) region.hidePopover();
    host.appendChild(region);
    region.setAttribute('popover', 'manual');
    region.showPopover();
  } else if (region.hasAttribute('popover')) {
    if (region.matches(':popover-open')) region.hidePopover();
    region.removeAttribute('popover');
    if (noticeRegionHome && noticeRegionHome.parent.isConnected) {
      noticeRegionHome.parent.insertBefore(region, noticeRegionHome.next);
    } else {
      document.body.appendChild(region);
    }
  }
}

document.addEventListener(
  'toggle',
  function (event) {
    // <details> and popovers fire toggle too — only dialogs drive placement.
    if (event.target instanceof HTMLDialogElement) syncNoticeRegionPlacement();
  },
  true,
);

// A swap that replaces the region's host dialog would destroy the region with
// it — move it home first; the htmx:load mount scan re-hoists if a modal is
// still open afterwards.
document.addEventListener('htmx:beforeSwap', function (event) {
  const region = document.getElementById('notices');
  const target = event.detail.target;
  if (!region || !target || !region.hasAttribute('popover')) return;
  if (target !== region && target.contains(region)) {
    if (region.matches(':popover-open')) region.hidePopover();
    region.removeAttribute('popover');
    const home = noticeRegionHome && noticeRegionHome.parent.isConnected ? noticeRegionHome : null;
    if (home) home.parent.insertBefore(region, home.next);
    else document.body.appendChild(region);
  }
});
