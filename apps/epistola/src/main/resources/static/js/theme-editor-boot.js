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
  const themeId = config.themeId;
  const themeData = config.theme;

  const editable = config.editable;

  mountThemeEditor({
    container: container,
    theme: themeData,
    readonly: !editable,
    fontOptions: {
      listFonts: async () => {
        const resp = await fetch('/tenants/' + tenantId + '/fonts/search?catalog=' + catalogId, {
          headers: { Accept: 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken() },
        });
        if (!resp.ok) throw new Error('Failed to list fonts');
        return await resp.json();
      },
    },
    onSave: async (payload) => {
      const response = await fetch(
        '/tenants/' + tenantId + '/themes/' + catalogId + '/' + themeId,
        {
          method: 'PATCH',
          headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': window.getCsrfToken(),
          },
          body: JSON.stringify(payload),
        },
      );

      if (!response.ok) {
        const text = await response.text().catch(() => response.statusText);
        throw new Error('Failed to save theme: ' + text);
      }

      // Update page title if name changed
      const result = await response.json();
      if (result.name) {
        document.title = result.name + ' - Epistola';
        const h1 = document.querySelector('.page-header h1');
        if (h1) h1.textContent = result.name;
      }
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
