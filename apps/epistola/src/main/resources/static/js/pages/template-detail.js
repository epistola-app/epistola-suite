// Page behaviors for the template detail pages (ADR 0010: no executable
// inline scripts in templates).
//
// Loaded once globally in the page <head>; every handler is delegated on
// `document` so it works for content present at load AND content swapped in
// later by HTMX (the shell uses hx-boost, so page navigation is a body swap).
//
// Hooks (declared by templates/templates/** and fragments/version-comparison.html):
//   [data-template-name-input]   settings: inline-editable template name; PATCHes
//                                data-patch-url, Enter commits, Escape reverts to
//                                data-original-name
//   [data-theme-select]          settings: default-theme select; PATCHes data-patch-url
//                                and swaps the returned fragment into #theme-section
//   [data-pdfa-toggle]           settings: PDF/A checkbox; PATCHes data-patch-url,
//                                reverts the checkbox on failure
//   [data-confirm-submit]        handled by /js/behaviors.js (confirm-then-submit)
//   [data-add-attr-button]       variant dialogs: reveals the [data-attr-row] chosen in
//                                the sibling [data-add-attr-select] within the closest
//                                [data-attr-rows] scope
//   select[data-filter-key]      variants tab (#variant-filter-bar): attribute filter
//                                for the variant card grid
//   [data-compare-url]           opens the version-comparison dialog and HTMX-loads the
//                                comparison UI from that URL
//   [data-compare-versions]      version-comparison dialog: runs the side-by-side compare
//   [data-show-dialog-on-swap]   after an HTMX swap targets this element, its
//                                enclosing <dialog> is opened — handled app-wide
//                                in behaviors.js (not here)
//   [data-contract-editor]       data-contract tab: mounts the data-contract editor on
//                                htmx:load (idempotent via data-editor-mounted)
//   [data-pdf-preview]           handled by /js/pdf-preview.js
(function () {
  'use strict';

  function csrfToken() {
    return typeof window.getCsrfToken === 'function' ? window.getCsrfToken() : '';
  }

  // ── Settings: inline-editable template name ───────────────────────────────
  document.addEventListener('focusout', function (event) {
    const input = event.target.closest && event.target.closest('[data-template-name-input]');
    if (!input) return;
    const newName = input.value.trim();
    if (!newName || newName === input.dataset.originalName) {
      input.value = input.dataset.originalName;
      return;
    }
    fetch(input.dataset.patchUrl, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      body: JSON.stringify({ name: newName }),
    }).then(function (r) {
      if (r.ok) {
        input.dataset.originalName = newName;
        const h1 = document.querySelector('.page-header h1');
        if (h1) h1.textContent = newName;
      } else {
        input.value = input.dataset.originalName;
      }
    });
  });

  document.addEventListener('keydown', function (event) {
    const input = event.target.closest && event.target.closest('[data-template-name-input]');
    if (!input) return;
    if (event.key === 'Enter') input.blur();
    if (event.key === 'Escape') {
      input.value = input.dataset.originalName;
      input.blur();
    }
  });

  // ── Settings: default theme select ─────────────────────────────────────────
  document.addEventListener('change', function (event) {
    const select = event.target.closest && event.target.closest('[data-theme-select]');
    if (!select) return;
    const raw = select.value;
    const clearThemeId = !raw;
    let themeId = null;
    let themeCatalogKey = null;
    if (raw) {
      const slash = raw.indexOf('/');
      themeCatalogKey = raw.substring(0, slash);
      themeId = raw.substring(slash + 1);
    }
    fetch(select.dataset.patchUrl, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': csrfToken(),
        'HX-Request': 'true',
      },
      body: JSON.stringify({
        themeId: themeId,
        themeCatalogKey: themeCatalogKey,
        clearThemeId: clearThemeId,
      }),
    })
      .then(function (r) {
        return r.text();
      })
      .then(function (html) {
        document.getElementById('theme-section').outerHTML = html;
      });
  });

  // ── Settings: PDF/A output toggle ──────────────────────────────────────────
  document.addEventListener('change', function (event) {
    const toggle = event.target.closest && event.target.closest('[data-pdfa-toggle]');
    if (!toggle) return;
    const enabled = toggle.checked;
    fetch(toggle.dataset.patchUrl, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
      body: JSON.stringify({ pdfaEnabled: enabled }),
    }).then(function (r) {
      if (!r.ok) toggle.checked = !enabled;
    });
  });

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

  // Re-apply the active filters after the variant grid is swapped (publish,
  // discard, set-default, delete all target #variants-section).
  document.addEventListener('htmx:afterSwap', function (event) {
    if (event.detail.target && event.detail.target.id === 'variants-section') filterVariants();
  });

  // ── Version comparison (dialog in fragments/version-comparison.html) ──────
  let comparisonBlobUrls = [];

  function cleanupBlobUrls() {
    comparisonBlobUrls.forEach(function (url) {
      URL.revokeObjectURL(url);
    });
    comparisonBlobUrls = [];
  }

  document.addEventListener('click', function (event) {
    const button = event.target.closest && event.target.closest('[data-compare-url]');
    if (!button) return;
    const container = document.getElementById('version-comparison-dialog-body');
    const dialog = document.getElementById('version-comparison-dialog');
    if (!container || !dialog) return;
    htmx.ajax('GET', button.dataset.compareUrl, { target: container, swap: 'innerHTML' });
    dialog.showModal();
  });

  document.addEventListener('click', function (event) {
    const button = event.target.closest && event.target.closest('[data-compare-versions]');
    if (button) compareVersions();
  });

  // <dialog> `close` does not bubble; a capture-phase listener on document
  // still sees it, so this works however the dialog gets closed.
  document.addEventListener(
    'close',
    function (event) {
      if (event.target && event.target.id === 'version-comparison-dialog') cleanupBlobUrls();
    },
    true,
  );

  async function compareVersions() {
    const config = JSON.parse(document.getElementById('comparison-config').textContent);
    const versionA = document.getElementById('compare-version-a').value;
    const versionB = document.getElementById('compare-version-b').value;

    const exampleSelect = document.getElementById('compare-example');
    let exampleData = {};
    if (exampleSelect) {
      const exampleId = exampleSelect.value;
      const dataScript = document.querySelector(
        '.comparison-example-data[data-example-id="' + exampleId + '"]',
      );
      if (dataScript) {
        try {
          exampleData = JSON.parse(dataScript.textContent);
        } catch (_) {
          exampleData = {};
        }
      }
    }

    cleanupBlobUrls();

    ['a', 'b'].forEach(function (side) {
      document.getElementById('comparison-empty-' + side).style.display = 'none';
      document.getElementById('comparison-loading-' + side).style.display = 'flex';
      document.getElementById('comparison-frame-' + side).style.display = 'none';
    });

    document.getElementById('comparison-label-a').textContent = 'v' + versionA;
    document.getElementById('comparison-label-b').textContent = 'v' + versionB;

    const previewUrl =
      '/tenants/' +
      config.tenantId +
      '/templates/' +
      config.catalogId +
      '/' +
      config.templateId +
      '/variants/' +
      config.variantId +
      '/preview';

    try {
      const results = await Promise.all([
        fetch(previewUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
          body: JSON.stringify({ data: exampleData, versionId: parseInt(versionA) }),
        }),
        fetch(previewUrl, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() },
          body: JSON.stringify({ data: exampleData, versionId: parseInt(versionB) }),
        }),
      ]);

      if (!results[0].ok || !results[1].ok) {
        const errorSide = !results[0].ok ? 'A' : 'B';
        const errorResponse = !results[0].ok ? results[0] : results[1];
        const errorText = await errorResponse.text();
        throw new Error(
          'Failed to generate Version ' +
            errorSide +
            ': ' +
            (errorText || 'HTTP ' + errorResponse.status),
        );
      }

      const blobs = await Promise.all([results[0].blob(), results[1].blob()]);
      const urlA = URL.createObjectURL(blobs[0]);
      const urlB = URL.createObjectURL(blobs[1]);
      comparisonBlobUrls.push(urlA, urlB);

      document.getElementById('comparison-frame-a').src = urlA;
      document.getElementById('comparison-frame-b').src = urlB;

      ['a', 'b'].forEach(function (side) {
        document.getElementById('comparison-loading-' + side).style.display = 'none';
        document.getElementById('comparison-frame-' + side).style.display = 'block';
      });
    } catch (error) {
      console.error('Version comparison failed:', error);
      alert('Failed to compare versions: ' + error.message);
      ['a', 'b'].forEach(function (side) {
        document.getElementById('comparison-loading-' + side).style.display = 'none';
        document.getElementById('comparison-empty-' + side).style.display = 'flex';
      });
    }
  }

  // [data-show-dialog-on-swap] (data-contract "breaking changes" dialog) is
  // handled by the single app-wide open-on-swap routine in behaviors.js — no
  // per-page listener here anymore.

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

    const statusBar = document.getElementById('contract-status-bar');
    const statusBarUrl = statusBar && statusBar.dataset ? statusBar.dataset.statusUrl : undefined;

    function refreshStatusBar() {
      if (statusBarUrl && typeof htmx !== 'undefined') {
        htmx.ajax('GET', statusBarUrl, { target: '#contract-status-bar', swap: 'outerHTML' });
      }
    }

    const initialSchema = parseJsonScript('data-contract-schema', null);
    const initialExamples = parseJsonScript('data-contract-examples', []);
    const { mountDataContractEditor } = await import('/editor/data-contract-editor.js');

    const contractBasePath = `/tenants/${tenantId}/templates/${catalogId}/${templateId}/contract`;
    const jsonHeaders = { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken() };

    mountDataContractEditor({
      container: container,
      templateId: templateId,
      initialSchema: initialSchema,
      initialExamples: initialExamples,
      readonly: readonly,
      callbacks: readonly
        ? {}
        : {
            onSaveSchema: async (schema, forceUpdate, dataExamples) => {
              try {
                const body = { dataModel: schema, forceUpdate };
                if (dataExamples) body.dataExamples = dataExamples;
                const response = await fetch(`${contractBasePath}/draft`, {
                  method: 'PATCH',
                  headers: jsonHeaders,
                  body: JSON.stringify(body),
                });
                if (!response.ok) {
                  const err = await response.json();
                  return { success: false, error: err.message, warnings: err.warnings };
                }
                const result = await response.json();
                refreshStatusBar();
                return { success: true, warnings: result.warnings };
              } catch (e) {
                return { success: false, error: e.message };
              }
            },
            onSaveDataExamples: async (examples) => {
              try {
                const response = await fetch(`${contractBasePath}/draft`, {
                  method: 'PATCH',
                  headers: jsonHeaders,
                  body: JSON.stringify({ dataExamples: examples }),
                });
                if (!response.ok) {
                  const err = await response.json();
                  return { success: false, warnings: err.warnings };
                }
                const result = await response.json();
                refreshStatusBar();
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
