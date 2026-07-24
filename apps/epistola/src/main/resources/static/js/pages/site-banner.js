// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Site banner dismissal (ADR 0010: no executable inline scripts in templates).
//
// The installation-wide banner is dismissible per browser session. We remember
// the dismissed banner's content hash in sessionStorage, so:
//   - within a session, a dismissed banner stays hidden across page swaps/reloads;
//   - a new or edited banner (different hash) re-appears even if a prior one was
//     dismissed;
//   - a new browser session shows it again.
//
// Listeners are installed once on `document` (HTMX events bubble), so this works
// for the initial page and for hx-boost body swaps alike.

(function () {
  'use strict';

  const STORAGE_KEY = 'epistola.dismissedBanner';

  function dismissedHash() {
    try {
      return sessionStorage.getItem(STORAGE_KEY);
    } catch (e) {
      return null; // storage may be blocked; treat as not dismissed
    }
  }

  function applyDismissState(root) {
    if (!root.querySelectorAll) return;
    const banners = Array.prototype.slice.call(root.querySelectorAll('[data-site-banner]'));
    if (root.matches && root.matches('[data-site-banner]')) banners.unshift(root);
    const hash = dismissedHash();
    banners.forEach(function (banner) {
      if (hash && banner.getAttribute('data-banner-hash') === hash) {
        banner.hidden = true;
      }
    });
  }

  document.addEventListener('htmx:load', function (event) {
    applyDismissState(event.detail.elt);
  });

  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;
    const button = event.target.closest('[data-dismiss-banner]');
    if (!button) return;
    const banner = button.closest('[data-site-banner]');
    if (!banner) return;
    try {
      sessionStorage.setItem(STORAGE_KEY, banner.getAttribute('data-banner-hash') || '');
    } catch (e) {
      /* storage blocked; still hide for this view */
    }
    banner.hidden = true;
  });
})();
