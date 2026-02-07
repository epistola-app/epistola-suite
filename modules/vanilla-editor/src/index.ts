/**
 * @epistola/vanilla-editor
 *
 * Mountable editor UI built on @epistola/headless-editor.
 * Uses uhtml for rendering, Stimulus for controller orchestration,
 * TipTap headless for text editing, and @github/hotkey for shortcuts.
 *
 * @example
 * ```ts
 * import { mountEditor } from '@epistola/vanilla-editor';
 *
 * const editor = mountEditor({
 *   container: '#editor-root',
 *   template: templateData,
 *   save: { handler: async (t) => fetch('/save', { method: 'POST', body: JSON.stringify(t) }) },
 * });
 * ```
 */

import './styles/editor.css';

export type { MountConfig, MountedEditor, SaveConfig, PreviewConfig } from './types.js';
export type { BlockTemplateMap, RendererOptions, SortableAdapterOptions } from './types.js';
export { BlockRenderer, getBadgeClass, getBadgeStyle, getBlockIcon } from './renderer.js';
export { SortableAdapter } from './sortable-adapter.js';
export { ExpressionChipNode } from './extensions/expression-chip-node.js';
export { TextBlockController } from './controllers/text-block.js';
export { ExpressionEditorController } from './controllers/expression-editor.js';
export { EditorController } from './controllers/editor-controller.js';
export { installHotkeys, uninstallHotkeys } from './hotkeys.js';
export { mountEditor, getEditor } from './mount.js';
