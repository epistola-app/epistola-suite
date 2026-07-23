/**
 * @module @epistola/editor
 *
 * Epistola Template Editor — Lit-based editor with headless engine.
 *
 * Public API:
 *   mountEditor(options)  → EditorInstance
 */

import './editor.css';
import './ui/EpistolaEditor.js';
import './ui/quality-plugin.js';
import type { TemplateDocument, NodeId, SlotId } from './types/index.js';
import type { FetchPreviewFn } from './ui/preview-service.js';
import type { EditorPlugin } from './plugins/types.js';
import { createDefaultRegistry } from './engine/registry.js';
import type { EditorFeatures } from './engine/feature-flags.js';
import { createImageDefinition } from './components/image/image-registration.js';
import type { AssetInfo, CatalogInfo } from './components/image/asset-picker-dialog.js';
import { setFontCatalog, type FontInfo } from './engine/font-catalog.js';
import { createStencilDefinition } from './components/stencil/stencil-registration.js';
import { collectStencilUpgradeRefs } from './components/stencil/upgrade-refs.js';
import type { StencilCallbacks } from './components/stencil/types.js';
import { validateCoreShortcutRegistriesOnStartup } from './shortcuts/startup-validation.js';
import { nanoid } from 'nanoid';

validateCoreShortcutRegistriesOnStartup();

export type { TemplateDocument, Node, Slot, NodeId, SlotId } from './types/index.js';
export type { AssetInfo, CatalogInfo } from './components/image/asset-picker-dialog.js';
export type { FontInfo } from './engine/font-catalog.js';
export type {
  StencilCallbacks,
  StencilSummary,
  StencilVersionInfo,
  SearchStencilsFn,
  GetStencilVersionFn,
  CheckStencilUpgradesFn,
  CreateStencilFn,
  UpdateStencilFn,
  StartEditingFn,
  PublishDraftFn,
  ListStencilVersionsFn,
  StencilVersionSummary,
} from './components/stencil/types.js';
export { EditorEngine } from './engine/EditorEngine.js';
export { createQualityPlugin } from './ui/quality-plugin.js';
export { createQualityEditorPlugin } from './ui/quality-plugin.js';
export type {
  QualityFinding,
  QualityPanelData,
  QualityPluginOptions,
} from './ui/quality-plugin.js';
export { loadEditorPlugins } from './plugins/plugin-loader.js';
export type {
  EditorPluginDescriptor,
  EditorPluginFactory,
  EditorPluginFactoryContext,
  EditorPluginLoadOptions,
} from './plugins/plugin-loader.js';
export { createDefaultRegistry, ComponentRegistry } from './engine/registry.js';
export type {
  EditorPlugin,
  SidebarTabContribution,
  ToolbarAction,
  PluginContext,
  PluginDisposeFn,
} from './plugins/types.js';
export type { PluginShortcutContribution } from './shortcuts/plugin-registry.js';
export {
  definePluginShortcutContribution,
  validatePluginShortcutContribution,
} from './shortcuts/plugin-registry.js';
export { validateShortcutRegistriesOnStartup } from './shortcuts/startup-validation.js';

// ---------------------------------------------------------------------------
// Public mount API
// ---------------------------------------------------------------------------

export interface EditorOptions {
  /** DOM element to mount the editor into */
  container: HTMLElement;
  /** Initial template document (node/slot model) */
  template?: TemplateDocument;
  /** Callback when the template is saved */
  onSave?: (template: TemplateDocument) => Promise<void>;
  /** JSON Schema describing the data model (for expression autocomplete) */
  dataModel?: object;
  /** Example data objects for previewing expressions */
  dataExamples?: object[];
  /** Callback to fetch a PDF preview. Host page owns the HTTP call; editor owns debounce/abort. */
  onFetchPreview?: FetchPreviewFn;
  /** Optional plugins that extend the editor with additional sidebar tabs, toolbar actions, etc. */
  plugins?: EditorPlugin[];
  /** Optional image block support with asset management callbacks. */
  imageOptions?: {
    /** The template's own catalog — the picker's default selection. */
    defaultCatalogKey: string;
    listCatalogs: () => Promise<CatalogInfo[]>;
    listAssets: (catalogKey: string) => Promise<AssetInfo[]>;
    uploadAsset: (file: File, catalogKey: string) => Promise<AssetInfo>;
    contentUrlPattern: string;
  };
  /**
   * Optional backend-driven font picker. The host fetches the tenant's font
   * catalog (bundled `system` fonts + customer fonts in the editing
   * catalog); the editor builds the `fontFamily` options and injects the
   * matching `@font-face` rules from it.
   */
  fontOptions?: {
    listFonts: () => Promise<FontInfo[]>;
  };
  /** Optional stencil support with search/get/upgrade callbacks. */
  stencilOptions?: StencilCallbacks;
  /**
   * Optional feature state. The host page reads this from the backend's
   * feature-toggle service and feature metadata registry. The engine exposes
   * booleans via `engine.isFeatureEnabled(flag)`; host/plugin setup can read
   * UI metadata such as badges directly from the same feature object.
   *
   * The shape is governed by `EditorFeatures` so feature names are typed
   * end-to-end and renames/removals fail the compile.
   */
  features?: EditorFeatures;
  /**
   * Effective BCP-47 locale for this editing session. Resolved server-side
   * via the variant-attribute → tenant default → app default chain
   * (`TenantLocaleResolver.resolve(tenant, variantAttributes)`) and forwarded
   * here so the editor's expression previews format dates with the same
   * locale the PDF render will use. Defaults to `"en-US"` when absent.
   */
  locale?: string;
}

export type {
  EditorFeatureBadge,
  EditorFeatureConfig,
  EditorFeatureFlag,
  EditorFeatures,
} from './engine/feature-flags.js';

export interface EditorInstance {
  /** Tear down the editor and clean up */
  unmount(): void;
  /** Get the current template document */
  getTemplate(): TemplateDocument;
  /** Replace the template document */
  setTemplate(template: TemplateDocument): void;
}

/**
 * Create an empty template document with a root container.
 */
export function createEmptyDocument(): TemplateDocument {
  const rootId = nanoid() as NodeId;
  const rootSlotId = nanoid() as SlotId;

  return {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: {
        id: rootId,
        type: 'root',
        slots: [rootSlotId],
      },
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootId,
        name: 'children',
        children: [],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

/**
 * Mount the editor into a DOM element.
 */
export function mountEditor(options: EditorOptions): EditorInstance {
  const { container, template, dataModel, dataExamples, onFetchPreview, onSave, plugins } = options;
  const doc = template ?? createEmptyDocument();

  // Create the custom element
  const editorEl = document.createElement('epistola-editor');
  editorEl.style.height = '100%';
  editorEl.style.width = '100%';
  editorEl.style.display = 'block';

  // Forward the (test-only) leader-timing override from the server-rendered
  // host container onto the editor element so connectedCallback can read it.
  const leaderTiming = container.getAttribute('data-leader-timing');
  if (leaderTiming) {
    editorEl.setAttribute('data-leader-timing', leaderTiming);
  }

  // Wire up callbacks before initEngine — initEngine reads these to create
  // the SaveService and other services during initialization.
  if (onFetchPreview) {
    editorEl.fetchPreview = onFetchPreview;
  }
  if (onSave) {
    editorEl.onSave = onSave;
  }
  if (plugins) {
    editorEl.plugins = plugins;
  }

  // Backend-driven font picker. Loaded asynchronously; the font-family
  // select options and `@font-face` rules are mutated in place when the
  // catalog arrives, so a re-render picks them up without re-mounting.
  if (options.fontOptions) {
    options.fontOptions
      .listFonts()
      .then((fonts) => {
        setFontCatalog(fonts);
        editorEl.requestUpdate();
      })
      .catch((e) => console.warn('Failed to load font catalog:', e));
  }

  // Build registry with optional image support
  const registry = createDefaultRegistry();
  if (options.imageOptions) {
    registry.register(
      createImageDefinition({
        assetPicker: {
          defaultCatalogKey: options.imageOptions.defaultCatalogKey,
          listCatalogs: options.imageOptions.listCatalogs,
          listAssets: options.imageOptions.listAssets,
          uploadAsset: options.imageOptions.uploadAsset,
        },
        contentUrlPattern: options.imageOptions.contentUrlPattern,
        defaultCatalogKey: options.imageOptions.defaultCatalogKey,
      }),
    );
  }

  // Register stencil component (always — so existing stencil nodes render correctly)
  registry.register(
    createStencilDefinition({
      callbacks: options.stencilOptions ?? null,
    }),
  );

  // Initialize the engine with data model context
  editorEl.initEngine(doc, registry, {
    dataModel,
    dataExamples,
    features: options.features,
    locale: options.locale,
  });

  // Mount into the container
  container.innerHTML = '';
  container.appendChild(editorEl);

  // Check for stencil upgrades after mount
  if (options.stencilOptions?.checkUpgrades) {
    const stencilRefs = collectStencilUpgradeRefs(doc);

    if (stencilRefs.length > 0) {
      options.stencilOptions.checkUpgrades(stencilRefs).then((upgrades) => {
        if (upgrades.length > 0) {
          const upgradeMap: Record<string, number> = {};
          for (const u of upgrades) {
            if (u.latestVersion > u.currentVersion) {
              upgradeMap[u.stencilId] = u.latestVersion;
            }
          }
          if (Object.keys(upgradeMap).length > 0) {
            editorEl.engine?.setComponentState('stencil:upgrades', upgradeMap);
          }
        }
      });
    }
  }

  return {
    unmount() {
      editorEl.remove();
    },
    getTemplate() {
      return editorEl.engine!.doc;
    },
    setTemplate(newDoc: TemplateDocument) {
      editorEl.engine!.replaceDocument(newDoc);
    },
  };
}
