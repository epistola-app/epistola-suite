// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// The corner-notice subsystem (#477): the epistolaNotice API, insertion +
// keyed dedupe, the global error safety net, auto-dismiss/hover-pause, and
// the notices-above-modal-dialogs placement. Server-sent notices arrive as
// OOB swaps into #notices (HtmxDsl successNotice/errorNotice); client-built
// ones clone the shell's #notice-template, so markup stays single-sourced
// in the epistola-web/notice fragment. Overlay chrome lives in notice.css.

// Public client-side notice API for legit-fetch sites (PDF blobs, editor
// callbacks) that have no HTMX response for a server-sent notice to ride.
// Same rules as the Kotlin DSL: one short sentence, title ≤ a few words, and
// a failure belonging to a dialog's own action renders inside that dialog,
// not here (the version-comparison dialog in template-detail.js is the
// reference).
//
// options:
//   title      optional bold first line, mirroring the DSL's title param.
//   dedupeKey  identifies "the same failure recurring" (e.g. one failing
//              poll): a visible notice with the same key is refreshed in
//              place — message updated only if it changed (so aria-live does
//              not re-announce an identical one), dismiss timer extended —
//              instead of stacking a pile. Pick keys per failure source;
//              recurring background call sites should always pass one.
//
// Returns a handle { dismiss() } for retracting the notice early, or null
// when the page hosts no notice region.
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

// ── Global safety net for unhandled errors (#477) ───────────────────────────
// Every async failure a handler did not shape ends in a corner error notice,
// so silent failure is not a possible outcome on any page that runs htmx:
// standalone pages (own <body>, no shell) host their own #notices region pair
// (tenants list). The editor page routes async through the editor's own error
// channel; login and error pages have no async at all.

function showErrorNotice(message, dedupeKey) {
  insertNotice('error', message, { dedupeKey: dedupeKey });
}

// What identifies a recurring failure for the safety net's dedupe: the
// request path (same poll → same key) plus the failure mode.
function noticeKeyFor(kind, event) {
  const path = (event.detail.pathInfo && event.detail.pathInfo.requestPath) || '';
  return kind + ':' + path;
}

// The server responded 4xx/5xx and the response was not shaped for HTMX.
document.addEventListener('htmx:responseError', function (event) {
  const xhr = event.detail.xhr;
  if (!xhr) return;

  // Shaped error responses (HX-Reswap: none + OOB) already carry their own
  // message — nothing to add here.
  if (xhr.getResponseHeader('HX-Reswap')) return;

  // The confirm dialog reports failures in its own error area. The check must
  // use the issuing element (detail.elt, the form inside the dialog): the
  // dialog form's hx-target points outside the dialog, so detail.target
  // never matches.
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

  showErrorNotice(message, noticeKeyFor('error:' + xhr.status, event));
});

// The request never reached the server. The confirm dialog's form only
// handles responseError itself, so its network failures land in the dialog's
// own error area here — otherwise the dialog would look like nothing
// happened.
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

// ── Auto-dismiss + hover pause ──────────────────────────────────────────────
// Timing state lives in this map, swept by one shared interval that only
// runs while a notice is alive. Deadline moves (dedupe refresh, hover
// pause/resume) are plain field writes — no per-notice timer to cancel or
// rearm. dismissNotice deletes the entry and marks .notice-leaving in the
// same step, so a map entry always means a live notice.
const NOTICE_TIMEOUT_MS = 5000;
const NOTICE_TICK_MS = 250;

/** @type {Map<Element, {deadline: number, remaining: number|null, dedupeKey: string|null}>} */
const notices = new Map();
let noticeTicker = null;

function mountNotice(notice, dedupeKey) {
  notice.style.setProperty('--notice-timeout', NOTICE_TIMEOUT_MS + 'ms');
  notices.set(notice, {
    deadline: Date.now() + NOTICE_TIMEOUT_MS,
    remaining: null, // non-null while hover-paused: the leftover time
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
  // Reduced-motion sets animation:none, which never fires animationend.
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

// Hovering pauses auto-dismiss; leaving resumes the remaining time. The
// data-notice-hovered attribute is purely the CSS hook pausing the timer
// bar — paused CSS animations resume where they stopped on their own.
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

// Adopt server-sent notices: OOB swaps bypass insertNotice, and htmx:load
// fires for every batch of newly settled content, OOB insertions included.
// Notices only ever live inside #notices (server OOB targets it, insertNotice
// inserts into it, hoisting moves the region wholesale), so the scan stays
// scoped to the region instead of walking the whole document on every settle.
// The .notice-leaving check keeps a dismissed notice (out of the map, still
// animating out) from being re-adopted.
document.addEventListener('htmx:load', function () {
  const region = document.getElementById('notices');
  if (region) {
    region.querySelectorAll('[data-notice]').forEach(function (notice) {
      if (!notices.has(notice) && !notice.classList.contains('notice-leaving')) {
        mountNotice(notice, null);
      }
    });
  }
  syncNoticeRegionPlacement();
});

// ── Notices above modal dialogs (#477) ──────────────────────────────────────
// An open modal <dialog> paints in the top layer, over the fixed #notices
// region — a notice arriving would sit behind the ::backdrop, invisible.
// BOTH halves of the fix are load-bearing:
//
//   1. Move the region INTO the open dialog. The dialog's subtree is exempt
//      from showModal()'s inert, so the dismiss × stays clickable (a popover
//      left outside the dialog is painted but inert). #notices travels with
//      its id, so server OOB swaps (afterbegin:#notices) keep working.
//   2. Show it as a MANUAL popover. Re-entering the top layer after the
//      dialog paints the region above the dialog and its backdrop; `manual`
//      means no light-dismiss stealing clicks.
//
// In a browser without dialog ToggleEvents nothing engages and notices keep
// the behind-the-backdrop behavior; the normal no-dialog state never carries
// the popover attribute.

// Where the region belongs when no dialog is open. The parent can be gone
// after a body swap — document.body is a safe fallback anchor since the
// region is position:fixed (only the body:has() geometry rules care, and any
// body descendant satisfies them).
let noticeRegionHome = null;

// Open modals in OPENING order — DOM order can differ (the confirm dialog
// sits before the version-history dialog it stacks on top of).
const openModalStack = [];

// Reparenting restarts every CSS animation in the region: pin each live
// notice's bar back to its deadline (--notice-elapsed + negative delay in
// notice.css) and suppress the enter-slide replay. Leaving notices are not
// in the map and are skipped — their exit replays, and the dismiss fallback
// still removes them.
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

// Capture: ToggleEvents don't bubble.
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

// A swap that replaces the region's host dialog would destroy the region
// with it — move it home first; the htmx:load pass re-hoists if a modal is
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
// dialog (the editor's Lit dialogs) unmounts by direct DOM removal,
// destroying a hoisted region with it — and with no #notices left, every
// later notice silently no-ops. Watch for the region leaving the document
// and re-home it, live notices intact. Observation is scoped to the hoisted
// state (attached on hoist, disconnected on every move-home path), so the
// observer costs nothing in the steady state. A reparent also records a
// removal, but by callback time the region is connected again — the
// isConnected guard makes those a no-op.
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
