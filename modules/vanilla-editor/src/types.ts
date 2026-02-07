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
} from '@epistola/headless-editor';
// ============================================================================
// Mount Config
// ============================================================================

/**
 * Configuration object for `mountEditor()`.
 * Describes everything needed to create a fully functional editor instance.
 */
export interface MountConfig {
  /**
   * Target container element or CSS selector.
   * If a string, resolved via `document.querySelector()`.
   * If an HTMLElement, used directly.
   */
  container: string | HTMLElement;

  /** Initial template data to load into the editor */
  template: Template;

  /**
   * Save lifecycle configuration.
   * When provided, the editor manages the full save cycle:
   * call handler, markAsSaved(), dirty status UI, error handling.
   */
  save?: SaveConfig;

  /**
   * Preview lifecycle configuration.
   * When provided, the editor manages debounced preview refresh on template changes.
   */
  preview?: PreviewConfig;

  /** Available themes for the theme selector dropdown */
  themes?: ThemeSummary[];

  /** Default theme to pre-select */
  defaultTheme?: ThemeSummary | null;

  /** Data examples for the data example selector dropdown */
  dataExamples?: DataExample[];

  /** JSON schema for expression autocomplete */
  schema?: JsonSchema | null;

  /** Enable debug mode (logs state changes, render timings) */
  debug?: boolean;
}

// ============================================================================
// Mounted Editor (return type)
// ============================================================================

/**
 * Public API returned by `mountEditor()`.
 * Provides access to the current template, the underlying editor, and cleanup.
 */
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
   * After calling destroy(), `mountEditor()` can be called again on the same container.
   */
  destroy(): void;
}

// ============================================================================
// Save Config
// ============================================================================

/**
 * Configuration for the save lifecycle.
 * The handler is called when the user triggers save (button click or Ctrl+S).
 */
export interface SaveConfig {
  /**
   * Async function that persists the template.
   * Called with the current template data.
   * On success, the editor automatically calls `markAsSaved()`.
   * On failure, the editor updates the save status UI to indicate failure.
   */
  handler: (template: Template) => Promise<void>;
}

// ============================================================================
// Preview Config
// ============================================================================

/**
 * Configuration for the live preview lifecycle.
 * The handler is called with debounced updates when the template changes.
 */
export interface PreviewConfig {
  /**
   * Async function that renders a preview.
   * Called with the current template and test data.
   * Should return HTML content or update the preview iframe.
   */
  handler: (template: Template, data: JsonObject) => Promise<string>;

  /**
   * Preview iframe element or CSS selector.
   * The handler's return value is written to this iframe's srcdoc.
   */
  iframe: string | HTMLIFrameElement;

  /**
   * Debounce delay in milliseconds for automatic preview refresh.
   * Defaults to 500ms.
   */
  debounceMs?: number;
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
}
