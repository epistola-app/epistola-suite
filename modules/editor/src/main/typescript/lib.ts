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
import type { EpistolaEditor } from './ui/EpistolaEditor.js';
import type { TemplateDocument, NodeId, SlotId } from './types/index.js';
import type { FetchPreviewFn } from './ui/preview-service.js';
import type { EditorPlugin } from './plugins/types.js';
import { createDefaultRegistry } from './engine/registry.js';
import { createImageDefinition } from './components/image/image-registration.js';
import type { AssetInfo } from './components/image/asset-picker-dialog.js';
import { createStencilDefinition } from './components/stencil/stencil-registration.js';
import type { StencilCallbacks } from './components/stencil/types.js';
import { validateCoreShortcutRegistriesOnStartup } from './shortcuts/startup-validation.js';
import { nanoid } from 'nanoid';

validateCoreShortcutRegistriesOnStartup();

export type { TemplateDocument, Node, Slot, NodeId, SlotId } from './types/index.js';
export type { AssetInfo } from './components/image/asset-picker-dialog.js';
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
  /**
   * Language code of the variant being edited (e.g. "nl", "en"). Used as
   * `sys.language` in expression previews so locale-aware functions like
   * `$formatLocalNumber` produce the same output as the rendered PDF.
   */
  variantLanguage?: string;
  /** Callback to fetch a PDF preview. Host page owns the HTTP call; editor owns debounce/abort. */
  onFetchPreview?: FetchPreviewFn;
  /** Optional plugins that extend the editor with additional sidebar tabs, toolbar actions, etc. */
  plugins?: EditorPlugin[];
  /** Optional image block support with asset management callbacks. */
  imageOptions?: {
    listAssets: () => Promise<AssetInfo[]>;
    uploadAsset: (file: File) => Promise<AssetInfo>;
    contentUrlPattern: string;
  };
  /** Optional stencil support with search/get/upgrade callbacks. */
  stencilOptions?: StencilCallbacks;
}

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
  const {
    container,
    template,
    dataModel,
    dataExamples,
    variantLanguage,
    onFetchPreview,
    onSave,
    plugins,
  } = options;
  const doc = template ?? createEmptyDocument();

  // Create the custom element
  const editorEl = document.createElement('epistola-editor') as EpistolaEditor;
  editorEl.style.height = '100%';
  editorEl.style.width = '100%';
  editorEl.style.display = 'block';

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

  // Build registry with optional image support
  const registry = createDefaultRegistry();
  if (options.imageOptions) {
    registry.register(
      createImageDefinition({
        assetPicker: {
          listAssets: options.imageOptions.listAssets,
          uploadAsset: options.imageOptions.uploadAsset,
        },
        contentUrlPattern: options.imageOptions.contentUrlPattern,
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
  editorEl.initEngine(doc, registry, { dataModel, dataExamples, variantLanguage });

  // Mount into the container
  container.innerHTML = '';
  container.appendChild(editorEl);

  // Check for stencil upgrades after mount
  if (options.stencilOptions?.checkUpgrades) {
    const stencilRefs = Object.values(doc.nodes)
      .filter((n) => n.type === 'stencil' && n.props?.stencilId && n.props?.version)
      .map((n) => ({
        stencilId: n.props!.stencilId as string,
        version: n.props!.version as number,
      }));

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
