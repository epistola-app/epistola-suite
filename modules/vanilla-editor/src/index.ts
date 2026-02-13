/**
 * @epistola/vanilla-editor
 *
 * Mountable editor UI built on @epistola/headless-editor.
 * Uses uhtml for rendering, Stimulus for controller orchestration,
 * TipTap headless for text editing, and @github/hotkey for shortcuts.
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
 * ```
 */

import "./styles/editor.css";

export type {
  MountedEditor,
  MountEditorAppConfig,
  EditorAppUiConfig,
  PreviewResult,
} from "./types.js";
export type {
  BlockTemplateMap,
  RendererOptions,
  SortableAdapterOptions,
  BlockRendererPlugin,
  BlockRendererPluginContext,
} from "./types.js";
export {
  BlockRenderer,
  getBadgeClass,
  getBadgeStyle,
  getBlockIcon,
} from "./renderer.js";
export { SortableAdapter } from "./sortable-adapter.js";
export { ExpressionChipNode } from "./extensions/expression-chip-node.js";
export { TextBlockController } from "./controllers/text-block.js";
export { ExpressionEditorController } from "./controllers/expression-editor.js";
export { EditorController } from "./controllers/editor-controller.js";
export { installHotkeys, uninstallHotkeys } from "./hotkeys.js";
export { mountEditorApp } from "./mount.js";
export { getEditorForElement } from "./mount.js";
