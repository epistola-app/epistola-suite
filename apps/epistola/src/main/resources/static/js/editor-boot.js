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

  const { createQualityPlugin, mountEditor } = await import(config.editorModuleUrl);

  const tenantId = config.tenantId;
  const catalogId = config.catalogId;
  const templateId = config.templateId;
  const variantId = config.variantId;
  const features = config.features ?? {};

  // Load plugins dynamically
  const plugins = [];

  if (features.aiChat?.enabled) {
    // AI chat plugin — uses mock transport until backend is wired up.
    try {
      const { createAiPlugin, createMockTransport } = await import(config.aiPluginUrl);
      plugins.push(
        createAiPlugin({
          sendMessage: createMockTransport(),
          badge: features.aiChat.badge,
        }),
      );
    } catch (e) {
      console.warn('AI plugin failed to load:', e);
    }
  }

  if (features.quality?.enabled && config.quality) {
    plugins.push(
      createQualityPlugin({
        findingsUrl: config.quality.findingsUrl,
        checkUrl: config.quality.checkUrl,
        csrfToken: () => window.getCsrfToken(),
      }),
    );
  }

  mountEditor({
    container: container,
    template: config.templateModel,
    dataExamples: config.dataExamples,
    dataModel: config.dataModel,
    plugins: plugins,
    features: features,
    locale: config.locale,
    fontOptions: {
      listFonts: async () => {
        const resp = await fetch('/tenants/' + tenantId + '/fonts/search?catalog=' + catalogId, {
          headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
        });
        if (!resp.ok) throw new Error('Failed to list fonts');
        return await resp.json();
      },
    },
    imageOptions: {
      defaultCatalogKey: catalogId,
      contentUrlPattern: '/tenants/' + tenantId + '/images/{catalogId}/{assetId}/content',
      listCatalogs: async () => {
        const resp = await fetch('/tenants/' + tenantId + '/images/catalogs', {
          headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
        });
        if (!resp.ok) throw new Error('Failed to list catalogs');
        return await resp.json();
      },
      listAssets: async (catalog) => {
        const params = new URLSearchParams({ catalog });
        const resp = await fetch('/tenants/' + tenantId + '/images/search?' + params.toString(), {
          headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
        });
        if (!resp.ok) throw new Error('Failed to list assets');
        return await resp.json();
      },
      uploadAsset: async (file, catalog) => {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('catalog', catalog);
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
    stencilOptions: {
      checkUpgrades: async (refs) => {
        const uniqueKeys = [...new Set(refs.map((r) => r.catalogKey + '/' + r.stencilId))];
        const results = [];
        for (const key of uniqueKeys) {
          const [cat, sid] = key.split('/');
          const resp = await fetch(
            '/tenants/' + tenantId + '/stencils/' + cat + '/' + sid + '/versions',
            {
              headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
            },
          );
          if (!resp.ok) continue;
          const data = await resp.json();
          const versions = data.items ?? data;
          const latestPublished = versions
            .filter((v) => v.status === 'published')
            .sort((a, b) => b.version - a.version)[0];
          if (latestPublished) {
            const currentVersions = refs.filter((r) => r.stencilId === sid && r.catalogKey === cat);
            for (const ref of currentVersions) {
              if (latestPublished.version > ref.version) {
                results.push({
                  stencilId: sid,
                  currentVersion: ref.version,
                  latestVersion: latestPublished.version,
                });
              }
            }
          }
        }
        return results;
      },
      searchStencils: async (query) => {
        const params = new URLSearchParams();
        if (query) params.set('q', query);
        const resp = await fetch('/tenants/' + tenantId + '/stencils/search?' + params, {
          headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
        });
        if (!resp.ok) return [];
        const data = await resp.json();
        return data.items ?? data;
      },
      listVersions: async (ref) => {
        const resp = await fetch(
          '/tenants/' +
            tenantId +
            '/stencils/' +
            ref.catalogKey +
            '/' +
            ref.stencilId +
            '/versions',
          {
            headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
          },
        );
        if (!resp.ok) return [];
        const data = await resp.json();
        return data.items ?? data;
      },
      getStencilVersion: async (ref, version) => {
        const resp = await fetch(
          '/tenants/' +
            tenantId +
            '/stencils/' +
            ref.catalogKey +
            '/' +
            ref.stencilId +
            '/versions/' +
            version,
          {
            headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
          },
        );
        if (!resp.ok) return null;
        const v = await resp.json();
        return {
          ref,
          stencilName: ref.stencilId,
          version: v.id ?? version,
          content: v.content,
          parameterSchema: v.parameterSchema ?? undefined,
        };
      },
      createStencil: async (slug, name) => {
        const resp = await fetch('/tenants/' + tenantId + '/stencils', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
          body: JSON.stringify({ id: slug, name, catalogKey: catalogId }),
        });
        if (!resp.ok) {
          const err = await resp.json().catch(() => ({}));
          throw new Error(
            err.detail || err.message || err.error || 'Stencil ID already exists or invalid',
          );
        }
        const data = await resp.json();
        return {
          ref: { stencilId: data.stencilId, catalogKey: data.catalogKey },
          version: data.version,
        };
      },
      startEditing: async (ref) => {
        const resp = await fetch(
          '/tenants/' +
            tenantId +
            '/stencils/' +
            ref.catalogKey +
            '/' +
            ref.stencilId +
            '/versions',
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
            body: '{}',
          },
        );
        if (!resp.ok) throw new Error('Failed to start editing');
        const data = await resp.json();
        return { draftVersion: data.version };
      },
      publishDraft: async (ref, version) => {
        const resp = await fetch(
          '/tenants/' +
            tenantId +
            '/stencils/' +
            ref.catalogKey +
            '/' +
            ref.stencilId +
            '/versions/' +
            version +
            '/publish',
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
          },
        );
        if (!resp.ok) throw new Error('Failed to publish draft');
        return await resp.json();
      },
      updateStencil: async (ref, content, parameterSchema) => {
        const body = parameterSchema !== undefined ? { content, parameterSchema } : { content };
        const resp = await fetch(
          '/tenants/' + tenantId + '/stencils/' + ref.catalogKey + '/' + ref.stencilId + '/draft',
          {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
            body: JSON.stringify(body),
          },
        );
        if (!resp.ok) throw new Error('Failed to update stencil');
        return await resp.json();
      },
    },
    onSave: async (template) => {
      const response = await fetch(
        '/tenants/' +
          tenantId +
          '/templates/' +
          catalogId +
          '/' +
          templateId +
          '/variants/' +
          variantId +
          '/draft',
        {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/problem+json',
            'X-XSRF-TOKEN': window.getCsrfToken(),
          },
          body: JSON.stringify({ templateModel: template }),
        },
      );

      if (!response.ok) {
        let message = response.statusText || 'Failed to save draft';
        let type = null;
        let field = null;
        const text = await response.text().catch(() => '');
        if (text) {
          try {
            const body = JSON.parse(text);
            // `detail` = problem+json (unexpected errors); `message` =
            // the draft validation envelope; `error` = legacy UI envelope.
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
    },
    onFetchPreview: async (doc, data, signal) => {
      const response = await fetch(
        '/tenants/' +
          tenantId +
          '/templates/' +
          catalogId +
          '/' +
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
          body: JSON.stringify({ templateModel: doc, data: data?.data ?? data }),
          signal: signal,
        },
      );

      if (!response.ok) {
        const text = await response.text().catch(() => response.statusText);
        throw new Error('Preview failed: ' + text);
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
