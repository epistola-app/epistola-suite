// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Page behaviors for the template detail pages (ADR 0010: no executable
// inline scripts in templates).
//
// Loaded once globally in the page <head>; every handler is delegated on
// `document` so it works for content present at load AND content swapped in
// later by HTMX (the shell uses hx-boost, so page navigation is a body swap).
//
// Hooks (declared by templates/templates/**):
//   [data-template-name-input]   settings: keyboard UX for the rename input (Enter
//                                commits, Escape reverts); the rename itself is a
//                                native hx-patch in the template
//   [data-confirm-submit]        handled by /js/behaviors.js (confirm-then-submit)
//   [data-add-attr-button]       variant dialogs: reveals the [data-attr-row] chosen in
//                                the sibling [data-add-attr-select] within the closest
//                                [data-attr-rows] scope
//   select[data-filter-key]      variants tab (#variant-filter-bar): attribute filter
//                                for the variant card grid
//   [data-contract-editor]       data-contract tab: mounts the data-contract editor on
//                                htmx:load (idempotent via data-editor-mounted)
//   [data-pdf-preview]           handled by /js/pdf-preview.js
(function () {
  'use strict';

  function csrfToken() {
    return typeof window.getCsrfToken === 'function' ? window.getCsrfToken() : '';
  }

  // ── Settings: template name keyboard UX ───────────────────────────────────
  // The rename itself is a native HTMX control (hx-patch on change, see
  // templates/detail/settings.html). This only adds keyboard affordances:
  // Enter commits (blur fires the change event), Escape reverts to the
  // server-rendered value (defaultValue = the value attribute) — reverted
  // means no change event, so no request.
  document.addEventListener('keydown', function (event) {
    const input = event.target.closest && event.target.closest('[data-template-name-input]');
    if (!input) return;
    if (event.key === 'Enter') input.blur();
    if (event.key === 'Escape') {
      input.value = input.defaultValue;
      input.blur();
    }
  });

  // The settings theme select and PDF/A toggle are native HTMX controls
  // (hx-patch in templates/detail/settings.html) — no JS here.

  // ── Variant dialogs: "Add attribute" picker ────────────────────────────────
  // Works for both the create dialog (rendered with the page) and the edit
  // dialog (HTMX-loaded) because the handler is delegated.
  document.addEventListener('click', function (event) {
    const button = event.target.closest && event.target.closest('[data-add-attr-button]');
    if (!button) return;
    const scope = button.closest('[data-attr-rows]');
    if (!scope) return;
    const select = scope.querySelector('[data-add-attr-select]');
    if (!select) return;
    const key = select.value;
    if (!key) return;
    const row = scope.querySelector('[data-attr-row="' + CSS.escape(key) + '"]');
    if (!row) return;
    row.style.display = '';
    const field = row.querySelector('select, input');
    if (field) field.focus();
    // Remove the option from the picker so it can't be added twice.
    const opt = select.querySelector('option[value="' + CSS.escape(key) + '"]');
    if (opt) opt.remove();
    select.value = '';
  });

  // ── Variants tab: attribute filter for the card grid ──────────────────────
  function filterVariants() {
    const filterBar = document.getElementById('variant-filter-bar');
    if (!filterBar) return;
    const filters = {};
    filterBar.querySelectorAll('select[data-filter-key]').forEach(function (s) {
      if (s.value) filters[s.dataset.filterKey] = s.value;
    });
    const cards = document.querySelectorAll('.variant-card');
    let visible = 0;
    cards.forEach(function (card) {
      const attrs = {};
      card.querySelectorAll('[data-attr-key]').forEach(function (b) {
        attrs[b.dataset.attrKey] = b.dataset.attrValue;
      });
      let match = true;
      for (const k in filters) {
        if (attrs[k] !== filters[k]) {
          match = false;
          break;
        }
      }
      card.style.display = match ? '' : 'none';
      if (match) visible++;
    });
    const nm = document.getElementById('variant-no-matches');
    if (nm) nm.style.display = visible === 0 && cards.length > 0 ? 'block' : 'none';
  }

  document.addEventListener('change', function (event) {
    const select =
      event.target.closest && event.target.closest('#variant-filter-bar select[data-filter-key]');
    if (select) filterVariants();
  });

  // Re-apply the active filters after the variant grid is swapped (mark ready,
  // set-default, delete all target #variants-section).
  document.addEventListener('htmx:afterSwap', function (event) {
    if (event.detail.target && event.detail.target.id === 'variants-section') filterVariants();
  });

  // ── Data-contract tab: mount the data-contract editor ─────────────────────
  function parseJsonScript(id, fallback) {
    const el = document.getElementById(id);
    if (!el) return fallback;
    try {
      return JSON.parse(el.textContent);
    } catch (e) {
      return fallback;
    }
  }

  async function mountContractEditor(container) {
    if (container.dataset.editorMounted === 'true') return;
    const tenantId = container.dataset.tenantId;
    const catalogId = container.dataset.catalogId;
    const templateId = container.dataset.templateId;
    const readonly = container.dataset.readonly === 'true';
    if (!tenantId || !catalogId || !templateId) return;
    container.dataset.editorMounted = 'true';

    const initialSchema = parseJsonScript('data-contract-schema', null);
    const initialExamples = parseJsonScript('data-contract-examples', []);
    const { mountDataContractEditor } = await import('/editor/data-contract-editor.js');

    // The template's one data contract, edited in place (PATCH).
    const contractUrl = `/tenants/${tenantId}/templates/${catalogId}/${templateId}/contract`;
    const jsonHeaders = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() };

    mountDataContractEditor({
      container: container,
      templateId: templateId,
      initialSchema: initialSchema,
      initialExamples: initialExamples,
      readonly: readonly,
      saveControlsContainer: document.getElementById('contract-save-controls'),
      validationAlertContainer: document.getElementById('contract-validation-alert'),
      callbacks: readonly
        ? {}
        : {
            onSaveSchema: async (schema, forceUpdate, dataExamples) => {
              try {
                const body = { dataModel: schema, forceUpdate };
                if (dataExamples) body.dataExamples = dataExamples;
                const response = await fetch(contractUrl, {
                  method: 'PATCH',
                  headers: jsonHeaders,
                  body: JSON.stringify(body),
                });
                if (!response.ok) {
                  const err = await response.json();
                  return {
                    success: false,
                    error: err.detail ?? err.message,
                    warnings: err.warnings,
                    errors: err.errors,
                  };
                }
                const result = await response.json();
                return { success: true, warnings: result.warnings };
              } catch (e) {
                return { success: false, error: e.message };
              }
            },
            onSaveDataExamples: async (examples) => {
              try {
                const response = await fetch(contractUrl, {
                  method: 'PATCH',
                  headers: jsonHeaders,
                  body: JSON.stringify({ dataExamples: examples }),
                });
                if (!response.ok) {
                  const err = await response.json();
                  return {
                    success: false,
                    error: err.detail ?? err.message,
                    warnings: err.warnings,
                    errors: err.errors,
                  };
                }
                const result = await response.json();
                return { success: true, warnings: result.warnings };
              } catch (e) {
                return { success: false };
              }
            },
            onUpdateDataExample: async (exampleId, updates, forceUpdate) => {
              try {
                const response = await fetch(
                  `/tenants/${tenantId}/templates/${catalogId}/${templateId}/data-examples/${exampleId}`,
                  {
                    method: 'PATCH',
                    headers: jsonHeaders,
                    body: JSON.stringify({ ...updates, forceUpdate }),
                  },
                );
                if (!response.ok) {
                  const err = await response.json();
                  return { success: false, errors: err.errors, warnings: err.warnings };
                }
                const result = await response.json();
                return { success: true, example: result, warnings: result.warnings };
              } catch (e) {
                return { success: false };
              }
            },
            onDeleteDataExample: async (exampleId) => {
              try {
                const response = await fetch(
                  `/tenants/${tenantId}/templates/${catalogId}/${templateId}/data-examples/${exampleId}`,
                  {
                    method: 'DELETE',
                    headers: { 'X-XSRF-TOKEN': csrfToken() },
                  },
                );
                return { success: response.ok };
              } catch (e) {
                return { success: false };
              }
            },
          },
    });
  }

  // htmx:load fires for the initial page and for every swapped-in element,
  // so this covers hard loads, boosted navigation, and fragment swaps.
  document.addEventListener('htmx:load', function (event) {
    const root = event.detail.elt;
    if (!root) return;
    if (root.matches && root.matches('[data-contract-editor]')) mountContractEditor(root);
    if (root.querySelectorAll) {
      root.querySelectorAll('[data-contract-editor]').forEach(mountContractEditor);
    }
  });
})();
