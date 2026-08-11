// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// The corner-notice subsystem (#477): the epistolaNotice API, notice
// insertion + keyed dedupe, the global error safety net, auto-dismiss
// timers, hover-pause, and the notices-above-modal-dialogs placement.
// Server-sent notices arrive as OOB swaps into the #notices region
// (epistola-web/notice via HtmxDsl successNotice/errorNotice); this file
// owns everything that happens to them client-side. Markup stays
// single-sourced in the epistola-web/notice fragment; chrome in notice.css.

// ── Global safety net for unhandled errors (#477) ───────────────
//
// Every async failure a handler did not shape ends in a corner error notice,
// so silent failure is not a possible outcome on shell pages (the few
// standalone pages have no #notices region — their async goes through the
// editor's own error channel or a form slot). The notice is cloned from the
// shell's #notice-template (no server HTML exists for these cases — cloning
// keeps the markup single-sourced in the epistola-web/notice fragment);
// insertNotice registers its auto-dismiss state directly, so it needs no
// htmx:load — none fires when nothing was swapped.

// Public client-side notice API — the JS mirror of the Kotlin DSL helpers
// (successNotice/errorNotice), for legit-fetch sites (PDF blobs, editor
// callbacks) that have no HTMX response for a server-sent notice to ride.
// Same component, same rules: one short sentence, title ≤ a few words.
// Notices stay visible above open modal dialogs (the modal-placement section
// below hosts the region inside the dialog), but the convention stands: a
// failure belonging to a dialog's own action renders inside that dialog,
// next to what the user did — not here (the version-comparison dialog in
// template-detail.js is the reference).
//
// options:
//   title      optional bold first line, mirroring the DSL's title param.
//   dedupeKey  identifies "the same failure recurring" (e.g. one failing
//              poll): a visible notice with the same key is refreshed in
//              place — message updated only if it changed (so aria-live does
//              not re-announce an identical one), dismiss timer extended —
//              instead of stacking a pile. Unrelated failures that happen to
//              share wording are NOT coalesced; pick keys per failure source.
//              Recurring background call sites (polls, periodic checks)
//              should always pass one.
//
// Returns a handle { dismiss() } for retracting the notice early (e.g. a
// standing network-error notice once connectivity returns), or null when the
// page hosts no notice region.
window.epistolaNotice = {
  success: function (message, options) {
    return insertNotice('success', message, options);
  },
  error: function (message, options) {
    return insertNotice('error', message, options);
  },
};

function insertNotice(kind, message, options) {
  const region = document.getElementById('notices');
  const template = document.getElementById('notice-template');
  if (!region || !template || !template.content.firstElementChild) return null;
  const opts = options || {};

  if (opts.dedupeKey) {
    const existing = liveNoticeByKey(opts.dedupeKey);
    if (existing) {
      const entry = notices.get(existing);
      const text = existing.querySelector('.notice-message');
      if (text.textContent !== message) text.textContent = message;
      entry.deadline = Date.now() + NOTICE_TIMEOUT_MS;
      if (entry.remaining !== null) entry.remaining = NOTICE_TIMEOUT_MS;
      restartNoticeTimerBar(existing);
      return noticeHandle(existing);
    }
  }

  const notice = template.content.firstElementChild.cloneNode(true);
  // The template ships error-flavored; other kinds swap the severity class
  // and announce politely instead of assertively.
  if (kind !== 'error') {
    notice.classList.remove('alert-error');
    notice.classList.add('alert-' + kind);
    notice.setAttribute('role', 'status');
  }
  // The template ships the title slot empty; fill it or drop it.
  const title = notice.querySelector('.alert-title');
  if (title) {
    if (opts.title) title.textContent = opts.title;
    else title.remove();
  }
  notice.querySelector('.notice-message').textContent = message;
  region.insertBefore(notice, region.firstChild);
  mountNotice(notice, opts.dedupeKey);
  syncNoticeRegionPlacement();
  return noticeHandle(notice);
}

function noticeHandle(notice) {
  return {
    dismiss: function () {
      dismissNotice(notice);
    },
  };
}

function showErrorNotice(message, dedupeKey) {
  insertNotice('error', message, { dedupeKey: dedupeKey });
}

// What identifies a recurring failure for showErrorNotice's dedupe: the
// request path (same poll → same key) plus the failure mode.
function noticeKeyFor(kind, event) {
  const path = (event.detail.pathInfo && event.detail.pathInfo.requestPath) || '';
  return kind + ':' + path;
}

// The server responded 4xx/5xx and the response was not shaped for HTMX.
document.addEventListener('htmx:responseError', function (event) {
  const xhr = event.detail.xhr;
  if (!xhr) return;

  // Shaped error responses already delivered their message via the OOB
  // swap above — nothing to add here.
  if (xhr.getResponseHeader('HX-Reswap')) return;

  // The confirm dialog already reports failures in its own error area
  // (openConfirmDialog's responseError listener), so the safety net must stay
  // out of it — a corner notice here would duplicate the in-dialog message.
  // The check must use the issuing element (detail.elt, the form inside the
  // dialog): the dialog form's hx-target always points outside the dialog,
  // so detail.target never matches.
  const sourceElt = event.detail.elt;
  if (sourceElt && sourceElt.closest && sourceElt.closest('#confirm-dialog')) return;

  let detail;
  try {
    const body = JSON.parse(xhr.responseText);
    detail = body.detail || body.error;
  } catch {
    detail = null;
  }

  let message;
  if (xhr.status === 403) {
    message = detail || "You don't have permission to perform this action.";
  } else if (xhr.status >= 500) {
    message = 'An unexpected error occurred. Please try again.';
  } else {
    message = detail || 'The request failed. Please try again.';
  }

  // Prefer the issuing form's global error slot — inline, next to the inputs.
  const sourceForm =
    event.detail.elt && event.detail.elt.closest ? event.detail.elt.closest('form') : null;
  const slot = sourceForm ? sourceForm.querySelector('[data-form-error]') : null;
  if (slot) {
    slot.textContent = message;
    slot.hidden = false;
    return;
  }

  // Non-form failure (standalone controls, row actions): corner error notice.
  // Replaces the old top-of-page banner, and covers every status — the banner
  // only showed 403/5xx and silently dropped other client errors.
  showErrorNotice(message, noticeKeyFor('error:' + xhr.status, event));
});

// The request never reached the server — there is no response to speak for it.
// If the request came from the open confirm dialog, the message renders in the
// dialog's own error area, next to the button the user just clicked — the
// dialog form only handles responseError itself, so a network failure would
// otherwise leave the dialog looking like nothing happened (a dialog reports
// its own request's failures in-dialog; the corner is for everything else).
document.addEventListener('htmx:sendError', function (event) {
  const message = 'Network error — check your connection and try again.';
  const sourceElt = event.detail.elt;
  if (sourceElt && sourceElt.closest && sourceElt.closest('#confirm-dialog')) {
    const errorEl = document.getElementById('confirm-dialog-error');
    if (errorEl) {
      errorEl.textContent = message;
      errorEl.style.display = 'block';
      return;
    }
  }
  showErrorNotice(message, noticeKeyFor('network', event));
});

// The response arrived but swapping it into the page failed.
document.addEventListener('htmx:swapError', function (event) {
  showErrorNotice(
    'Something went wrong displaying the response. Reload the page.',
    noticeKeyFor('swap', event),
  );
});

// ── Corner notices (#477): auto-dismiss + early dismiss ─────────────────────
// Server-sent feedback fragments (epistola-web/notice) arrive as OOB swaps
// into the fixed #notices region; client-built ones come from insertNotice
// above. Every notice auto-dismisses after a uniform timeout; the × button
// (data-notice-dismiss) closes early. Both paths exit via .notice-leaving
// (slide-out in notice.css) and remove on animationend — the timeout fallback
// covers reduced-motion, where animation:none never fires the event.
//
// Timing state lives HERE, in one Map keyed by notice element, not in data-*
// attributes on the notice. One shared interval sweeps the map while any
// notice is alive; when the map empties, the interval stops. That makes every
// state change a plain field write — a dedupe refresh bumps entry.deadline,
// hover-pause parks the leftover time in entry.remaining — with no per-notice
// timer handle to track, cancel, or rearm, and no custom events between
// insertion and timing. dismissNotice removes the entry and marks the element
// .notice-leaving in the same breath, so a map entry always means "live
// notice" (data-notice-hovered stays on the element purely as the CSS hook
// that pauses the timer bar).
const NOTICE_TIMEOUT_MS = 5000;
const NOTICE_TICK_MS = 250;

/** @type {Map<Element, {deadline: number, remaining: number|null, dedupeKey: string|null}>} */
const notices = new Map();
let noticeTicker = null;

function mountNotice(notice, dedupeKey) {
  notice.style.setProperty('--notice-timeout', NOTICE_TIMEOUT_MS + 'ms');
  notices.set(notice, {
    deadline: Date.now() + NOTICE_TIMEOUT_MS,
    remaining: null,
    dedupeKey: dedupeKey || null,
  });
  if (noticeTicker === null) noticeTicker = setInterval(sweepNotices, NOTICE_TICK_MS);
}

function sweepNotices() {
  const now = Date.now();
  for (const [notice, entry] of notices) {
    if (!notice.isConnected) notices.delete(notice);
    else if (entry.remaining === null && now >= entry.deadline) dismissNotice(notice);
  }
  if (notices.size === 0) {
    clearInterval(noticeTicker);
    noticeTicker = null;
  }
}

function liveNoticeByKey(dedupeKey) {
  for (const [notice, entry] of notices) {
    if (entry.dedupeKey === dedupeKey && notice.isConnected) return notice;
  }
  return null;
}

function dismissNotice(notice) {
  if (notice.classList.contains('notice-leaving')) return;
  notices.delete(notice);
  notice.style.animation = ''; // clear the reparent suppression so leave plays
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

// Restart the remaining-time bar (notice.css ::after) from full — used
// wherever the deadline resets so the two stay in sync.
function restartNoticeTimerBar(notice) {
  notice.classList.add('notice-timer-reset');
  void notice.offsetWidth;
  notice.classList.remove('notice-timer-reset');
}

// Hovering pauses auto-dismiss; leaving resumes the remaining time. The bar
// resumes on its own — paused CSS animations continue where they stopped.
document.addEventListener('mouseover', function (event) {
  const notice = event.target.closest && event.target.closest('[data-notice]');
  const entry = notice && notices.get(notice);
  if (entry && entry.remaining === null) {
    notice.setAttribute('data-notice-hovered', '');
    entry.remaining = Math.max(0, entry.deadline - Date.now());
  }
});

document.addEventListener('mouseout', function (event) {
  const notice = event.target.closest && event.target.closest('[data-notice]');
  if (!notice || (event.relatedTarget && notice.contains(event.relatedTarget))) return;
  const entry = notices.get(notice);
  if (entry && entry.remaining !== null) {
    notice.removeAttribute('data-notice-hovered');
    entry.deadline = Date.now() + entry.remaining;
    entry.remaining = null;
  }
});

// Server-sent notices (OOB swaps) enter the DOM without passing through
// insertNotice. htmx:load fires for every batch of newly settled content
// (incl. OOB insertions), so adopt whatever the map doesn't know yet — the
// map itself is the "already mounted" guard for client-built notices, and
// the .notice-leaving check keeps a dismissed notice (out of the map, still
// animating out) from being re-adopted.
document.addEventListener('htmx:load', function () {
  document.querySelectorAll('[data-notice]').forEach(function (notice) {
    if (!notices.has(notice) && !notice.classList.contains('notice-leaving')) {
      mountNotice(notice, null);
    }
  });
  syncNoticeRegionPlacement();
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
// 2024; capture — they don't bubble) plus the htmx:load adoption pass above
// as a safety net. In a browser without dialog toggle events nothing engages
// and notices simply keep the behind-the-backdrop behavior. The region's
// normal (no-dialog) state is completely untouched: no popover attribute,
// plain fixed positioning.

// Where the region belongs when no dialog is open; captured before the first
// hoist. The parent can be gone after a body swap — document.body is a safe
// fallback anchor since the region is position:fixed (placement only matters
// for the body:has() geometry rules, which any body descendant satisfies).
let noticeRegionHome = null;

// Open modals in OPENING order — DOM order can differ (the confirm dialog
// sits before the version-history dialog it stacks on top of).
const openModalStack = [];

// Reparenting restarts every CSS animation in the region: pin each live
// notice's bar back to its deadline (--notice-elapsed + negative delay in
// notice.css) and suppress the enter-slide replay (dismissNotice clears the
// inline suppression so the leave animation still plays). Leaving notices
// are not in the map and are skipped — their exit animation replays from
// the top, and the dismiss fallback still removes them.
function reanchorNoticeAnimations(region) {
  region.querySelectorAll('[data-notice]').forEach(function (notice) {
    const entry = notices.get(notice);
    if (!entry) return;
    notice.style.animation = 'none';
    const remaining = entry.remaining !== null ? entry.remaining : entry.deadline - Date.now();
    const elapsed = Math.min(NOTICE_TIMEOUT_MS, NOTICE_TIMEOUT_MS - Math.max(0, remaining));
    notice.style.setProperty('--notice-elapsed', elapsed + 'ms');
  });
}

function syncNoticeRegionPlacement() {
  const region = document.getElementById('notices');
  if (!region || typeof region.showPopover !== 'function') return;

  for (let i = openModalStack.length - 1; i >= 0; i--) {
    const d = openModalStack[i];
    if (!d.isConnected || !d.matches(':modal')) openModalStack.splice(i, 1);
  }
  const host = openModalStack[openModalStack.length - 1] || null;

  if (host) {
    if (region.parentElement === host) return;
    if (!noticeRegionHome) {
      noticeRegionHome = { parent: region.parentNode, next: region.nextSibling };
    }
    if (region.matches(':popover-open')) region.hidePopover();
    host.appendChild(region);
    region.setAttribute('popover', 'manual');
    region.showPopover();
    reanchorNoticeAnimations(region);
    hoistedRegionObserver.observe(document.documentElement, { childList: true, subtree: true });
  } else if (region.hasAttribute('popover')) {
    hoistedRegionObserver.disconnect();
    if (region.matches(':popover-open')) region.hidePopover();
    region.removeAttribute('popover');
    if (noticeRegionHome && noticeRegionHome.parent.isConnected) {
      noticeRegionHome.parent.insertBefore(region, noticeRegionHome.next);
    } else {
      document.body.appendChild(region);
    }
    reanchorNoticeAnimations(region);
  }
}

document.addEventListener(
  'toggle',
  function (event) {
    // <details> and popovers fire toggle too — only dialogs drive placement.
    const dialog = event.target;
    if (!(dialog instanceof HTMLDialogElement)) return;
    if (event.newState === 'open' && dialog.matches(':modal') && !openModalStack.includes(dialog)) {
      openModalStack.push(dialog);
    }
    syncNoticeRegionPlacement();
  },
  true,
);

// A swap that replaces the region's host dialog would destroy the region with
// it — move it home first; the htmx:load adoption pass re-hoists if a modal is
// still open afterwards.
document.addEventListener('htmx:beforeSwap', function (event) {
  const region = document.getElementById('notices');
  const target = event.detail.target;
  if (!region || !target || !region.hasAttribute('popover')) return;
  if (target !== region && target.contains(region)) {
    hoistedRegionObserver.disconnect();
    if (region.matches(':popover-open')) region.hidePopover();
    region.removeAttribute('popover');
    const home = noticeRegionHome && noticeRegionHome.parent.isConnected ? noticeRegionHome : null;
    if (home) home.parent.insertBefore(region, home.next);
    else document.body.appendChild(region);
  }
});

// The htmx rescue above cannot see non-htmx removals: a framework-rendered
// dialog (the editor's Lit dialogs) unmounts by direct DOM removal, destroying
// a hoisted region with it — and with no #notices left, every later notice
// silently no-ops. While the region is hoisted, watch for it leaving the
// document and re-home it, live notices intact. Observation is scoped to the
// hoisted state (attached on hoist, disconnected on every move-home path):
// at home the region is a direct body child only an htmx body swap — which
// ships a fresh region — can remove, so the observer costs nothing in the
// steady state. A reparent also records a removal, but by callback time the
// region is connected again, so the isConnected guard makes those a no-op.
const hoistedRegionObserver = new MutationObserver(function (mutations) {
  if (document.getElementById('notices')) return;
  for (const mutation of mutations) {
    for (const node of mutation.removedNodes) {
      if (!(node instanceof Element)) continue;
      const region = node.id === 'notices' ? node : node.querySelector('#notices');
      if (!region || region.isConnected) continue;
      hoistedRegionObserver.disconnect();
      region.removeAttribute('popover');
      if (noticeRegionHome && noticeRegionHome.parent.isConnected) {
        noticeRegionHome.parent.insertBefore(region, noticeRegionHome.next);
      } else {
        document.body.appendChild(region);
      }
      reanchorNoticeAnimations(region);
      syncNoticeRegionPlacement();
      return;
    }
  }
});
