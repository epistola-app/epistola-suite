import {
  EDITOR_SHORTCUTS_CONFIG,
  type ResizeKeyboardShortcutConfig,
  type ResizeShortcutId,
} from '../shortcuts-config.js'
import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  type CommandDefinition,
  type KeybindingDefinition,
  type ShortcutRegistryDefinition,
} from './foundation.js'

const RESIZE_SHORTCUTS_BY_ID = new Map(
  EDITOR_SHORTCUTS_CONFIG.resize.keyboard.map((shortcut) => [shortcut.id, shortcut] as const),
)

function getResizeShortcut(shortcutId: ResizeShortcutId): ResizeKeyboardShortcutConfig {
  const shortcut = RESIZE_SHORTCUTS_BY_ID.get(shortcutId)
  if (!shortcut) {
    throw new Error(`Missing resize shortcut config for "${shortcutId}"`)
  }
  return shortcut
}

const GROW_PREVIEW_WIDTH_SHORTCUT = getResizeShortcut('grow-preview-width')
const SHRINK_PREVIEW_WIDTH_SHORTCUT = getResizeShortcut('shrink-preview-width')
const CLOSE_PREVIEW_AT_MIN_WIDTH_SHORTCUT = getResizeShortcut('close-preview-when-min-width')

export interface ResizeShortcutRuntimeContext {
  currentWidth: number
  minWidth: number
  step: number
  setWidth: (nextWidth: number) => void
  closePreview: () => void
}

export const RESIZE_SHORTCUT_COMMAND_IDS = {
  growPreviewWidth: 'resize.preview.grow',
  shrinkPreviewWidth: 'resize.preview.shrink',
  closePreviewWhenMinWidth: 'resize.preview.close-when-min-width',
} as const

const RESIZE_SHORTCUT_COMMANDS: readonly CommandDefinition<ResizeShortcutRuntimeContext>[] = [
  {
    id: RESIZE_SHORTCUT_COMMAND_IDS.growPreviewWidth,
    label: GROW_PREVIEW_WIDTH_SHORTCUT.action,
    category: 'Resize',
    run: (context) => {
      context.setWidth(context.currentWidth + context.step)
      return { ok: true }
    },
  },
  {
    id: RESIZE_SHORTCUT_COMMAND_IDS.shrinkPreviewWidth,
    label: SHRINK_PREVIEW_WIDTH_SHORTCUT.action,
    category: 'Resize',
    run: (context) => {
      context.setWidth(context.currentWidth - context.step)
      return { ok: true }
    },
  },
  {
    id: RESIZE_SHORTCUT_COMMAND_IDS.closePreviewWhenMinWidth,
    label: CLOSE_PREVIEW_AT_MIN_WIDTH_SHORTCUT.action,
    category: 'Resize',
    run: (context) => {
      context.closePreview()
      return { ok: true }
    },
  },
]

const RESIZE_SHORTCUT_KEYBINDINGS: readonly KeybindingDefinition[] = [
  {
    commandId: RESIZE_SHORTCUT_COMMAND_IDS.growPreviewWidth,
    context: 'resizeHandle',
    keys: [GROW_PREVIEW_WIDTH_SHORTCUT.key],
    preventDefault: true,
    display: GROW_PREVIEW_WIDTH_SHORTCUT.helpKeys,
  },
  {
    commandId: RESIZE_SHORTCUT_COMMAND_IDS.shrinkPreviewWidth,
    context: 'resizeHandle',
    keys: [SHRINK_PREVIEW_WIDTH_SHORTCUT.key],
    preventDefault: true,
    when: (context) => {
      const typed = context as ResizeShortcutRuntimeContext
      return typed.currentWidth > typed.minWidth
    },
    display: SHRINK_PREVIEW_WIDTH_SHORTCUT.helpKeys,
  },
  {
    commandId: RESIZE_SHORTCUT_COMMAND_IDS.closePreviewWhenMinWidth,
    context: 'resizeHandle',
    keys: [CLOSE_PREVIEW_AT_MIN_WIDTH_SHORTCUT.key],
    preventDefault: true,
    when: (context) => {
      const typed = context as ResizeShortcutRuntimeContext
      return typed.currentWidth <= typed.minWidth
    },
    display: CLOSE_PREVIEW_AT_MIN_WIDTH_SHORTCUT.helpKeys,
  },
]

export const RESIZE_SHORTCUT_REGISTRY: ShortcutRegistryDefinition<ResizeShortcutRuntimeContext> =
  defineShortcutRegistry({
    commands: RESIZE_SHORTCUT_COMMANDS,
    keybindings: RESIZE_SHORTCUT_KEYBINDINGS,
  })

assertValidShortcutRegistry(RESIZE_SHORTCUT_REGISTRY)

export function getResizeShortcutRegistry(): ShortcutRegistryDefinition<ResizeShortcutRuntimeContext> {
  return RESIZE_SHORTCUT_REGISTRY
}
