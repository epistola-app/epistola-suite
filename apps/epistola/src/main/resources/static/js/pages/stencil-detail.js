// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Stencil detail page behaviors (templates/stencils/detail.html).
// ADR 0010: no executable inline scripts in templates.
//
// The usage table (#usage-list) is an HTMX fragment that is re-swapped on
// filter/page changes and after upgrades, so everything here is delegated on
// `document` and reads its context from data-* attributes rendered into the
// fragment:
// - #usage-state carries data-page / data-filter / data-usage-url so a refresh
//   re-fetches the same view.
// - The apply button ([data-apply-upgrade]) carries data-upgrade-url, the POST
//   endpoint for a single template/variant upgrade.

(function () {
  'use strict';

  // Cap a single bulk upgrade — each row is an individual request, so a huge
  // selection would fire hundreds of sequential POSTs.
  const STENCIL_UPGRADE_MAX_BATCH = 100;

  // Non-null while a bulk upgrade is running; the apply button then acts as a
  // Cancel button and aborts it.
  let stencilUpgradeAbortController = null;

  // ── Select-all checkbox for upgradable usage rows ────────────────────────────
  // Usage: <input type="checkbox" data-select-all-usage>
  document.addEventListener('change', function (event) {
    const box = event.target.closest && event.target.closest('[data-select-all-usage]');
    if (!box) return;
    document.querySelectorAll('.usage-select:not(:disabled)').forEach(function (cb) {
      cb.checked = box.checked;
    });
  });

  function refreshUsageList() {
    if (!window.htmx) return;
    // Preserve the current filter + page so the refresh stays in place.
    const st = document.getElementById('usage-state');
    if (!st) return;
    const page = st.dataset.page || '1';
    const filter = st.dataset.filter || 'both';
    const url = st.dataset.usageUrl + '?page=' + page + '&filter=' + encodeURIComponent(filter);
    htmx.ajax('GET', url, { target: '#usage-list', swap: 'innerHTML' });
  }

  async function applyStencilUpgrade(button) {
    const version = parseInt(document.getElementById('upgrade-version-select').value);
    const status = document.getElementById('upgrade-status');
    // Selection is per variant: the server marks exactly one row per
    // variant as upgradable (its draft, else its latest published).
    const selected = [...document.querySelectorAll('.usage-select:checked')];
    if (selected.length === 0) {
      status.textContent = 'No rows selected';
      return;
    }
    if (selected.length > STENCIL_UPGRADE_MAX_BATCH) {
      status.textContent = `Select at most ${STENCIL_UPGRADE_MAX_BATCH} templates to upgrade at once (${selected.length} selected).`;
      return;
    }

    const versionLabel =
      document.getElementById('upgrade-version-select').selectedOptions[0].textContent;
    if (
      !(await epistolaConfirm(
        `This will upgrade ${selected.length} template(s) to stencil ${versionLabel}.\n\nPublished templates are upgraded in a new draft — their live version stays unchanged until you publish that draft. Existing drafts are modified in place.\n\nContinue?`,
        {
          title: 'Upgrade Stencil',
          confirmLabel: 'Upgrade',
          confirmClass: 'ep-btn-primary',
        },
      ))
    ) {
      return;
    }

    const upgradeUrl = button.getAttribute('data-upgrade-url');
    stencilUpgradeAbortController = new AbortController();

    button.textContent = 'Cancel';
    button.classList.remove('ep-btn-primary');
    button.classList.add('ep-btn-outline');
    status.textContent = '';

    let success = 0,
      failed = 0,
      cancelled = false;
    for (const cb of selected) {
      if (stencilUpgradeAbortController.signal.aborted) {
        cancelled = true;
        break;
      }
      const templateId = cb.dataset.templateId;
      const variantId = cb.dataset.variantId;
      // The template's own catalog — not the stencil's catalog (the one in the
      // URL). Usage spans multiple catalogs, so this must be the per-row value
      // the handler resolves the template in.
      const catalogKey = cb.dataset.catalogKey;
      try {
        const resp = await fetch(upgradeUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
          body: JSON.stringify({ templateId, variantId, catalogKey, newVersion: version }),
          signal: stencilUpgradeAbortController.signal,
        });
        if (resp.ok) {
          cb.closest('tr').style.opacity = '0.5';
          cb.checked = false;
          cb.disabled = true;
          success++;
        } else {
          failed++;
        }
      } catch (e) {
        if (e.name === 'AbortError') {
          cancelled = true;
          break;
        }
        failed++;
      }
      status.textContent = `${success + failed} / ${selected.length}...`;
    }

    button.textContent = 'Apply to selected';
    button.classList.remove('ep-btn-outline');
    button.classList.add('ep-btn-primary');
    stencilUpgradeAbortController = null;

    const parts = [];
    if (success > 0) parts.push(`${success} upgraded`);
    if (failed > 0) parts.push(`${failed} failed`);
    if (cancelled) parts.push('cancelled');
    status.textContent = parts.length > 0 ? parts.join(', ') : '';

    // Reload the table so newly created drafts (from upgrading published
    // templates) and updated stencil versions are reflected accurately.
    if (success > 0) refreshUsageList();
  }

  // ── Apply upgrade / cancel in-flight run ─────────────────────────────────────
  // Usage: <button data-apply-upgrade th:attr="data-upgrade-url=@{…/upgrade}">
  document.addEventListener('click', function (event) {
    const button = event.target.closest && event.target.closest('[data-apply-upgrade]');
    if (!button) return;
    if (stencilUpgradeAbortController) {
      stencilUpgradeAbortController.abort();
      return;
    }
    applyStencilUpgrade(button);
  });
})();
