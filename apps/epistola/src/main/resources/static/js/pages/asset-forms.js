// Asset/catalog-resource form behaviors (ADR 0010: no executable inline scripts).
// Covers: templates/attributes/new.html, templates/attributes/list.html (edit
// dialog fragment), templates/code-lists/new.html, templates/fonts/new.html,
// templates/images/new.html.
//
// Conventions (same as behaviors.js):
// - Templates declare intent with data-* attributes; behavior lives here.
// - Listeners are installed once on `document` — HTMX events bubble, so handlers
//   work for content present at load AND content swapped in later (e.g. the
//   edit-attribute dialog fragment).
// - Per-content initialization runs on `htmx:load` and is idempotent.

// ── Radio-driven pane visibility ─────────────────────────────────────────────
// Usage: <form data-radio-panes="constraintKind" data-pane-attr="data-constraint-pane">
//          <input type="radio" name="constraintKind" value="inline">
//          <div data-constraint-pane="inline">…</div>
// The pane whose attribute value matches the checked radio's value is shown;
// all other panes in the form are hidden. Re-evaluated on every radio change
// and once when the form arrives (initial load or HTMX swap).
(function () {
  'use strict';

  function refreshRadioPanes(form) {
    const radioName = form.getAttribute('data-radio-panes');
    const paneAttr = form.getAttribute('data-pane-attr');
    const selected = form.querySelector('input[name="' + radioName + '"]:checked');
    const kind = selected ? selected.value : '';
    form.querySelectorAll('[' + paneAttr + ']').forEach(function (pane) {
      pane.hidden = pane.getAttribute(paneAttr) !== kind;
    });
  }

  document.addEventListener('change', function (event) {
    const form = event.target.closest && event.target.closest('form[data-radio-panes]');
    if (!form) return;
    if (event.target.name === form.getAttribute('data-radio-panes')) refreshRadioPanes(form);
  });

  // ── Code-list inline entries editor ──────────────────────────────────────────
  // Usage: <fieldset data-entries-editor>
  //          <tbody id="entries-tbody"></tbody>
  //          <button type="button" id="add-entry-btn" data-add-entry>…</button>
  //          <input type="hidden" name="entriesJson">
  // Rows are plain DOM; the hidden input mirrors them as a JSON array of
  // {code, label, sortOrder}. Hydrates from the hidden input (server-rendered
  // formData after a validation failure), else starts with one empty row.

  function entriesEditorParts(root) {
    return {
      tbody: root.querySelector('tbody'),
      hidden: root.querySelector('input[name="entriesJson"]'),
    };
  }

  function makeEntryRow(idx, code, label) {
    const tr = document.createElement('tr');
    tr.innerHTML =
      '<td><input id="entry-code-' +
      idx +
      '" type="text" class="ep-input" placeholder="code" aria-label="Entry code" maxlength="64" data-entry-code required></td>' +
      '<td><input id="entry-label-' +
      idx +
      '" type="text" class="ep-input" placeholder="Label" aria-label="Entry label" maxlength="200" data-entry-label required></td>' +
      '<td><button type="button" class="btn btn-sm btn-ghost btn-ghost-destructive" data-remove-entry>Remove</button></td>';
    tr.querySelector('[data-entry-code]').value = code;
    tr.querySelector('[data-entry-label]').value = label;
    return tr;
  }

  function syncEntries(root) {
    const parts = entriesEditorParts(root);
    const rows = [];
    parts.tbody.querySelectorAll('tr').forEach(function (tr, idx) {
      // Keep the per-row input ids sequential after removals.
      tr.querySelector('[data-entry-code]').id = 'entry-code-' + idx;
      tr.querySelector('[data-entry-label]').id = 'entry-label-' + idx;
      rows.push({
        code: tr.querySelector('[data-entry-code]').value.trim(),
        label: tr.querySelector('[data-entry-label]').value,
        sortOrder: idx,
      });
    });
    parts.hidden.value = JSON.stringify(rows);
  }

  function addEntryRow(root, code, label) {
    const parts = entriesEditorParts(root);
    parts.tbody.appendChild(makeEntryRow(parts.tbody.children.length, code || '', label || ''));
  }

  function hydrateEntriesEditor(root) {
    if (root.hasAttribute('data-entries-hydrated')) return;
    root.setAttribute('data-entries-hydrated', '');
    const parts = entriesEditorParts(root);
    let existing = [];
    try {
      existing = parts.hidden.value ? JSON.parse(parts.hidden.value) : [];
    } catch (e) {
      existing = [];
    }
    if (existing.length) {
      existing.forEach(function (r) {
        addEntryRow(root, r.code, r.label);
      });
    } else {
      addEntryRow(root);
    }
    syncEntries(root);
  }

  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;

    const addBtn = event.target.closest('[data-add-entry]');
    if (addBtn) {
      const root = addBtn.closest('[data-entries-editor]');
      addEntryRow(root);
      syncEntries(root);
      return;
    }

    const removeBtn = event.target.closest('[data-remove-entry]');
    if (removeBtn) {
      const root = removeBtn.closest('[data-entries-editor]');
      removeBtn.closest('tr').remove();
      syncEntries(root);
    }
  });

  document.addEventListener('input', function (event) {
    const root = event.target.closest && event.target.closest('[data-entries-editor]');
    if (root && event.target.matches('[data-entry-code], [data-entry-label]')) syncEntries(root);
  });

  // ── Font faces: add another weight/italic row ────────────────────────────────
  // Usage: <div data-font-face-rows><div class="font-face-row">…</div></div>
  //        <button type="button" data-add-font-face>+ Add face</button>
  // Clones the first row with file cleared and weight/italic reset to defaults.
  document.addEventListener('click', function (event) {
    const btn = event.target.closest && event.target.closest('[data-add-font-face]');
    if (!btn) return;
    const container = btn.closest('form').querySelector('[data-font-face-rows]');
    const clone = container.querySelector('.font-face-row').cloneNode(true);
    clone.querySelectorAll('input, select').forEach(function (el) {
      if (el.type === 'file') {
        el.value = '';
      } else if (el.name === 'weight') {
        el.value = '400';
      } else if (el.name === 'italic') {
        el.selectedIndex = 0;
      }
    });
    container.appendChild(clone);
  });

  // ── Upload forms: surface server errors next to the form ────────────────────
  // Usage: <form hx-post="…" data-upload-error="error-span-id"
  //              data-upload-error-message="Fallback message.">
  // Writes the RFC 7807 detail (or a fallback) into the referenced element on
  // htmx:responseError; clears it when a new request starts.
  function uploadErrorTarget(form) {
    return document.getElementById(form.getAttribute('data-upload-error'));
  }

  document.addEventListener('htmx:responseError', function (event) {
    const form = event.target.closest && event.target.closest('form[data-upload-error]');
    if (!form) return;
    const errorEl = uploadErrorTarget(form);
    const xhr = event.detail && event.detail.xhr;
    if (!errorEl || !xhr || xhr.status < 400) return;
    const fallback =
      form.getAttribute('data-upload-error-message') || 'The request failed. Please try again.';
    try {
      const body = JSON.parse(xhr.responseText || '{}');
      errorEl.textContent = body.detail || body.error || fallback;
    } catch (e) {
      errorEl.textContent = fallback;
    }
  });

  document.addEventListener('htmx:beforeRequest', function (event) {
    const form = event.target.closest && event.target.closest('form[data-upload-error]');
    if (!form) return;
    const errorEl = uploadErrorTarget(form);
    if (errorEl) errorEl.textContent = '';
  });

  // ── File input: show the selected file name ─────────────────────────────────
  // Usage: <input type="file" data-file-preview="preview-container-id">
  // The container holds a #file-name span; it is shown with the chosen file's
  // name (and any upload error on the form is cleared).
  document.addEventListener('change', function (event) {
    const input = event.target.closest && event.target.closest('input[data-file-preview]');
    if (!input) return;
    const preview = document.getElementById(input.getAttribute('data-file-preview'));
    if (!preview) return;
    const fileName = preview.querySelector('#file-name');
    if (input.files.length > 0) {
      fileName.textContent = input.files[0].name;
      preview.style.display = 'block';
      const form = input.closest('form[data-upload-error]');
      if (form) {
        const errorEl = uploadErrorTarget(form);
        if (errorEl) errorEl.textContent = '';
      }
    } else {
      preview.style.display = 'none';
    }
  });

  // ── Drag-and-drop upload zone ────────────────────────────────────────────────
  // Usage: <div data-file-drop-zone class="asset-upload-zone">…<input type="file">…</div>
  // Dropping a file assigns it to the zone's file input and fires its change
  // event (which drives the preview behavior above).
  document.addEventListener('dragover', function (event) {
    const zone = event.target.closest && event.target.closest('[data-file-drop-zone]');
    if (!zone) return;
    event.preventDefault();
    zone.classList.add('dragover');
  });

  document.addEventListener('dragleave', function (event) {
    const zone = event.target.closest && event.target.closest('[data-file-drop-zone]');
    if (zone) zone.classList.remove('dragover');
  });

  document.addEventListener('drop', function (event) {
    const zone = event.target.closest && event.target.closest('[data-file-drop-zone]');
    if (!zone) return;
    event.preventDefault();
    zone.classList.remove('dragover');
    const file = event.dataTransfer.files[0];
    if (!file) return;
    const input = zone.querySelector('input[type="file"]');
    const dt = new DataTransfer();
    dt.items.add(file);
    input.files = dt.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });

  // ── Per-content initialization ───────────────────────────────────────────────
  // htmx:load fires for the initial page and for every swapped-in element.
  document.addEventListener('htmx:load', function (event) {
    const root = event.detail.elt;
    if (!root.querySelectorAll) return;
    const forms = root.querySelectorAll('form[data-radio-panes]');
    forms.forEach(refreshRadioPanes);
    if (root.matches && root.matches('form[data-radio-panes]')) refreshRadioPanes(root);
    root.querySelectorAll('[data-entries-editor]').forEach(hydrateEntriesEditor);
    if (root.matches && root.matches('[data-entries-editor]')) hydrateEntriesEditor(root);
  });
})();
