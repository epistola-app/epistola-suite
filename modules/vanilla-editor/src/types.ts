/**
 * Type definitions for @epistola/vanilla-editor.
 *
 * Types-first: these interfaces define the contract before implementation.
 * All public types have JSDoc. No `any` types.
 */

import type {
  TemplateEditor,
  Template,
  ThemeSummary,
  DataExample,
  JsonSchema,
  JsonObject,
  DragDropPort,
  Block,
} from "@epistola/headless-editor";

export type PreviewResult =
  | string
  | Blob
  | {
      mimeType: string;
      data: string | Blob;
    }
  | void;
export interface EditorAppUiConfig {
  showThemeSelector?: boolean;
  showDataExampleSelector?: boolean;
  showPreview?: boolean;
  labels?: {
    save?: string;
    preview?: string;
  };
}

export interface MountEditorAppConfig {
  container: string | HTMLElement;
  template: Template;
  onSave?: (template: Template) => Promise<void>;
  onPreview?: (template: Template, data: JsonObject) => Promise<PreviewResult>;
  themes?: ThemeSummary[];
  defaultTheme?: ThemeSummary | null;
  dataExamples?: DataExample[];
  schema?: JsonSchema | null;
  debug?: boolean;
  ui?: EditorAppUiConfig;
  rendererPlugins?: BlockRendererPlugin[];
  dndMode?: "native" | "fallback";
}

// ============================================================================
// Mounted Editor (return type)
// ============================================================================

/** Public API returned by `mountEditorApp()`. */
export interface MountedEditor {
  /** Get the current template as a snapshot */
  getTemplate(): Template;

  /**
   * Get the underlying `TemplateEditor` instance for advanced use.
   * Prefer using `MountedEditor` methods over accessing the editor directly.
   */
  getEditor(): TemplateEditor;

  /**
   * Destroy the editor, cleaning up all subscriptions, controllers,
   * SortableJS instances, TipTap editors, hotkey bindings, and DOM content.
   * After calling destroy(), `mountEditorApp()` can be called again on the same container.
   */
  destroy(): void;
}

// ============================================================================
// Block Template Map
// ============================================================================

/**
 * Maps a block type string to its uhtml render function.
 * Each render function receives a block and returns a uhtml result
 * (the result of a `html\`...\`` tagged template: `Node | HTMLElement | Hole`).
 */
export type BlockTemplateMap = Record<string, (block: Block) => Node>;

// ============================================================================
// Renderer Options
// ============================================================================

/**
 * Configuration for the `BlockRenderer`.
 */
export interface RendererOptions {
  /** Container element where blocks are rendered */
  container: HTMLElement;

  /** The headless editor instance providing state and operations */
  editor: TemplateEditor;

  /** Enable debug logging for render timings */
  debug?: boolean;

  /** Optional block renderer plugins keyed by block type */
  rendererPlugins?: BlockRendererPlugin[];
}

export interface BlockRendererPluginContext {
  block: Block;
  selectedBlockId: string | null;
}

export interface BlockRendererPlugin {
  type: string;
  render: (context: BlockRendererPluginContext) => unknown;
}

// ============================================================================
// Sortable Adapter Options
// ============================================================================

/**
 * Configuration for the `SortableAdapter` that bridges SortableJS
 * with the headless editor's `DragDropPort` interface.
 */
export interface SortableAdapterOptions {
  /** The headless editor instance */
  editor: TemplateEditor;

  /** Container element with sortable block elements */
  container: HTMLElement;

  /** DragDropPort from the editor for validation and execution */
  dragDropPort: DragDropPort;

  /** Drag interaction mode; fallback is useful for E2E automation */
  dndMode?: "native" | "fallback";
}
