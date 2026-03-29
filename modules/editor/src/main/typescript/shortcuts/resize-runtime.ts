import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  type CommandDefinition,
  type KeybindingDefinition,
  type ShortcutRegistryDefinition,
} from './foundation.js';

// ---------------------------------------------------------------------------
// Command IDs
// ---------------------------------------------------------------------------

export const RESIZE_SHORTCUT_COMMAND_IDS = {
  growPreviewWidth: 'resize.preview.grow',
  shrinkPreviewWidth: 'resize.preview.shrink',
  closePreviewWhenMinWidth: 'resize.preview.close-when-min-width',
} as const;

// ---------------------------------------------------------------------------
// Runtime context
// ---------------------------------------------------------------------------

export interface ResizeShortcutRuntimeContext {
  currentWidth: number;
  minWidth: number;
  step: number;
  setWidth: (nextWidth: number) => void;
  closePreview: () => void;
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

const RESIZE_SHORTCUT_COMMANDS: readonly CommandDefinition<ResizeShortcutRuntimeContext>[] = [
  {
    id: RESIZE_SHORTCUT_COMMAND_IDS.growPreviewWidth,
    label: 'Grow preview width',
    category: 'Resize',
    run: (ctx) => {
      ctx.setWidth(ctx.currentWidth + ctx.step);
      return { ok: true };
    },
  },
  {
    id: RESIZE_SHORTCUT_COMMAND_IDS.shrinkPreviewWidth,
    label: 'Shrink preview width',
    category: 'Resize',
    run: (ctx) => {
      ctx.setWidth(ctx.currentWidth - ctx.step);
      return { ok: true };
    },
  },
  {
    id: RESIZE_SHORTCUT_COMMAND_IDS.closePreviewWhenMinWidth,
    label: 'Close preview',
    category: 'Resize',
    run: (ctx) => {
      ctx.closePreview();
      return { ok: true };
    },
  },
];

// ---------------------------------------------------------------------------
// Keybindings
// ---------------------------------------------------------------------------

const RESIZE_SHORTCUT_KEYBINDINGS: readonly KeybindingDefinition<ResizeShortcutRuntimeContext>[] = [
  {
    commandId: RESIZE_SHORTCUT_COMMAND_IDS.growPreviewWidth,
    context: 'resizeHandle',
    keys: ['ArrowLeft'],
    preventDefault: true,
    display: '\u2190 (focused handle)',
  },
  {
    commandId: RESIZE_SHORTCUT_COMMAND_IDS.shrinkPreviewWidth,
    context: 'resizeHandle',
    keys: ['ArrowRight'],
    preventDefault: true,
    when: (ctx) => ctx.currentWidth > ctx.minWidth,
    display: '\u2192 (focused handle)',
  },
  {
    commandId: RESIZE_SHORTCUT_COMMAND_IDS.closePreviewWhenMinWidth,
    context: 'resizeHandle',
    keys: ['ArrowRight'],
    preventDefault: true,
    when: (ctx) => ctx.currentWidth <= ctx.minWidth,
    display: '\u2192 at min width',
  },
];

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

export const RESIZE_SHORTCUT_REGISTRY: ShortcutRegistryDefinition<ResizeShortcutRuntimeContext> =
  defineShortcutRegistry({
    commands: RESIZE_SHORTCUT_COMMANDS,
    keybindings: RESIZE_SHORTCUT_KEYBINDINGS,
  });

assertValidShortcutRegistry(RESIZE_SHORTCUT_REGISTRY);

export function getResizeShortcutRegistry(): ShortcutRegistryDefinition<ResizeShortcutRuntimeContext> {
  return RESIZE_SHORTCUT_REGISTRY;
}
