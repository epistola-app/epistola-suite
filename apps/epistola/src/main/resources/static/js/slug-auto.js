// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

(function () {
  var ALLOWED_KEYS = ['Backspace', 'Delete', 'Enter', 'Escape', 'Tab', 'Home', 'End'];

  function nameToSlug(name) {
    return name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '');
  }

  function isValidSlugKey(key) {
    return key.length === 1 && /^[a-z0-9-]$/.test(key);
  }

  function wireup(nameInput) {
    if (nameInput.hasAttribute('data-slug-wired')) return;
    var slugId = nameInput.getAttribute('data-slug-source');
    var slugInput = document.getElementById(slugId);
    if (!slugInput) return;
    nameInput.setAttribute('data-slug-wired', '');

    var slugManuallyEdited =
      slugInput.value !== '' && slugInput.value !== nameToSlug(nameInput.value);

    nameInput.addEventListener('input', function () {
      if (!slugManuallyEdited) {
        slugInput.value = nameToSlug(nameInput.value);
      }
    });

    slugInput.addEventListener('input', function () {
      var cleaned = slugInput.value.replace(/[^a-z0-9-]/g, '');
      if (cleaned !== slugInput.value) {
        slugInput.value = cleaned;
      }
      slugManuallyEdited = slugInput.value !== nameToSlug(nameInput.value);
    });

    slugInput.addEventListener('keydown', function (e) {
      if (
        ALLOWED_KEYS.indexOf(e.key) !== -1 ||
        e.key.startsWith('Arrow') ||
        e.ctrlKey ||
        e.metaKey ||
        e.altKey
      ) {
        return;
      }

      if (!isValidSlugKey(e.key)) {
        e.preventDefault();
      }
    });
  }

  function scan(root) {
    if (root.matches && root.matches('[data-slug-source]')) wireup(root);
    if (root.querySelectorAll) {
      root.querySelectorAll('[data-slug-source]').forEach(wireup);
    }
  }

  // Loaded once from <head> (ADR 0010), so the body may not exist yet: defer
  // the initial scan and the MutationObserver (which wires inputs added later
  // by ANY means — HTMX swaps, hx-boost navigations, or plain DOM insertion)
  // until the DOM is ready. The data-slug-wired guard makes re-scans a no-op.
  function start() {
    scan(document.body);
    new MutationObserver(function (mutations) {
      mutations.forEach(function (mutation) {
        mutation.addedNodes.forEach(function (node) {
          if (node.nodeType === Node.ELEMENT_NODE) scan(node);
        });
      });
    }).observe(document.body, { childList: true, subtree: true });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }

  // History restore (Back/Forward): htmx's snapshot keeps the `data-slug-wired`
  // marker but NOT the per-element input/keydown listeners (those are not
  // serialisable), so wireup() would skip re-binding on the guard. Drop the
  // markers and re-scan so auto-slug works again on a restored page.
  document.addEventListener('htmx:historyRestore', function () {
    document.querySelectorAll('[data-slug-source][data-slug-wired]').forEach(function (el) {
      el.removeAttribute('data-slug-wired');
    });
    scan(document.body);
  });
})();
