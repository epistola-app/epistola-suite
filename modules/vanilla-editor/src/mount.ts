/**
 * Mount function and per-instance runtime access.
 *
 * `mountEditorApp()` mounts the package-owned editor shell.
 * Runtime access is resolved per mounted shell (no module-global editor singleton).
 *
 * @example
 * ```ts
 * import { mountEditorApp } from '@epistola/vanilla-editor';
 *
 * const editor = mountEditorApp({
 *   container: '#editor-root',
 *   template: templateData,
 *   onSave: async (t) => fetch('/save', { method: 'POST', body: JSON.stringify(t) }),
 * });
 *
 * // Later:
 * editor.destroy();
 * ```
 */

import { TemplateEditor } from "@epistola/headless-editor";
import type {
  Template,
  BlockType,
  ThemeSummary,
  DataExample,
  JsonSchema,
} from "@epistola/headless-editor";
import { Application } from "@hotwired/stimulus";
import type { MountedEditor, MountEditorAppConfig } from "./types.js";
import type { BlockRendererPlugin } from "./types.js";
import { BlockRenderer } from "./renderer.js";
import { SortableAdapter } from "./sortable-adapter.js";
import { TextBlockController } from "./controllers/text-block.js";
import { ExpressionEditorController } from "./controllers/expression-editor.js";
import { EditorController } from "./controllers/editor-controller.js";
import { installHotkeys } from "./hotkeys.js";

type ToolbarCatalogItem = {
  type: BlockType;
  label: string;
  group: string;
  order: number;
  visible: boolean;
  addableAtRoot: boolean;
};

type CatalogCapableEditor = TemplateEditor & {
  getBlockCatalog?: () => ToolbarCatalogItem[];
  getBlockTypes?: () => BlockType[];
  getBlockDefinition?: (type: BlockType) =>
    | {
        label?: string;
        icon?: string;
        category?: string;
        constraints: { allowedParentTypes: string[] | null };
      }
    | undefined;
};

interface CoreMountConfig {
  container: string | HTMLElement;
  template: Template;
  save?: { handler: (template: Template) => Promise<void> };
  themes?: ThemeSummary[];
  defaultTheme?: ThemeSummary | null;
  dataExamples?: DataExample[];
  schema?: JsonSchema | null;
  debug?: boolean;
  rendererPlugins?: BlockRendererPlugin[];
  dndMode?: "native" | "fallback";
}

interface AppRuntime {
  editor: TemplateEditor;
  saveHandler?: (template: Template) => Promise<void>;
}

type PreviewPayload = {
  type: "pdf" | "html" | "none";
  blob?: Blob;
  html?: string;
};

const runtimeByShell = new WeakMap<HTMLElement, AppRuntime>();

export function getRuntimeForElement(
  element: Element | null,
): AppRuntime | null {
  if (!element) return null;
  const shell = element.closest<HTMLElement>('[data-editor-app-shell="true"]');
  if (!shell) return null;
  return runtimeByShell.get(shell) ?? null;
}

export function getEditorForElement(
  element: Element | null,
): TemplateEditor | null {
  return getRuntimeForElement(element)?.editor ?? null;
}

/**
 * Mount a fully functional editor into a container element.
 *
 * Creates a TemplateEditor, registers Stimulus controllers, sets up the
 * renderer, sortable adapter, and hotkey bindings. Subscribes to template
 * changes for automatic re-rendering and optional preview refresh.
 *
 * @throws Error if an editor is already mounted (call destroy() first)
 * @throws Error if the container element cannot be resolved
 */
function mountEditor(config: CoreMountConfig): MountedEditor {
  // Resolve container
  const container = resolveContainer(config.container);

  // Create headless editor
  const editor = new TemplateEditor({
    template: config.template,
  });

  // Configure themes
  if (config.themes) {
    editor.setThemes(config.themes);
  }
  if (config.defaultTheme) {
    editor.setDefaultTheme(config.defaultTheme);
  }

  // Configure data examples
  if (config.dataExamples) {
    editor.setDataExamples(config.dataExamples);
  }

  // Configure schema
  if (config.schema) {
    editor.setSchema(config.schema);
  }

  // Create renderer and sortable adapter
  const renderer = new BlockRenderer({
    container,
    editor,
    debug: config.debug,
    rendererPlugins: config.rendererPlugins,
  });
  const sortableAdapter = new SortableAdapter({
    editor,
    container,
    dragDropPort: editor.getDragDropPort(),
    dndMode: config.dndMode,
  });

  // Register Stimulus controllers
  const stimulusApp = Application.start();
  stimulusApp.register("text-block", TextBlockController);
  stimulusApp.register("expression-editor", ExpressionEditorController);
  stimulusApp.register("editor", EditorController);

  // Render loop: subscribe to template changes
  // Destroy sortable BEFORE uhtml re-renders to keep DOM clean for diffing,
  // then re-create sortable instances on the freshly rendered DOM.
  const render = () => {
    sortableAdapter.destroy();
    renderer.render();
    sortableAdapter.setup();
  };

  const unsubscribe = editor.subscribe(() => {
    render();
  });

  // Initial render
  render();

  // Install hotkeys
  const cleanupHotkeys = installHotkeys(container);

  // Return public API
  return {
    getTemplate(): Template {
      return editor.getTemplate();
    },

    getEditor(): TemplateEditor {
      return editor;
    },

    destroy(): void {
      cleanupHotkeys();
      unsubscribe();
      sortableAdapter.destroy();
      renderer.destroy();
      stimulusApp.stop();
    },
  };
}

export function mountEditorApp(config: MountEditorAppConfig): MountedEditor {
  const host = resolveContainer(config.container);

  host.innerHTML = buildEditorAppShell(config);

  const shell = host.querySelector<HTMLElement>(
    '[data-editor-app-shell="true"]',
  );
  const editorRoot = host.querySelector<HTMLElement>(
    '[data-editor-target="blockContainer"]',
  );
  if (!shell || !editorRoot) {
    throw new Error("Failed to initialize editor app shell");
  }

  const mounted = mountEditor({
    container: editorRoot,
    template: config.template,
    save: config.onSave
      ? {
          handler: config.onSave,
        }
      : undefined,
    themes: config.themes,
    defaultTheme: config.defaultTheme,
    dataExamples: config.dataExamples,
    schema: config.schema,
    debug: config.debug,
    rendererPlugins: config.rendererPlugins,
    dndMode: config.dndMode,
  });

  const editor = mounted.getEditor();
  editor.markAsSaved();
  (
    editor as unknown as {
      __saveHandler?: (template: Template) => Promise<void>;
    }
  ).__saveHandler = config.onSave;
  runtimeByShell.set(shell, {
    editor,
    saveHandler: config.onSave,
  });
  (shell as HTMLElement & { __veEditor?: TemplateEditor }).__veEditor = editor;
  renderPluginToolbar(host, editor);
  setupShellModals(host, editor);

  const previewButton = host.querySelector<HTMLButtonElement>(
    '[data-editor-app-action="preview"]',
  );
  const previewStatus = host.querySelector<HTMLElement>(
    '[data-editor-app-target="previewStatus"]',
  );
  const previewPane = host.querySelector<HTMLElement>(
    '[data-editor-app-target="previewPane"]',
  );
  const previewIframe = host.querySelector<HTMLIFrameElement>(
    '[data-editor-app-target="previewIframe"]',
  );
  const previewEmpty = host.querySelector<HTMLElement>(
    '[data-editor-app-target="previewEmpty"]',
  );
  const saveButton = host.querySelector<HTMLButtonElement>(
    '[data-editor-target="saveBtn"]',
  );
  let previewObjectUrl: string | null = null;
  let previewDebounceTimer: ReturnType<typeof setTimeout> | null = null;

  const clearPreviewObjectUrl = () => {
    if (previewObjectUrl) {
      URL.revokeObjectURL(previewObjectUrl);
      previewObjectUrl = null;
    }
  };

  const showPreviewEmpty = (message?: string) => {
    if (previewIframe) {
      previewIframe.style.display = "none";
      previewIframe.removeAttribute("src");
      previewIframe.removeAttribute("srcdoc");
    }
    if (previewEmpty) {
      previewEmpty.style.display = "block";
      if (message) previewEmpty.textContent = message;
    }
  };

  const showPreviewContent = () => {
    if (previewIframe) previewIframe.style.display = "block";
    if (previewEmpty) previewEmpty.style.display = "none";
  };

  const normalizePreviewPayload = (result: unknown): PreviewPayload => {
    if (result == null) return { type: "none" };
    if (typeof result === "string") return { type: "html", html: result };
    if (result instanceof Blob) return { type: "pdf", blob: result };

    const candidate = result as { mimeType?: unknown; data?: unknown };
    if (candidate && typeof candidate === "object") {
      const data = candidate.data;
      if (typeof data === "string") return { type: "html", html: data };
      if (data instanceof Blob) return { type: "pdf", blob: data };
    }

    return { type: "none" };
  };

  if (previewPane) {
    previewPane.style.display =
      config.ui?.showPreview === false ? "none" : "block";
  }
  const triggerPreview = async () => {
    if (!config.onPreview) return;
    if (previewStatus) {
      previewStatus.textContent = "Previewing...";
      previewStatus.className = "text-muted small";
    }

    try {
      const result = await config.onPreview(
        mounted.getTemplate(),
        mounted.getEditor().getTestData(),
      );
      const payload = normalizePreviewPayload(result);

      clearPreviewObjectUrl();
      if (payload.type === "pdf" && payload.blob && previewIframe) {
        previewObjectUrl = URL.createObjectURL(payload.blob);
        previewIframe.src = previewObjectUrl;
        showPreviewContent();
      } else if (payload.type === "html" && previewIframe) {
        previewIframe.srcdoc = payload.html ?? "";
        showPreviewContent();
      } else {
        showPreviewEmpty("Preview rendered by host callback");
      }

      if (previewStatus) {
        previewStatus.textContent = "Preview ready";
        previewStatus.className = "text-success small";
      }
    } catch (error: unknown) {
      clearPreviewObjectUrl();
      showPreviewEmpty("Preview failed");
      if (previewStatus) {
        previewStatus.textContent =
          error instanceof Error ? error.message : "Preview failed";
        previewStatus.className = "text-danger small";
      }
    }
  };

  if (previewButton) {
    previewButton.addEventListener("click", () => {
      void triggerPreview();
    });
  }

  const schedulePreview = () => {
    if (!config.onPreview) return;
    if (previewDebounceTimer) {
      clearTimeout(previewDebounceTimer);
    }
    previewDebounceTimer = setTimeout(() => {
      previewDebounceTimer = null;
      void triggerPreview();
    }, 600);
  };

  if (config.onPreview) {
    const stores = editor.getStores();
    const unsubTemplate = stores.$template.subscribe(() => {
      schedulePreview();
    });
    const unsubDataExample = stores.$selectedDataExampleId.subscribe(() => {
      schedulePreview();
    });
    schedulePreview();

    const originalDestroy = mounted.destroy.bind(mounted);
    mounted.destroy = () => {
      unsubTemplate();
      unsubDataExample();
      if (previewDebounceTimer) {
        clearTimeout(previewDebounceTimer);
        previewDebounceTimer = null;
      }
      originalDestroy();
    };
  }

  if (saveButton && config.onSave) {
    const saveHandler = config.onSave;
    saveButton.addEventListener("click", () => {
      void saveHandler(mounted.getTemplate())
        .then(() => {
          mounted.getEditor().markAsSaved();
        })
        .catch(() => {
          // EditorController surfaces save errors when mounted.
        });
    });
  }

  return {
    ...mounted,
    destroy(): void {
      if (previewDebounceTimer) {
        clearTimeout(previewDebounceTimer);
        previewDebounceTimer = null;
      }
      clearPreviewObjectUrl();
      mounted.destroy();
      runtimeByShell.delete(shell);
      host.innerHTML = "";
    },
  };
}

function buildEditorAppShell(config: MountEditorAppConfig): string {
  const showThemeSelector = config.ui?.showThemeSelector !== false;
  const showDataExampleSelector = config.ui?.showDataExampleSelector !== false;
  const showPreview = config.ui?.showPreview !== false;
  const saveLabel = config.ui?.labels?.save ?? "Save";
  const previewLabel = config.ui?.labels?.preview ?? "Preview";

  const themeSelect = showThemeSelector
    ? `
      <select
        class="form-select form-select-sm"
        style="width:auto"
        data-action="change->editor#handleThemeChange"
        data-editor-target="themeSelect"
      >
        <option value="">Default theme</option>
      </select>
    `
    : "";

  const dataExampleSelect = showDataExampleSelector
    ? `
      <select
        class="form-select form-select-sm"
        style="width:auto"
        data-action="change->editor#handleDataExampleChange"
        data-editor-target="dataExampleSelect"
      >
        <option value="">No test data</option>
      </select>
    `
    : "";

  const previewButton = showPreview
    ? `
      <button class="btn btn-sm btn-outline-secondary" data-editor-app-action="preview">
        <i class="bi bi-eye"></i> ${previewLabel}
      </button>
      <span data-editor-app-target="previewStatus" class="text-muted small"></span>
    `
    : "";

  const previewPane = showPreview
    ? `
      <div class="ve-preview-pane" data-editor-app-target="previewPane">
        <div class="card">
          <div class="card-body p-0" style="height: 100%; min-height: 520px; position: relative;">
            <div data-editor-app-target="previewEmpty" class="ve-preview-empty text-muted" style="height:100%;">
              Click Preview to render
            </div>
            <iframe data-editor-app-target="previewIframe" title="Preview" style="width:100%;height:100%;border:0;display:none;"></iframe>
          </div>
        </div>
      </div>
    `
    : "";

  return `
    <div class="ve-app-shell" data-editor-app-shell="true" data-controller="editor">
      <div class="ve-app-toolbar d-flex align-items-center gap-2 flex-wrap mb-3">
        <div data-editor-app-target="pluginToolbar" class="d-flex align-items-center gap-2"></div>
        <button class="btn btn-sm btn-outline-secondary" data-action="editor#addBlockToSelected">
          Add to selected
        </button>
        <span class="vr"></span>
        <button class="btn btn-sm btn-outline-secondary" data-editor-app-action="open-page-settings">
          Page Settings
        </button>
        <button class="btn btn-sm btn-outline-secondary" data-editor-app-action="open-document-styles">
          Document Styles
        </button>
        <button class="btn btn-sm btn-outline-secondary" data-editor-app-action="open-block-styles" data-editor-target="blockStylesBtn" disabled>
          Block Styles
        </button>
        <span class="vr"></span>
        <button class="btn btn-sm btn-outline-secondary" data-action="editor#undo" data-editor-target="undoBtn" disabled>Undo</button>
        <button class="btn btn-sm btn-outline-secondary" data-action="editor#redo" data-editor-target="redoBtn" disabled>Redo</button>
        <button class="btn btn-sm btn-outline-primary" data-action="editor#save" data-editor-target="saveBtn">${saveLabel}</button>
        <span data-editor-target="saveStatus" class="save-status"></span>
        ${themeSelect}
        ${dataExampleSelect}
        ${previewButton}
      </div>
      <div class="ve-app-main">
        <div class="ve-editor-pane" data-editor-target="blockContainer"></div>
        ${previewPane}
      </div>

      <dialog data-editor-app-modal="page-settings" class="ve-modal">
        <form method="dialog" class="ve-modal-content">
          <h5 class="mb-3">Page Settings</h5>
          <div class="row g-2">
            <div class="col-6">
              <label class="form-label" for="ve-page-format">Format</label>
              <select id="ve-page-format" class="form-select form-select-sm">
                <option value="A4">A4</option>
                <option value="Letter">Letter</option>
              </select>
            </div>
            <div class="col-6">
              <label class="form-label" for="ve-page-orientation">Orientation</label>
              <select id="ve-page-orientation" class="form-select form-select-sm">
                <option value="portrait">Portrait</option>
                <option value="landscape">Landscape</option>
              </select>
            </div>
            <div class="col-3">
              <label class="form-label" for="ve-margin-top">Top</label>
              <input id="ve-margin-top" class="form-control form-control-sm" type="number" min="0" value="20" />
            </div>
            <div class="col-3">
              <label class="form-label" for="ve-margin-right">Right</label>
              <input id="ve-margin-right" class="form-control form-control-sm" type="number" min="0" value="20" />
            </div>
            <div class="col-3">
              <label class="form-label" for="ve-margin-bottom">Bottom</label>
              <input id="ve-margin-bottom" class="form-control form-control-sm" type="number" min="0" value="20" />
            </div>
            <div class="col-3">
              <label class="form-label" for="ve-margin-left">Left</label>
              <input id="ve-margin-left" class="form-control form-control-sm" type="number" min="0" value="20" />
            </div>
          </div>
          <div class="d-flex justify-content-end gap-2 mt-3">
            <button type="button" class="btn btn-sm btn-outline-secondary" data-editor-app-action="close-page-settings">Cancel</button>
            <button type="button" class="btn btn-sm btn-primary" data-editor-app-action="save-page-settings">Save</button>
          </div>
        </form>
      </dialog>

      <dialog data-editor-app-modal="document-styles" class="ve-modal">
        <form method="dialog" class="ve-modal-content">
          <h5 class="mb-3">Document Styles</h5>
          <div class="mb-2">
            <label class="form-label" for="ve-doc-font-family">Font Family</label>
            <input id="ve-doc-font-family" class="form-control form-control-sm" placeholder="e.g., Inter, Arial, sans-serif" />
          </div>
          <div class="row g-2 mb-2">
            <div class="col-8">
              <label class="form-label" for="ve-doc-font-size-value">Font Size</label>
              <input id="ve-doc-font-size-value" class="form-control form-control-sm" type="number" min="1" step="0.1" value="12" />
            </div>
            <div class="col-4">
              <label class="form-label" for="ve-doc-font-size-unit">Unit</label>
              <select id="ve-doc-font-size-unit" class="form-select form-select-sm">
                <option value="pt">pt</option><option value="px">px</option><option value="em">em</option><option value="rem">rem</option><option value="%">%</option>
              </select>
            </div>
          </div>
          <div class="mb-2">
            <label class="form-label" for="ve-doc-font-weight">Font Weight</label>
            <select id="ve-doc-font-weight" class="form-select form-select-sm">
              <option value="">Default</option><option value="normal">Normal</option><option value="500">500</option><option value="600">600</option><option value="bold">Bold</option>
            </select>
          </div>
          <div class="row g-2 mb-2">
            <div class="col-6">
              <label class="form-label" for="ve-doc-color">Text Color</label>
              <input id="ve-doc-color" class="form-control form-control-sm" placeholder="#000000" />
            </div>
            <div class="col-6">
              <label class="form-label" for="ve-doc-bg-color">Background Color</label>
              <input id="ve-doc-bg-color" class="form-control form-control-sm" placeholder="#ffffff" />
            </div>
          </div>
          <div class="row g-2 mb-2">
            <div class="col-6">
              <label class="form-label" for="ve-doc-line-height">Line Height</label>
              <input id="ve-doc-line-height" class="form-control form-control-sm" type="number" min="0.8" step="0.1" value="1.5" />
            </div>
            <div class="col-6">
              <label class="form-label" for="ve-doc-text-align">Text Align</label>
              <select id="ve-doc-text-align" class="form-select form-select-sm">
                <option value="">Default</option><option value="left">Left</option><option value="center">Center</option><option value="right">Right</option><option value="justify">Justify</option>
              </select>
            </div>
          </div>
          <div class="row g-2 mb-2">
            <div class="col-8">
              <label class="form-label" for="ve-doc-letter-spacing-value">Letter Spacing</label>
              <input id="ve-doc-letter-spacing-value" class="form-control form-control-sm" type="number" step="0.1" />
            </div>
            <div class="col-4">
              <label class="form-label" for="ve-doc-letter-spacing-unit">Unit</label>
              <select id="ve-doc-letter-spacing-unit" class="form-select form-select-sm">
                <option value="px">px</option><option value="em">em</option><option value="rem">rem</option><option value="%">%</option>
              </select>
            </div>
          </div>
          <div class="d-flex justify-content-end gap-2 mt-3">
            <button type="button" class="btn btn-sm btn-outline-secondary" data-editor-app-action="close-document-styles">Cancel</button>
            <button type="button" class="btn btn-sm btn-primary" data-editor-app-action="save-document-styles">Save</button>
          </div>
        </form>
      </dialog>

      <dialog data-editor-app-modal="block-styles" class="ve-modal">
        <form method="dialog" class="ve-modal-content">
          <h5 class="mb-3">Block Styles</h5>
          <div class="mb-2">
            <label class="form-label" for="ve-block-font-size">Font Size</label>
            <input id="ve-block-font-size" class="form-control form-control-sm" placeholder="e.g., 16px" />
          </div>
          <div class="mb-2">
            <label class="form-label" for="ve-block-font-weight">Font Weight</label>
            <select id="ve-block-font-weight" class="form-select form-select-sm">
              <option value="">Default</option><option value="normal">Normal</option><option value="500">500</option><option value="600">600</option><option value="700">700</option><option value="bold">Bold</option>
            </select>
          </div>
          <div class="row g-2 mb-2">
            <div class="col-6">
              <label class="form-label" for="ve-block-color">Text Color</label>
              <input id="ve-block-color" class="form-control form-control-sm" placeholder="#000000" />
            </div>
            <div class="col-6">
              <label class="form-label" for="ve-block-bg-color">Background Color</label>
              <input id="ve-block-bg-color" class="form-control form-control-sm" placeholder="#ffffff" />
            </div>
          </div>
          <div class="mb-2">
            <label class="form-label" for="ve-block-text-align">Text Align</label>
            <select id="ve-block-text-align" class="form-select form-select-sm">
              <option value="">Default</option><option value="left">Left</option><option value="center">Center</option><option value="right">Right</option><option value="justify">Justify</option>
            </select>
          </div>
          <div class="row g-2 mb-2">
            <div class="col-6">
              <label class="form-label" for="ve-block-padding">Padding</label>
              <input id="ve-block-padding" class="form-control form-control-sm" placeholder="e.g., 12px" />
            </div>
            <div class="col-6">
              <label class="form-label" for="ve-block-margin">Margin</label>
              <input id="ve-block-margin" class="form-control form-control-sm" placeholder="e.g., 8px" />
            </div>
          </div>
          <div class="mb-2">
            <label class="form-label" for="ve-block-border-radius">Border Radius</label>
            <input id="ve-block-border-radius" class="form-control form-control-sm" placeholder="e.g., 6px" />
          </div>
          <div class="d-flex justify-content-between mt-3">
            <button type="button" class="btn btn-sm btn-outline-danger" data-editor-app-action="clear-block-styles">Clear</button>
            <div class="d-flex gap-2">
              <button type="button" class="btn btn-sm btn-outline-secondary" data-editor-app-action="close-block-styles">Cancel</button>
              <button type="button" class="btn btn-sm btn-primary" data-editor-app-action="save-block-styles">Save</button>
            </div>
          </div>
        </form>
      </dialog>
    </div>
  `;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderPluginToolbar(
  host: HTMLElement,
  editor: TemplateEditor,
): void {
  const container = host.querySelector<HTMLElement>(
    '[data-editor-app-target="pluginToolbar"]',
  );
  if (!container) return;

  const catalogProvider = (editor as CatalogCapableEditor).getBlockCatalog?.();
  const catalog =
    catalogProvider && catalogProvider.length > 0
      ? catalogProvider
      : buildToolbarCatalogFallback(editor);
  const visibleCatalog = catalog.filter(
    (item) => item.visible && item.addableAtRoot,
  );

  const groups = new Map<string, typeof visibleCatalog>();
  for (const item of visibleCatalog) {
    const list = groups.get(item.group) ?? [];
    list.push(item);
    groups.set(item.group, list);
  }

  const sections: string[] = [];
  for (const [group, items] of groups.entries()) {
    const buttons = items
      .map(
        (item) =>
          `<button class="btn btn-sm btn-outline-secondary" data-action="editor#addBlock" data-block-type="${escapeHtml(item.type)}">${escapeHtml(item.label)}</button>`,
      )
      .join("");

    sections.push(
      `<div class="d-flex align-items-center gap-2"><span class="text-muted small">${escapeHtml(group)}:</span>${buttons}</div>`,
    );
  }

  container.innerHTML = sections.join('<span class="vr"></span>');
}

function buildToolbarCatalogFallback(
  editor: TemplateEditor,
): ToolbarCatalogItem[] {
  const api = editor as CatalogCapableEditor;
  const types = api.getBlockTypes?.() ?? [];

  const entries: ToolbarCatalogItem[] = types.map((type) => {
    const definition = api.getBlockDefinition?.(type);

    return {
      type: type as BlockType,
      label: definition?.label ?? type,
      group: definition?.category ?? "Blocks",
      order: 0,
      visible: true,
      addableAtRoot:
        definition?.constraints.allowedParentTypes === null ||
        (definition?.constraints.allowedParentTypes?.includes("root") ?? true),
    };
  });

  return entries.sort((a, b) => {
    if (a.group !== b.group) return a.group.localeCompare(b.group);
    if (a.order !== b.order) return a.order - b.order;
    return a.label.localeCompare(b.label);
  });
}

function parseValueWithUnit(
  value: string | undefined,
  fallbackUnit: string,
): { value: string; unit: string } {
  if (!value) return { value: "", unit: fallbackUnit };
  const raw = String(value).trim();
  const match = raw.match(/^(-?\d*\.?\d+)\s*(pt|px|em|rem|%)$/i);
  if (match) {
    return { value: match[1], unit: match[2].toLowerCase() };
  }
  const numeric = Number(raw);
  if (!Number.isNaN(numeric)) {
    return { value: String(numeric), unit: fallbackUnit };
  }
  return { value: "", unit: fallbackUnit };
}

function composeValueWithUnit(
  value: string,
  unit: string,
  allowNegative = false,
): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const numeric = Number(trimmed);
  if (Number.isNaN(numeric)) return null;
  if (!allowNegative && numeric <= 0) return null;
  return `${numeric}${unit}`;
}

function showDialog(dialog: HTMLDialogElement): void {
  if (typeof dialog.showModal === "function") {
    dialog.showModal();
  } else {
    dialog.setAttribute("open", "open");
  }
}

function closeDialog(dialog: HTMLDialogElement): void {
  if (typeof dialog.close === "function") {
    dialog.close();
  } else {
    dialog.removeAttribute("open");
  }
}

function setupShellModals(host: HTMLElement, editor: TemplateEditor): void {
  const pageDialog = host.querySelector<HTMLDialogElement>(
    '[data-editor-app-modal="page-settings"]',
  );
  const docDialog = host.querySelector<HTMLDialogElement>(
    '[data-editor-app-modal="document-styles"]',
  );
  const blockDialog = host.querySelector<HTMLDialogElement>(
    '[data-editor-app-modal="block-styles"]',
  );
  if (!pageDialog || !docDialog || !blockDialog) return;

  const byAction = (action: string) =>
    host.querySelector<HTMLElement>(`[data-editor-app-action="${action}"]`);

  const pageFormat = host.querySelector<HTMLSelectElement>("#ve-page-format");
  const pageOrientation = host.querySelector<HTMLSelectElement>(
    "#ve-page-orientation",
  );
  const marginTop = host.querySelector<HTMLInputElement>("#ve-margin-top");
  const marginRight = host.querySelector<HTMLInputElement>("#ve-margin-right");
  const marginBottom =
    host.querySelector<HTMLInputElement>("#ve-margin-bottom");
  const marginLeft = host.querySelector<HTMLInputElement>("#ve-margin-left");

  byAction("open-page-settings")?.addEventListener("click", () => {
    const page = editor.getTemplate().pageSettings;
    pageFormat!.value = page?.format ?? "A4";
    pageOrientation!.value = page?.orientation ?? "portrait";
    marginTop!.value = String(page?.margins?.top ?? 20);
    marginRight!.value = String(page?.margins?.right ?? 20);
    marginBottom!.value = String(page?.margins?.bottom ?? 20);
    marginLeft!.value = String(page?.margins?.left ?? 20);
    showDialog(pageDialog);
  });
  byAction("close-page-settings")?.addEventListener("click", () =>
    closeDialog(pageDialog),
  );
  byAction("save-page-settings")?.addEventListener("click", () => {
    editor.updatePageSettings({
      format: (pageFormat?.value as "A4" | "Letter" | "Custom") ?? "A4",
      orientation:
        (pageOrientation?.value as "portrait" | "landscape") ?? "portrait",
      margins: {
        top: Number(marginTop?.value || 20),
        right: Number(marginRight?.value || 20),
        bottom: Number(marginBottom?.value || 20),
        left: Number(marginLeft?.value || 20),
      },
    });
    closeDialog(pageDialog);
  });

  const docFontFamily = host.querySelector<HTMLInputElement>(
    "#ve-doc-font-family",
  );
  const docFontSizeValue = host.querySelector<HTMLInputElement>(
    "#ve-doc-font-size-value",
  );
  const docFontSizeUnit = host.querySelector<HTMLSelectElement>(
    "#ve-doc-font-size-unit",
  );
  const docFontWeight = host.querySelector<HTMLSelectElement>(
    "#ve-doc-font-weight",
  );
  const docColor = host.querySelector<HTMLInputElement>("#ve-doc-color");
  const docBgColor = host.querySelector<HTMLInputElement>("#ve-doc-bg-color");
  const docLineHeight = host.querySelector<HTMLInputElement>(
    "#ve-doc-line-height",
  );
  const docTextAlign =
    host.querySelector<HTMLSelectElement>("#ve-doc-text-align");
  const docLetterSpacingValue = host.querySelector<HTMLInputElement>(
    "#ve-doc-letter-spacing-value",
  );
  const docLetterSpacingUnit = host.querySelector<HTMLSelectElement>(
    "#ve-doc-letter-spacing-unit",
  );

  byAction("open-document-styles")?.addEventListener("click", () => {
    const styles = editor.getTemplate().documentStyles ?? {};
    const parsedFontSize = parseValueWithUnit(styles.fontSize, "pt");
    const parsedLetterSpacing = parseValueWithUnit(styles.letterSpacing, "px");

    docFontFamily!.value = styles.fontFamily ?? "";
    docFontSizeValue!.value = parsedFontSize.value || "12";
    docFontSizeUnit!.value = parsedFontSize.unit;
    docFontWeight!.value = styles.fontWeight ?? "";
    docColor!.value = styles.color ?? "#000000";
    docBgColor!.value = styles.backgroundColor ?? "";
    docLineHeight!.value = styles.lineHeight ?? "1.5";
    docTextAlign!.value = styles.textAlign ?? "";
    docLetterSpacingValue!.value = parsedLetterSpacing.value;
    docLetterSpacingUnit!.value = parsedLetterSpacing.unit;

    showDialog(docDialog);
  });
  byAction("close-document-styles")?.addEventListener("click", () =>
    closeDialog(docDialog),
  );
  byAction("save-document-styles")?.addEventListener("click", () => {
    const styles: Record<string, string> = {};
    if (docFontFamily?.value.trim())
      styles.fontFamily = docFontFamily.value.trim();
    const fontSize = composeValueWithUnit(
      docFontSizeValue?.value ?? "",
      docFontSizeUnit?.value ?? "pt",
    );
    if (fontSize) styles.fontSize = fontSize;
    if (docFontWeight?.value) styles.fontWeight = docFontWeight.value;
    if (docColor?.value.trim() && docColor.value.trim() !== "#000000")
      styles.color = docColor.value.trim();
    if (docBgColor?.value.trim())
      styles.backgroundColor = docBgColor.value.trim();
    if (docLineHeight?.value.trim())
      styles.lineHeight = docLineHeight.value.trim();
    if (docTextAlign?.value) styles.textAlign = docTextAlign.value;
    const letterSpacing = composeValueWithUnit(
      docLetterSpacingValue?.value ?? "",
      docLetterSpacingUnit?.value ?? "px",
      true,
    );
    if (letterSpacing) styles.letterSpacing = letterSpacing;

    editor.updateDocumentStyles(styles);
    closeDialog(docDialog);
  });

  const blockFontSize = host.querySelector<HTMLInputElement>(
    "#ve-block-font-size",
  );
  const blockFontWeight = host.querySelector<HTMLSelectElement>(
    "#ve-block-font-weight",
  );
  const blockColor = host.querySelector<HTMLInputElement>("#ve-block-color");
  const blockTextAlign = host.querySelector<HTMLSelectElement>(
    "#ve-block-text-align",
  );
  const blockPadding =
    host.querySelector<HTMLInputElement>("#ve-block-padding");
  const blockMargin = host.querySelector<HTMLInputElement>("#ve-block-margin");
  const blockBgColor =
    host.querySelector<HTMLInputElement>("#ve-block-bg-color");
  const blockBorderRadius = host.querySelector<HTMLInputElement>(
    "#ve-block-border-radius",
  );

  byAction("open-block-styles")?.addEventListener("click", () => {
    const selected = editor.getSelectedBlock();
    if (!selected) return;
    const styles = selected.styles ?? {};

    blockFontSize!.value = String(styles.fontSize ?? "");
    blockFontWeight!.value = String(styles.fontWeight ?? "");
    blockColor!.value = String(styles.color ?? "");
    blockTextAlign!.value = String(styles.textAlign ?? "");
    blockPadding!.value = String(styles.padding ?? "");
    blockMargin!.value = String(styles.margin ?? "");
    blockBgColor!.value = String(styles.backgroundColor ?? "");
    blockBorderRadius!.value = String(styles.borderRadius ?? "");

    showDialog(blockDialog);
  });
  byAction("close-block-styles")?.addEventListener("click", () =>
    closeDialog(blockDialog),
  );
  byAction("clear-block-styles")?.addEventListener("click", () => {
    blockFontSize!.value = "";
    blockFontWeight!.value = "";
    blockColor!.value = "";
    blockTextAlign!.value = "";
    blockPadding!.value = "";
    blockMargin!.value = "";
    blockBgColor!.value = "";
    blockBorderRadius!.value = "";
  });
  byAction("save-block-styles")?.addEventListener("click", () => {
    const selected = editor.getSelectedBlock();
    if (!selected) return;

    const styles: Record<string, string> = {};
    if (blockFontSize?.value.trim())
      styles.fontSize = blockFontSize.value.trim();
    if (blockFontWeight?.value) styles.fontWeight = blockFontWeight.value;
    if (blockColor?.value.trim()) styles.color = blockColor.value.trim();
    if (blockTextAlign?.value) styles.textAlign = blockTextAlign.value;
    if (blockPadding?.value.trim()) styles.padding = blockPadding.value.trim();
    if (blockMargin?.value.trim()) styles.margin = blockMargin.value.trim();
    if (blockBgColor?.value.trim())
      styles.backgroundColor = blockBgColor.value.trim();
    if (blockBorderRadius?.value.trim())
      styles.borderRadius = blockBorderRadius.value.trim();

    editor.updateBlock(selected.id, { styles });
    closeDialog(blockDialog);
  });
}

/** Resolve a container string or element to an HTMLElement. */
function resolveContainer(container: string | HTMLElement): HTMLElement {
  if (typeof container === "string") {
    const el = document.querySelector<HTMLElement>(container);
    if (!el) throw new Error(`Container not found: ${container}`);
    return el;
  }
  return container;
}
