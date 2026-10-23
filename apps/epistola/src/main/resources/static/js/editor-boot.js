// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// Template-editor bootstrap (ADR 0010: no executable inline scripts).
// Reads the page's #editor-config JSON island and mounts the editor into
// #editor-container. Runs for the initial page load AND for hx-boost body
// swaps (module top-level code only evaluates once per URL, so mounting is
// driven by htmx:load with an idempotency guard).

async function mount(container) {
  if (container.dataset.editorMounted) return;
  const island = document.getElementById('editor-config');
  if (!island) return;
  container.dataset.editorMounted = 'true';
  const config = JSON.parse(island.textContent);

  const editorModule = await import(config.editorModuleUrl);
  const { formatPreviewErrorResponse, loadEditorPlugins, mountEditor } = editorModule;

  const tenantId = config.tenantId;
  const catalogId = config.catalogId;
  const templateId = config.templateId;
  const variantId = config.variantId;
  const features = config.features ?? {};
  // The template's catalog as the route names it: `letters`, or `letters@2.0.0` for a draft of it
  // (several drafts per catalog). Every request about the template's own catalog carries it, so
  // the editor reads and writes the draft it was opened in; other catalogs are addressed plainly.
  // `catalogId` stays the bare key, which is what template documents store.
  const catalogSegment = config.catalogSegment || catalogId;
  // A catalog segment may name its namespace, `acme/letters` (ADR 0034): its parts are encoded apart.
  const catalogPath = (segment) => String(segment).split('/').map(encodeURIComponent).join('/');
  const inDraft = catalogSegment !== catalogId;
  const segmentOf = (catalog) => (catalog === catalogId ? catalogSegment : catalog);
  // Cross-catalog requests (searches, image content) name the draft as `in=` so the server reads
  // this catalog's rows from it and every other catalog's from its current version.
  const inParam = inDraft ? 'in=' + encodeURIComponent(catalogSegment) : '';
  // The variant's revision as last read or saved. Each save names it, so a save over someone else's
  // is refused (`resource-modified`) instead of silently replacing their work.
  let variantRevision = config.variantRevision ?? null;

  const plugins = await loadEditorPlugins(config.plugins, {
    features,
    bundledModule: editorModule,
    csrfToken: () => window.getCsrfToken(),
  });

  mountEditor({
    container: container,
    template: config.templateModel,
    // The catalog's available presets (ADR 0007) travel with the theme the inspector reads.
    theme:
      config.availablePresets && config.availablePresets.length > 0
        ? { ...config.theme, availablePresets: config.availablePresets }
        : (config.theme ?? undefined),
    dataExamples: config.dataExamples,
    dataModel: config.dataModel,
    plugins: plugins,
    features: features,
    locale: config.locale,
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
    imageOptions: {
      defaultCatalogKey: catalogId,
      contentUrlPattern:
        '/tenants/' +
        tenantId +
        '/catalogs/{catalogId}/images/{imageKey}/content' +
        (inDraft ? '?' + inParam : ''),
      listCatalogs: async () => {
        const resp = await fetch('/tenants/' + tenantId + '/images/catalogs', {
          headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
        });
        if (!resp.ok) throw new Error('Failed to list catalogs');
        return await resp.json();
      },
      listAssets: async (catalog) => {
        const params = new URLSearchParams({ catalog: segmentOf(catalog) });
        const resp = await fetch('/tenants/' + tenantId + '/images/search?' + params.toString(), {
          headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
        });
        if (!resp.ok) throw new Error('Failed to list assets');
        return await resp.json();
      },
      uploadAsset: async (file, catalog) => {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('catalog', segmentOf(catalog));
        const resp = await fetch('/tenants/' + tenantId + '/images', {
          method: 'POST',
          headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
          body: formData,
        });
        if (!resp.ok) {
          const err = await resp.json().catch(() => ({}));
          throw new Error(err.detail || err.error || 'Failed to upload asset');
        }
        return await resp.json();
      },
    },
    // A stencil has one working copy and no versions (contract ADRs 0002/0006): templates use it by
    // reference. The editor reads the working copy to inline it, saves it when the author edits the
    // stencil in place, and marks it ready.
    stencilOptions: (() => {
      const jsonHeaders = () => ({
        Accept: 'application/json',
        'X-XSRF-TOKEN': window.getCsrfToken(),
      });
      const stencilUrl = (ref) =>
        '/tenants/' +
        tenantId +
        '/catalogs/' +
        catalogPath(segmentOf(ref.catalogKey)) +
        '/stencils/' +
        encodeURIComponent(ref.stencilKey);
      // Each stencil's revision as last read or saved here, by its URL; a save names it.
      const stencilRevisions = new Map();
      const errorMessage = async (resp, fallback) => {
        const err = await resp.json().catch(() => ({}));
        return err.detail || err.message || err.error || fallback;
      };
      return {
        catalogKey: catalogId,
        searchStencils: async (query) => {
          const params = new URLSearchParams();
          if (query) params.set('q', query);
          if (inDraft) params.set('in', catalogSegment);
          const resp = await fetch('/tenants/' + tenantId + '/stencils/search?' + params, {
            headers: jsonHeaders(),
          });
          if (!resp.ok) throw new Error('Failed to search stencils');
          const data = await resp.json();
          return (data.items ?? []).map((item) => ({
            stencilKey: item.id,
            catalogKey: item.catalogKey,
            name: item.name,
            description: item.description ?? undefined,
            tags: item.tags ?? [],
          }));
        },
        getStencil: async (ref) => {
          const resp = await fetch(stencilUrl(ref) + '/content', { headers: jsonHeaders() });
          if (resp.status === 404) return null;
          if (!resp.ok) throw new Error(await errorMessage(resp, 'Failed to load the stencil'));
          const data = await resp.json();
          if (typeof data.revision === 'number')
            stencilRevisions.set(stencilUrl(ref), data.revision);
          return {
            ref: { stencilKey: data.stencilId, catalogKey: data.catalogKey },
            name: data.name ?? data.stencilId,
            editable: data.editable === true,
            content: data.content,
            parameterSchema: data.parameterSchema ?? undefined,
            readsTemplateData: data.readsTemplateData === true,
          };
        },
        createStencil: async (stencilKey, name) => {
          const resp = await fetch('/tenants/' + tenantId + '/stencils', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...jsonHeaders() },
            body: JSON.stringify({ id: stencilKey, name, catalogKey: catalogSegment }),
          });
          if (!resp.ok) {
            throw new Error(await errorMessage(resp, 'Stencil key already exists or is invalid'));
          }
          const data = await resp.json();
          return { stencilKey: data.stencilId, catalogKey: data.catalogKey };
        },
        saveStencil: async function saveStencil(ref, update, overwrite = false) {
          const url = stencilUrl(ref);
          const body =
            update.parameterSchema === null
              ? { content: update.content, clearParameterSchema: true }
              : { content: update.content, parameterSchema: update.parameterSchema };
          body.expectedRevision = overwrite ? null : (stencilRevisions.get(url) ?? null);
          const resp = await fetch(url + '/content', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', ...jsonHeaders() },
            body: JSON.stringify(body),
          });
          if (resp.status === 412) {
            const overwriteTheirs = window.confirm(
              'Someone else saved this stencil after you opened it.\n\n' +
                'OK: replace their version with yours.\n' +
                'Cancel: keep theirs; open the stencil again to see it.',
            );
            if (overwriteTheirs) return saveStencil(ref, update, true);
            throw new Error(
              'Not saved: someone else saved this stencil. Open it again to see their version.',
            );
          }
          if (!resp.ok) throw new Error(await errorMessage(resp, 'Failed to save the stencil'));
          const saved = await resp.json().catch(() => null);
          if (saved && typeof saved.revision === 'number')
            stencilRevisions.set(url, saved.revision);
        },
        markStencilReady: async (ref) => {
          const resp = await fetch(stencilUrl(ref) + '/ready', {
            method: 'POST',
            headers: jsonHeaders(),
          });
          if (!resp.ok) {
            throw new Error(await errorMessage(resp, 'Failed to mark the stencil ready'));
          }
        },
      };
    })(),
    onSave: async function save(template, overwrite = false) {
      const response = await fetch(
        '/tenants/' +
          tenantId +
          '/catalogs/' +
          catalogPath(catalogSegment) +
          '/templates/' +
          templateId +
          '/variants/' +
          variantId +
          '/content',
        {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/problem+json',
            'X-XSRF-TOKEN': window.getCsrfToken(),
          },
          body: JSON.stringify({
            templateModel: template,
            expectedRevision: overwrite ? null : variantRevision,
          }),
        },
      );

      if (response.status === 412) {
        // Someone else saved this variant since it was opened here. Ask rather than guess: keeping
        // their version means reloading (and losing what was changed here); keeping this one
        // overwrites theirs.
        const overwriteTheirs = window.confirm(
          'Someone else saved this variant after you opened it.\n\n' +
            'OK: replace their version with yours.\n' +
            'Cancel: reload to see their version (your changes here are lost).',
        );
        if (overwriteTheirs) return save(template, true);
        window.location.reload();
        return;
      }

      if (!response.ok) {
        let message = response.statusText || 'Failed to save';
        let type = null;
        let field = null;
        const text = await response.text().catch(() => '');
        if (text) {
          try {
            const body = JSON.parse(text);
            // `detail` = problem+json (unexpected errors); `message` =
            // the validation envelope; `error` = legacy UI envelope.
            message = body.detail || body.message || body.error || message;
            type = body.type || null;
            field = (body.errors && body.errors[0] && body.errors[0].field) || null;
          } catch (_) {
            message = text;
          }
        }
        // Carry the structured problem `type`/`field` on the Error so
        // the editor can render it inline without parsing the message.
        const err = new Error(message);
        err.type = type;
        err.field = field;
        throw err;
      }
      const saved = await response.json().catch(() => null);
      if (saved && typeof saved.revision === 'number') variantRevision = saved.revision;
    },
    onFetchPreview: async (doc, data, signal) => {
      const response = await fetch(
        '/tenants/' +
          tenantId +
          '/catalogs/' +
          catalogPath(catalogSegment) +
          '/templates/' +
          templateId +
          '/variants/' +
          variantId +
          '/preview',
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/pdf',
            'X-XSRF-TOKEN': window.getCsrfToken(),
          },
          body: JSON.stringify({
            templateModel: doc,
            data: data?.data ?? data,
            theme: config.theme ?? null,
          }),
          signal: signal,
        },
      );

      if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new Error(formatPreviewErrorResponse(text, response.statusText || 'Preview failed'));
      }

      return await response.blob();
    },
  });
}

function scan(root) {
  if (!root) return;
  if (root.id === 'editor-container') mount(root);
  else if (root.querySelector) {
    const container = root.querySelector('#editor-container');
    if (container) mount(container);
  }
}

document.addEventListener('htmx:load', (event) => scan(event.detail.elt));
scan(document.body);
