// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// New tenant: the optional catalog naming (ADR 0010: no executable inline scripts in templates).
//
// The section is optional: closed, its fields are disabled and not sent. Open, the namespace follows
// the tenant ID until someone types in it (`data-namespace-follows` names the field it follows), and a
// line under the section shows what the tenant's catalogs will be called.
// One delegated listener on `document`, so it works for the dialog however it arrived. It runs after
// slug-auto.js has filled the tenant ID from the name, since that listener sits on the field itself.

(function () {
  'use strict';

  function form(el) {
    return el && el.closest ? el.closest('form') : null;
  }

  function preview(f) {
    const line = f.querySelector('[data-naming-preview]');
    const namespace = f.querySelector('[data-namespace-follows]');
    const authority = f.querySelector('#authority');
    if (!line || !namespace || !authority) return;
    const ns = namespace.value.trim();
    const domain = authority.value.trim();
    line.hidden = ns === '';
    if (ns === '') return;
    line.textContent =
      'Its catalogs are named ' +
      ns +
      '/letters, ' +
      (domain === '' ? 'once you name the domain that grants it.' : 'granted by ' + domain + '.');
  }

  // A closed section counts as left empty: its fields are disabled, so they are not sent, and the
  // namespace is filled from the tenant ID only once the section is opened.
  function syncSection(details) {
    const f = form(details);
    if (!f) return;
    const open = details.open;
    details.querySelectorAll('input').forEach(function (input) {
      input.disabled = !open;
    });
    const namespace = f.querySelector('[data-namespace-follows]');
    if (
      open &&
      namespace &&
      namespace.dataset.namespaceEdited !== 'true' &&
      namespace.value === ''
    ) {
      const followed = f.querySelector('#' + namespace.getAttribute('data-namespace-follows'));
      if (followed) namespace.value = followed.value;
    }
    preview(f);
  }

  // `toggle` does not bubble: listen in the capture phase.
  document.addEventListener(
    'toggle',
    function (event) {
      const details = event.target;
      if (details && details.matches && details.matches('[data-tenant-naming]'))
        syncSection(details);
    },
    true,
  );
  document.addEventListener('htmx:load', function (event) {
    const root = event.detail && event.detail.elt;
    if (!root || !root.querySelectorAll) return;
    root.querySelectorAll('[data-tenant-naming]').forEach(syncSection);
  });

  document.addEventListener('input', function (event) {
    const target = event.target;
    const f = form(target);
    if (!f || !f.querySelector('[data-tenant-naming]')) return;
    const namespace = f.querySelector('[data-namespace-follows]');
    if (!namespace) return;

    if (target === namespace) {
      // Typed in by hand: it stops following the tenant ID, unless emptied again.
      namespace.dataset.namespaceEdited = namespace.value === '' ? '' : 'true';
    } else if (namespace.dataset.namespaceEdited !== 'true' && !namespace.disabled) {
      const followed = f.querySelector('#' + namespace.getAttribute('data-namespace-follows'));
      if (followed && (target === followed || target.id === 'name'))
        namespace.value = followed.value;
    }
    preview(f);
  });
})();
