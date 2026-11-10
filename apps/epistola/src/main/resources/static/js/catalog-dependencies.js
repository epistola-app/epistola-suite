// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Using another catalog's resource is a deliberate step: a picker offers the resources of catalogs
// the catalog does not declare apart, and choosing one asks the author to add the dependency first.
// Confirmed, it is declared (POST <catalog>/dependencies/declare) and the choice goes on; declined or
// refused, nothing changes.
(function () {
  'use strict';

  /**
   * Asks the author to make this catalog depend on `catalogName`, then declares `dependency` (an
   * address, `acme/shared`) at `declareUrl`. Resolves true once it is declared, false when the author
   * declined or it was refused (the refusal is shown).
   */
  window.epistolaDeclareDependency = async function (declareUrl, dependency, catalogName, what) {
    const subject = what ? '“' + what + '”' : 'something from ' + catalogName;
    const confirmed = await window.epistolaConfirm(
      'Using ' +
        subject +
        ' makes this catalog depend on ' +
        catalogName +
        '. It is added to the catalog’s dependencies.',
      { title: 'Add a dependency', confirmLabel: 'Add dependency', confirmClass: 'ep-btn-primary' },
    );
    if (!confirmed) return false;
    const response = await fetch(declareUrl, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/x-www-form-urlencoded',
        'X-XSRF-TOKEN': window.getCsrfToken(),
      },
      body: new URLSearchParams({ dependency: dependency }).toString(),
    }).catch(() => null);
    if (response && response.ok) return true;
    const problem = response ? await response.json().catch(() => ({})) : {};
    window.alert(problem.detail || 'The dependency could not be added.');
    return false;
  };

  // A <select> whose chosen option (or the select itself) names a catalog to declare
  // (`data-declares="acme/shared"`, `data-declares-name="Shared"`) within an element carrying
  // `data-declare-url`. Listening in the capture phase holds the change back from HTMX and forms
  // until the dependency is added; declined, the select shows its previous value again.
  const previous = new WeakMap();

  function remember(select) {
    previous.set(select, select.value);
  }

  document.addEventListener('focusin', (event) => {
    if (event.target instanceof HTMLSelectElement) remember(event.target);
  });

  document.addEventListener(
    'change',
    (event) => {
      const select = event.target;
      if (!(select instanceof HTMLSelectElement)) return;
      if (select.dataset.declaring === 'done') {
        delete select.dataset.declaring;
        remember(select);
        return;
      }
      const option = select.selectedOptions[0];
      const marked =
        option && option.dataset.declares
          ? option
          : select.value !== '' && select.dataset.declares
            ? select
            : null;
      const host = marked && select.closest('[data-declare-url]');
      if (!marked || !host) {
        remember(select);
        return;
      }
      event.stopImmediatePropagation();
      const dependency = marked.dataset.declares;
      window
        .epistolaDeclareDependency(
          host.dataset.declareUrl,
          dependency,
          marked.dataset.declaresName || dependency,
          option ? option.textContent.trim() : '',
        )
        .then((declared) => {
          if (!declared) {
            select.value = previous.has(select) ? previous.get(select) : '';
            return;
          }
          // Declared now: nothing of that catalog asks again.
          host
            .querySelectorAll('[data-declares="' + CSS.escape(dependency) + '"]')
            .forEach((el) => {
              delete el.dataset.declares;
            });
          select.dataset.declaring = 'done';
          select.dispatchEvent(new Event('change', { bubbles: true }));
        });
    },
    true,
  );
})();
