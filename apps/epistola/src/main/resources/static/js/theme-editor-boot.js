// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Theme-editor bootstrap (ADR 0010: no executable inline scripts).
// Reads the page's #theme-editor-config JSON island and mounts the theme
// editor into #theme-editor-container. htmx:load-driven with an idempotency
// guard so it survives hx-boost body swaps (module top-level code only
// evaluates once per URL).

async function mount(container) {
  if (container.dataset.editorMounted) return;
  const island = document.getElementById('theme-editor-config');
  if (!island) return;
  container.dataset.editorMounted = 'true';
  const config = JSON.parse(island.textContent);

  const { mountThemeEditor } = await import(config.editorModuleUrl);

  const tenantId = config.tenantId;
  const catalogId = config.catalogId;
  // `letters`, or `letters@2.0.0` for a draft (several drafts per catalog): the theme's own
  // requests carry it, so the editor saves into the draft the page was opened in.
  const catalogSegment = config.catalogSegment || catalogId;
  const themeId = config.themeId;
  const themeData = config.theme;
  // The theme's revision as last read or saved; each save names it (see editor-boot.js).
  let themeRevision = themeData?.revision ?? null;

  const editable = config.editable;

  mountThemeEditor({
    container: container,
    theme: themeData,
    readonly: !editable,
    fontOptions: {
      listFonts: async () => {
        const resp = await fetch(
          '/tenants/' + tenantId + '/fonts/search?catalog=' + encodeURIComponent(catalogSegment),
          {
            headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
          },
        );
        if (!resp.ok) throw new Error('Failed to list fonts');
        return await resp.json();
      },
    },
    onSave: async function save(payload, overwrite = false) {
      const response = await fetch(
        '/tenants/' +
          tenantId +
          '/catalogs/' +
          encodeURIComponent(catalogSegment) +
          '/themes/' +
          themeId,
        {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': window.getCsrfToken(),
          },
          body: JSON.stringify({ ...payload, expectedRevision: overwrite ? null : themeRevision }),
        },
      );

      if (response.status === 412) {
        const overwriteTheirs = window.confirm(
          'Someone else saved this theme after you opened it.\n\n' +
            'OK: replace their version with yours.\n' +
            'Cancel: reload to see their version (your changes here are lost).',
        );
        if (overwriteTheirs) return save(payload, true);
        window.location.reload();
        return;
      }

      if (!response.ok) {
        const text = await response.text().catch(() => response.statusText);
        throw new Error('Failed to save theme: ' + text);
      }

      // Update page title if name changed
      const result = await response.json();
      if (typeof result.revision === 'number') themeRevision = result.revision;
      if (result.name) {
        document.title = result.name + ' - Epistola';
        const titleEl = document.getElementById('page-title-text');
        if (titleEl) titleEl.textContent = result.name;
      }

      // This save is a raw fetch(), so it bypasses htmx.js entirely — the
      // HX-Trigger mechanism other resource-changed notifications ride on
      // never fires for it. Tell the embedding bridge directly instead
      // (docs/embedding.md). No-op when the bridge isn't loaded (embedding
      // disabled, i.e. every non-demo deployment).
      window.epistolaEmbedBridge?.notifyResourceChanged(
        { resourceType: 'theme', tenantId: tenantId, catalogKey: catalogId, key: themeId },
        'update',
      );
    },
  });
}

function scan(root) {
  if (!root) return;
  if (root.id === 'theme-editor-container') mount(root);
  else if (root.querySelector) {
    const container = root.querySelector('#theme-editor-container');
    if (container) mount(container);
  }
}

document.addEventListener('htmx:load', (event) => scan(event.detail.elt));
scan(document.body);
