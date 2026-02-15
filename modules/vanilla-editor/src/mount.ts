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
  CSSStyles,
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

type BlockStyleOption = {
  value: string;
  label: string;
};

type BlockStyleFieldConfig = {
  key: keyof CSSStyles & string;
  label: string;
  group: "Typography" | "Spacing" | "Colors" | "Borders" | "Layout";
  control?: "text" | "select" | "color";
  placeholder?: string;
  options?: BlockStyleOption[];
};

const BLOCK_STYLE_FIELDS: readonly BlockStyleFieldConfig[] = [
  {
    key: "fontFamily",
    label: "Font Family",
    group: "Typography",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "Helvetica", label: "sans-serif (Helvetica)" },
      { value: "Times-Roman", label: "serif (Times-Roman)" },
      { value: "Courier", label: "monospace (Courier)" },
    ],
  },
  {
    key: "fontSize",
    label: "Font Size",
    group: "Typography",
    placeholder: "e.g., 16px",
  },
  {
    key: "fontWeight",
    label: "Font Weight",
    group: "Typography",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "normal", label: "Normal" },
      { value: "500", label: "500" },
      { value: "600", label: "600" },
      { value: "700", label: "700" },
      { value: "bold", label: "Bold" },
    ],
  },
  {
    key: "fontStyle",
    label: "Font Style",
    group: "Typography",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "normal", label: "Normal" },
      { value: "italic", label: "Italic" },
      { value: "oblique", label: "Oblique" },
    ],
  },
  {
    key: "lineHeight",
    label: "Line Height",
    group: "Typography",
    placeholder: "e.g., 1.5",
  },
  {
    key: "letterSpacing",
    label: "Letter Spacing",
    group: "Typography",
    placeholder: "e.g., 0.02em",
  },
  {
    key: "textAlign",
    label: "Text Align",
    group: "Typography",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "left", label: "Left" },
      { value: "center", label: "Center" },
      { value: "right", label: "Right" },
      { value: "justify", label: "Justify" },
    ],
  },
  {
    key: "textDecoration",
    label: "Text Decoration",
    group: "Typography",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "none", label: "None" },
      { value: "underline", label: "Underline" },
      { value: "line-through", label: "Line Through" },
    ],
  },
  {
    key: "whiteSpace",
    label: "White Space",
    group: "Typography",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "normal", label: "Normal" },
      { value: "nowrap", label: "No Wrap" },
    ],
  },
  {
    key: "wordBreak",
    label: "Word Break",
    group: "Typography",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "normal", label: "Normal" },
      { value: "break-word", label: "Break Word" },
      { value: "break-all", label: "Break All" },
    ],
  },
  {
    key: "padding",
    label: "Padding",
    group: "Spacing",
    placeholder: "e.g., 12px 8px",
  },
  {
    key: "paddingTop",
    label: "Padding Top",
    group: "Spacing",
    placeholder: "e.g., 12px",
  },
  {
    key: "paddingRight",
    label: "Padding Right",
    group: "Spacing",
    placeholder: "e.g., 12px",
  },
  {
    key: "paddingBottom",
    label: "Padding Bottom",
    group: "Spacing",
    placeholder: "e.g., 12px",
  },
  {
    key: "paddingLeft",
    label: "Padding Left",
    group: "Spacing",
    placeholder: "e.g., 12px",
  },
  {
    key: "marginTop",
    label: "Margin Top",
    group: "Spacing",
    placeholder: "e.g., 8px",
  },
  {
    key: "marginRight",
    label: "Margin Right",
    group: "Spacing",
    placeholder: "e.g., 8px",
  },
  {
    key: "marginBottom",
    label: "Margin Bottom",
    group: "Spacing",
    placeholder: "e.g., 8px",
  },
  {
    key: "marginLeft",
    label: "Margin Left",
    group: "Spacing",
    placeholder: "e.g., 8px",
  },
  {
    key: "color",
    label: "Text Color",
    group: "Colors",
    control: "color",
  },
  {
    key: "backgroundColor",
    label: "Background Color",
    group: "Colors",
    control: "color",
  },
  {
    key: "opacity",
    label: "Opacity",
    group: "Colors",
    placeholder: "e.g., 0.85",
  },
  {
    key: "borderWidth",
    label: "Border Width",
    group: "Borders",
    placeholder: "e.g., 1px",
  },
  {
    key: "borderStyle",
    label: "Border Style",
    group: "Borders",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "none", label: "None" },
      { value: "solid", label: "Solid" },
      { value: "dashed", label: "Dashed" },
      { value: "dotted", label: "Dotted" },
      { value: "double", label: "Double" },
    ],
  },
  {
    key: "borderColor",
    label: "Border Color",
    group: "Borders",
    control: "color",
  },
  {
    key: "border",
    label: "Border",
    group: "Borders",
    placeholder: "e.g., 1px solid #cccccc",
  },
  {
    key: "borderTop",
    label: "Border Top",
    group: "Borders",
    placeholder: "e.g., 2px solid #2563eb",
  },
  {
    key: "borderRight",
    label: "Border Right",
    group: "Borders",
    placeholder: "e.g., 1px solid #cccccc",
  },
  {
    key: "borderBottom",
    label: "Border Bottom",
    group: "Borders",
    placeholder: "e.g., 1px solid #cccccc",
  },
  {
    key: "borderLeft",
    label: "Border Left",
    group: "Borders",
    placeholder: "e.g., 3px solid #2563eb",
  },
  {
    key: "borderRadius",
    label: "Border Radius",
    group: "Borders",
    placeholder: "e.g., 6px",
  },
  {
    key: "width",
    label: "Width",
    group: "Layout",
    placeholder: "e.g., 100%",
  },
  {
    key: "minWidth",
    label: "Min Width",
    group: "Layout",
    placeholder: "e.g., 240px",
  },
  {
    key: "maxWidth",
    label: "Max Width",
    group: "Layout",
    placeholder: "e.g., 720px",
  },
  {
    key: "height",
    label: "Height",
    group: "Layout",
    placeholder: "e.g., auto",
  },
  {
    key: "minHeight",
    label: "Min Height",
    group: "Layout",
    placeholder: "e.g., 40px",
  },
  {
    key: "maxHeight",
    label: "Max Height",
    group: "Layout",
    placeholder: "e.g., 320px",
  },
  {
    key: "overflow",
    label: "Overflow",
    group: "Layout",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "visible", label: "Visible" },
      { value: "hidden", label: "Hidden" },
      { value: "fit", label: "Fit" },
    ],
  },
  {
    key: "overflowX",
    label: "Overflow X",
    group: "Layout",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "visible", label: "Visible" },
      { value: "hidden", label: "Hidden" },
      { value: "fit", label: "Fit" },
    ],
  },
  {
    key: "overflowY",
    label: "Overflow Y",
    group: "Layout",
    control: "select",
    options: [
      { value: "", label: "Default" },
      { value: "visible", label: "Visible" },
      { value: "hidden", label: "Hidden" },
      { value: "fit", label: "Fit" },
    ],
  },
] as const;

const BLOCK_STYLE_GROUP_ORDER: readonly BlockStyleFieldConfig["group"][] = [
  "Typography",
  "Spacing",
  "Colors",
  "Borders",
  "Layout",
];

const COLOR_INPUT_DEFAULT = "#000000";

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
  setupShellSidebar(host, editor);

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
        <button class="btn btn-sm btn-outline-secondary" data-action="editor#undo" data-editor-target="undoBtn" disabled>Undo</button>
        <button class="btn btn-sm btn-outline-secondary" data-action="editor#redo" data-editor-target="redoBtn" disabled>Redo</button>
        <button class="btn btn-sm btn-outline-primary" data-action="editor#save" data-editor-target="saveBtn">${saveLabel}</button>
        <span data-editor-target="saveStatus" class="save-status"></span>
        ${themeSelect}
        ${dataExampleSelect}
        ${previewButton}
      </div>
      <div class="ve-app-main ${showPreview ? "ve-with-preview" : "ve-no-preview"}">
        <aside class="ve-style-sidebar" data-editor-app-target="styleSidebar">
          <div class="ve-sidebar-header mb-2">
            <h6 class="ve-sidebar-title mb-0">Styles</h6>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary ve-sidebar-toggle"
              data-editor-app-action="toggle-style-sidebar"
              aria-expanded="true"
              title="Collapse styles sidebar"
            >
              <i class="bi bi-layout-sidebar-inset"></i>
            </button>
          </div>

          <details class="ve-style-section" open>
            <summary>Page Styles</summary>
            <div class="ve-style-section-body">
              <details class="ve-style-group" open>
                <summary>Layout</summary>
                <div class="ve-style-group-body">
                  <div class="row g-2 mb-2">
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
                  </div>
                  <div class="row g-2 mb-2">
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
                </div>
              </details>
            </div>
          </details>

          <div class="ve-style-tabs mt-2">
            <button type="button" class="btn btn-sm btn-outline-secondary ve-style-tab is-active" data-editor-app-tab="document">Document Styles</button>
            <button type="button" class="btn btn-sm btn-outline-secondary ve-style-tab" data-editor-app-tab="block">Block Styles</button>
          </div>

          <section class="ve-style-panel is-active" data-editor-app-panel="document">
            <details class="ve-style-group" open>
              <summary>Typography</summary>
              <div class="ve-style-group-body">
                <div class="mb-2">
                  <label class="form-label" for="ve-doc-font-family">Font Family</label>
                  <select id="ve-doc-font-family" class="form-select form-select-sm">
                    <option value="">Default</option>
                    <option value="Helvetica">sans-serif (Helvetica)</option>
                    <option value="Times-Roman">serif (Times-Roman)</option>
                    <option value="Courier">monospace (Courier)</option>
                  </select>
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
              </div>
            </details>

            <details class="ve-style-group">
              <summary>Colors</summary>
              <div class="ve-style-group-body">
                <div class="row g-2 mb-2">
                  <div class="col-6">
                    <label class="form-label" for="ve-doc-color">Text Color</label>
                    <div class="input-group input-group-sm">
                      <input id="ve-doc-color" type="color" class="form-control form-control-color" value="${COLOR_INPUT_DEFAULT}" data-ve-color-empty="true" />
                      <button type="button" class="btn btn-outline-secondary" data-ve-color-clear-for="ve-doc-color">Clear</button>
                    </div>
                  </div>
                  <div class="col-6">
                    <label class="form-label" for="ve-doc-bg-color">Background Color</label>
                    <div class="input-group input-group-sm">
                      <input id="ve-doc-bg-color" type="color" class="form-control form-control-color" value="${COLOR_INPUT_DEFAULT}" data-ve-color-empty="true" />
                      <button type="button" class="btn btn-outline-secondary" data-ve-color-clear-for="ve-doc-bg-color">Clear</button>
                    </div>
                  </div>
                </div>
              </div>
            </details>
          </section>

          <section class="ve-style-panel" data-editor-app-panel="block">
            <p class="text-muted small mb-2" data-editor-app-target="noBlockSelected">No Block Selected</p>
            <fieldset class="ve-block-style-fields" data-editor-app-target="blockStyleFields">
              ${buildBlockStyleGroupsMarkup()}
              <button type="button" class="btn btn-sm btn-outline-danger mt-2" data-editor-app-action="clear-block-styles">Clear</button>
            </fieldset>
          </section>
        </aside>

        <div class="ve-editor-pane" data-editor-target="blockContainer"></div>
        ${previewPane}
      </div>
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

function toDomIdSegment(property: string): string {
  return property.replace(/[A-Z]/g, (match) => `-${match.toLowerCase()}`);
}

function getBlockStyleFieldId(key: string): string {
  return `ve-block-style-${toDomIdSegment(key)}`;
}

function isHexColorValue(value: string): boolean {
  return /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(value.trim());
}

function normalizeHexColorValue(value: string): string {
  const trimmed = value.trim().toLowerCase();
  if (/^#[0-9a-f]{6}$/.test(trimmed)) return trimmed;
  if (!/^#[0-9a-f]{3}$/.test(trimmed)) return COLOR_INPUT_DEFAULT;
  const [, r, g, b] = trimmed;
  return `#${r}${r}${g}${g}${b}${b}`;
}

function isColorInput(
  input: HTMLInputElement | HTMLSelectElement,
): input is HTMLInputElement {
  return input instanceof HTMLInputElement && input.type === "color";
}

function setColorInputState(
  input: HTMLInputElement,
  rawColor: string | null | undefined,
): void {
  const color = rawColor ? rawColor.trim() : "";
  if (isHexColorValue(color)) {
    input.value = normalizeHexColorValue(color);
    input.dataset.veColorEmpty = "false";
    return;
  }

  input.value = COLOR_INPUT_DEFAULT;
  input.dataset.veColorEmpty = "true";
}

function getColorInputStyleValue(input: HTMLInputElement): string | undefined {
  if (input.dataset.veColorEmpty === "true") return undefined;
  return input.value.trim() || undefined;
}

function buildBlockStyleFieldMarkup(field: BlockStyleFieldConfig): string {
  const id = getBlockStyleFieldId(field.key);
  const control = field.control ?? "text";
  if (control === "color") {
    return `
      <div class="mb-2">
        <label class="form-label" for="${id}">${escapeHtml(field.label)}</label>
        <div class="input-group input-group-sm">
          <input id="${id}" type="color" class="form-control form-control-color" value="${COLOR_INPUT_DEFAULT}" data-ve-color-empty="true" />
          <button type="button" class="btn btn-outline-secondary" data-ve-color-clear-for="${id}">Clear</button>
        </div>
      </div>
    `;
  }

  if (control === "select") {
    const options = (field.options ?? [])
      .map(
        (option) =>
          `<option value="${escapeHtml(option.value)}">${escapeHtml(option.label)}</option>`,
      )
      .join("");

    return `
      <div class="mb-2">
        <label class="form-label" for="${id}">${escapeHtml(field.label)}</label>
        <select id="${id}" class="form-select form-select-sm">${options}</select>
      </div>
    `;
  }

  const placeholder = field.placeholder
    ? ` placeholder="${escapeHtml(field.placeholder)}"`
    : "";

  return `
    <div class="mb-2">
      <label class="form-label" for="${id}">${escapeHtml(field.label)}</label>
      <input id="${id}" class="form-control form-control-sm"${placeholder} />
    </div>
  `;
}

function buildBlockStyleGroupsMarkup(): string {
  const grouped = new Map<BlockStyleFieldConfig["group"], BlockStyleFieldConfig[]>();
  for (const group of BLOCK_STYLE_GROUP_ORDER) {
    grouped.set(group, []);
  }
  for (const field of BLOCK_STYLE_FIELDS) {
    grouped.get(field.group)?.push(field);
  }

  return BLOCK_STYLE_GROUP_ORDER.map((group, index) => {
    const fields = grouped.get(group) ?? [];
    if (fields.length === 0) return "";
    const fieldsMarkup = fields.map((field) => buildBlockStyleFieldMarkup(field)).join("");

    return `
      <details class="ve-style-group" ${index < 4 ? "open" : ""}>
        <summary>${escapeHtml(group)}</summary>
        <div class="ve-style-group-body">${fieldsMarkup}</div>
      </details>
    `;
  }).join("");
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

function setupShellSidebar(host: HTMLElement, editor: TemplateEditor): void {
  const appMain = host.querySelector<HTMLElement>(".ve-app-main");
  const styleSidebar = host.querySelector<HTMLElement>(
    '[data-editor-app-target="styleSidebar"]',
  );
  const toggleSidebarButton = host.querySelector<HTMLButtonElement>(
    '[data-editor-app-action="toggle-style-sidebar"]',
  );
  const documentTabButton = host.querySelector<HTMLButtonElement>(
    '[data-editor-app-tab="document"]',
  );
  const blockTabButton = host.querySelector<HTMLButtonElement>(
    '[data-editor-app-tab="block"]',
  );
  const documentPanel = host.querySelector<HTMLElement>(
    '[data-editor-app-panel="document"]',
  );
  const blockPanel = host.querySelector<HTMLElement>(
    '[data-editor-app-panel="block"]',
  );
  const noBlockSelected = host.querySelector<HTMLElement>(
    '[data-editor-app-target="noBlockSelected"]',
  );
  const blockStyleFields = host.querySelector<HTMLFieldSetElement>(
    '[data-editor-app-target="blockStyleFields"]',
  );
  const blockContainer = host.querySelector<HTMLElement>(
    '[data-editor-target="blockContainer"]',
  );
  if (
    !documentTabButton ||
    !blockTabButton ||
    !documentPanel ||
    !blockPanel ||
    !noBlockSelected ||
    !blockStyleFields ||
    !blockContainer ||
    !appMain ||
    !styleSidebar ||
    !toggleSidebarButton
  ) {
    return;
  }

  const toggleSidebar = (): void => {
    const collapsed = appMain.classList.toggle("ve-sidebar-collapsed");
    styleSidebar.classList.toggle("is-collapsed", collapsed);
    toggleSidebarButton.setAttribute("aria-expanded", String(!collapsed));
    toggleSidebarButton.title = collapsed
      ? "Expand styles sidebar"
      : "Collapse styles sidebar";
  };

  toggleSidebarButton.addEventListener("click", toggleSidebar);

  const pageFormat = host.querySelector<HTMLSelectElement>("#ve-page-format");
  const pageOrientation = host.querySelector<HTMLSelectElement>(
    "#ve-page-orientation",
  );
  const marginTop = host.querySelector<HTMLInputElement>("#ve-margin-top");
  const marginRight = host.querySelector<HTMLInputElement>("#ve-margin-right");
  const marginBottom =
    host.querySelector<HTMLInputElement>("#ve-margin-bottom");
  const marginLeft = host.querySelector<HTMLInputElement>("#ve-margin-left");

  const docFontFamily = host.querySelector<HTMLSelectElement>(
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

  const blockStyleInputs = new Map<
    BlockStyleFieldConfig["key"],
    HTMLInputElement | HTMLSelectElement
  >();
  for (const field of BLOCK_STYLE_FIELDS) {
    const input = host.querySelector<HTMLInputElement | HTMLSelectElement>(
      `#${getBlockStyleFieldId(field.key)}`,
    );
    if (!input) return;
    blockStyleInputs.set(field.key, input);
  }

  if (
    !pageFormat ||
    !pageOrientation ||
    !marginTop ||
    !marginRight ||
    !marginBottom ||
    !marginLeft ||
    !docFontFamily ||
    !docFontSizeValue ||
    !docFontSizeUnit ||
    !docFontWeight ||
    !docColor ||
    !docBgColor ||
    !docLineHeight ||
    !docTextAlign ||
    !docLetterSpacingValue ||
    !docLetterSpacingUnit
  ) {
    return;
  }

  type StyleTab = "document" | "block";
  let pageTimer: ReturnType<typeof setTimeout> | null = null;
  let docTimer: ReturnType<typeof setTimeout> | null = null;
  let blockTimer: ReturnType<typeof setTimeout> | null = null;
  let pendingBlockId: string | null = null;

  const setActiveTab = (tab: StyleTab): void => {
    documentTabButton.classList.toggle("is-active", tab === "document");
    blockTabButton.classList.toggle("is-active", tab === "block");
    documentPanel.classList.toggle("is-active", tab === "document");
    blockPanel.classList.toggle("is-active", tab === "block");
  };

  const syncPageInputs = (): void => {
    const page = editor.getTemplate().pageSettings;
    pageFormat.value = page?.format ?? "A4";
    pageOrientation.value = page?.orientation ?? "portrait";
    marginTop.value = String(page?.margins?.top ?? 20);
    marginRight.value = String(page?.margins?.right ?? 20);
    marginBottom.value = String(page?.margins?.bottom ?? 20);
    marginLeft.value = String(page?.margins?.left ?? 20);
  };

  const syncDocumentInputs = (): void => {
    const styles = editor.getTemplate().documentStyles ?? {};
    const parsedFontSize = parseValueWithUnit(styles.fontSize, "pt");
    const parsedLetterSpacing = parseValueWithUnit(styles.letterSpacing, "px");

    docFontFamily.value = styles.fontFamily ?? "";
    docFontSizeValue.value = parsedFontSize.value || "12";
    docFontSizeUnit.value = parsedFontSize.unit;
    docFontWeight.value = styles.fontWeight ?? "";
    setColorInputState(docColor, styles.color ?? null);
    setColorInputState(docBgColor, styles.backgroundColor ?? null);
    docLineHeight.value = styles.lineHeight ?? "1.5";
    docTextAlign.value = styles.textAlign ?? "";
    docLetterSpacingValue.value = parsedLetterSpacing.value;
    docLetterSpacingUnit.value = parsedLetterSpacing.unit;
  };

  const clearBlockInputs = (): void => {
    for (const input of blockStyleInputs.values()) {
      if (isColorInput(input)) {
        setColorInputState(input, null);
      } else {
        input.value = "";
      }
    }
  };

  const syncBlockInputs = (): void => {
    const selected = editor.getSelectedBlock();
    if (!selected) {
      noBlockSelected.style.display = "block";
      blockStyleFields.disabled = true;
      clearBlockInputs();
      return;
    }

    noBlockSelected.style.display = "none";
    blockStyleFields.disabled = false;

    const styles = selected.styles ?? {};
    for (const field of BLOCK_STYLE_FIELDS) {
      const input = blockStyleInputs.get(field.key);
      if (!input) continue;

      const value = styles[field.key as keyof typeof styles];

      if (isColorInput(input)) {
        setColorInputState(
          input,
          value === undefined || value === null ? null : String(value),
        );
      } else {
        input.value = value === undefined || value === null ? "" : String(value);
      }
    }
  };

  const applyPageSettings = (): void => {
    editor.updatePageSettings({
      format: (pageFormat.value as "A4" | "Letter" | "Custom") ?? "A4",
      orientation:
        (pageOrientation.value as "portrait" | "landscape") ?? "portrait",
      margins: {
        top: Number(marginTop.value || 20),
        right: Number(marginRight.value || 20),
        bottom: Number(marginBottom.value || 20),
        left: Number(marginLeft.value || 20),
      },
    });
  };

  const applyDocumentStyles = (): void => {
    const styles: Record<string, string> = {};
    if (docFontFamily.value.trim()) styles.fontFamily = docFontFamily.value.trim();
    const fontSize = composeValueWithUnit(docFontSizeValue.value, docFontSizeUnit.value);
    if (fontSize) styles.fontSize = fontSize;
    if (docFontWeight.value) styles.fontWeight = docFontWeight.value;
    const docTextColor = getColorInputStyleValue(docColor);
    if (docTextColor && docTextColor !== "#000000") {
      styles.color = docTextColor;
    }
    const docBackgroundColor = getColorInputStyleValue(docBgColor);
    if (docBackgroundColor) styles.backgroundColor = docBackgroundColor;
    if (docLineHeight.value.trim()) styles.lineHeight = docLineHeight.value.trim();
    if (docTextAlign.value) styles.textAlign = docTextAlign.value;
    const letterSpacing = composeValueWithUnit(
      docLetterSpacingValue.value,
      docLetterSpacingUnit.value,
      true,
    );
    if (letterSpacing) styles.letterSpacing = letterSpacing;

    editor.setDocumentStyles(styles);
  };

  const applyBlockStyles = (expectedBlockId: string | null): void => {
    if (!expectedBlockId) return;
    const selected = editor.getSelectedBlock();
    if (!selected || selected.id !== expectedBlockId) return;

    const nextStyles: Record<string, string | number | undefined> = {};

    for (const field of BLOCK_STYLE_FIELDS) {
      const input = blockStyleInputs.get(field.key);
      if (!input) continue;
      if (isColorInput(input)) {
        nextStyles[field.key] = getColorInputStyleValue(input);
        continue;
      }

      const rawValue = input.value.trim();
      nextStyles[field.key] = rawValue || undefined;
    }

    const normalized = Object.fromEntries(
      Object.entries(nextStyles).filter(([, value]) => value !== undefined),
    );
    editor.updateBlock(selected.id, { styles: normalized });
  };

  const debounce = (
    timerRef: "page" | "doc" | "block",
    callback: () => void,
    blockId?: string | null,
  ): void => {
    const clearTimer = (timer: ReturnType<typeof setTimeout> | null): null => {
      if (timer) clearTimeout(timer);
      return null;
    };

    if (timerRef === "page") {
      pageTimer = clearTimer(pageTimer);
      pageTimer = setTimeout(() => {
        pageTimer = null;
        callback();
      }, 250);
      return;
    }

    if (timerRef === "doc") {
      docTimer = clearTimer(docTimer);
      docTimer = setTimeout(() => {
        docTimer = null;
        callback();
      }, 250);
      return;
    }

    blockTimer = clearTimer(blockTimer);
    pendingBlockId = blockId ?? null;
    blockTimer = setTimeout(() => {
      blockTimer = null;
      callback();
    }, 250);
  };

  const flushTimers = (): void => {
    if (pageTimer) {
      clearTimeout(pageTimer);
      pageTimer = null;
      applyPageSettings();
    }
    if (docTimer) {
      clearTimeout(docTimer);
      docTimer = null;
      applyDocumentStyles();
    }
    if (blockTimer) {
      clearTimeout(blockTimer);
      blockTimer = null;
      applyBlockStyles(pendingBlockId);
      pendingBlockId = null;
    }
  };

  documentTabButton.addEventListener("click", () => setActiveTab("document"));
  blockTabButton.addEventListener("click", () => setActiveTab("block"));

  blockContainer.addEventListener("click", (event) => {
    const target = event.target as HTMLElement;
    if (target.closest("[data-block-id]")) return;
    editor.selectBlock(null);
  });

  const onPageInput = () => debounce("page", applyPageSettings);
  const onDocInput = () => debounce("doc", applyDocumentStyles);
  const onBlockInput = () => {
    const selectedId = editor.getState().selectedBlockId;
    debounce("block", () => applyBlockStyles(selectedId), selectedId);
  };

  for (const input of [docColor, docBgColor]) {
    const markTouched = () => {
      input.dataset.veColorEmpty = "false";
    };
    input.addEventListener("input", markTouched);
    input.addEventListener("change", markTouched);
  }

  host
    .querySelectorAll<HTMLButtonElement>("[data-ve-color-clear-for]")
    .forEach((button) => {
      button.addEventListener("click", () => {
        const inputId = button.getAttribute("data-ve-color-clear-for");
        if (!inputId) return;
        const input = host.querySelector<HTMLInputElement>(`#${inputId}`);
        if (!input || input.type !== "color") return;
        setColorInputState(input, null);
        if (inputId.startsWith("ve-doc-")) {
          onDocInput();
          return;
        }
        onBlockInput();
      });
    });

  for (const input of [
    pageFormat,
    pageOrientation,
    marginTop,
    marginRight,
    marginBottom,
    marginLeft,
  ]) {
    input.addEventListener("input", onPageInput);
    input.addEventListener("change", onPageInput);
    input.addEventListener("blur", flushTimers);
  }

  for (const input of [
    docFontFamily,
    docFontSizeValue,
    docFontSizeUnit,
    docFontWeight,
    docColor,
    docBgColor,
    docLineHeight,
    docTextAlign,
    docLetterSpacingValue,
    docLetterSpacingUnit,
  ]) {
    input.addEventListener("input", onDocInput);
    input.addEventListener("change", onDocInput);
    input.addEventListener("blur", flushTimers);
  }

  for (const input of blockStyleInputs.values()) {
    if (isColorInput(input)) {
      const markTouched = () => {
        input.dataset.veColorEmpty = "false";
      };
      input.addEventListener("input", markTouched);
      input.addEventListener("change", markTouched);
    }
    input.addEventListener("input", onBlockInput);
    input.addEventListener("change", onBlockInput);
    input.addEventListener("blur", flushTimers);
  }

  host
    .querySelector<HTMLElement>('[data-editor-app-action="clear-block-styles"]')
    ?.addEventListener("click", () => {
      clearBlockInputs();
      const selectedId = editor.getState().selectedBlockId;
      applyBlockStyles(selectedId);
    });

  editor.subscribe((state) => {
    if (state.selectedBlockId) {
      setActiveTab("block");
    } else {
      setActiveTab("document");
    }
    syncBlockInputs();
  });

  syncPageInputs();
  syncDocumentInputs();
  syncBlockInputs();
  setActiveTab(editor.getState().selectedBlockId ? "block" : "document");
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
