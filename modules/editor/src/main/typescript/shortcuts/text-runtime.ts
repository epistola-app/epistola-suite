import {
  EDITOR_SHORTCUTS_CONFIG,
  type TextShortcutConfig,
  type TextShortcutId,
} from '../shortcuts-config.js'
import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  type CommandDefinition,
  type KeybindingDefinition,
  type ShortcutRegistryDefinition,
} from './foundation.js'
import { toShortcutStrokesFromBindings } from './key-strokes.js'

const TEXT_SHORTCUTS_BY_ID = new Map(
  EDITOR_SHORTCUTS_CONFIG.text.map((shortcut) => [shortcut.id, shortcut] as const),
)

function getTextShortcut(shortcutId: TextShortcutId): TextShortcutConfig {
  const shortcut = TEXT_SHORTCUTS_BY_ID.get(shortcutId)
  if (!shortcut) {
    throw new Error(`Missing text shortcut config for "${shortcutId}"`)
  }
  return shortcut
}

export const TEXT_SHORTCUT_COMMAND_IDS = {
  bold: 'text.mark.bold',
  italic: 'text.mark.italic',
  underline: 'text.mark.underline',
  lineBreakShiftEnter: 'text.line-break.shift-enter',
  lineBreakModEnter: 'text.line-break.mod-enter',
} as const

export type TextShortcutCommandId =
  (typeof TEXT_SHORTCUT_COMMAND_IDS)[keyof typeof TEXT_SHORTCUT_COMMAND_IDS]

interface TextShortcutRuntimeDefinition {
  commandId: TextShortcutCommandId
  shortcutId: TextShortcutId
}

const TEXT_SHORTCUT_RUNTIME_DEFINITIONS: readonly TextShortcutRuntimeDefinition[] = [
  {
    commandId: TEXT_SHORTCUT_COMMAND_IDS.bold,
    shortcutId: 'bold',
  },
  {
    commandId: TEXT_SHORTCUT_COMMAND_IDS.italic,
    shortcutId: 'italic',
  },
  {
    commandId: TEXT_SHORTCUT_COMMAND_IDS.underline,
    shortcutId: 'underline',
  },
  {
    commandId: TEXT_SHORTCUT_COMMAND_IDS.lineBreakShiftEnter,
    shortcutId: 'line-break-shift-enter',
  },
  {
    commandId: TEXT_SHORTCUT_COMMAND_IDS.lineBreakModEnter,
    shortcutId: 'line-break-mod-enter',
  },
]

const COMMAND_ID_BY_SHORTCUT_ID = new Map<TextShortcutId, TextShortcutCommandId>()
for (const definition of TEXT_SHORTCUT_RUNTIME_DEFINITIONS) {
  COMMAND_ID_BY_SHORTCUT_ID.set(definition.shortcutId, definition.commandId)
}

const SHORTCUT_ID_BY_COMMAND_ID = new Map<TextShortcutCommandId, TextShortcutId>()
for (const definition of TEXT_SHORTCUT_RUNTIME_DEFINITIONS) {
  SHORTCUT_ID_BY_COMMAND_ID.set(definition.commandId, definition.shortcutId)
}

const TEXT_SHORTCUT_COMMANDS: readonly CommandDefinition<unknown>[] =
  TEXT_SHORTCUT_RUNTIME_DEFINITIONS.map((definition) => {
    const shortcut = getTextShortcut(definition.shortcutId)
    return {
      id: definition.commandId,
      label: shortcut.action,
      category: 'Text',
      run: () => ({ ok: true }),
      metadata: {
        shortcutId: definition.shortcutId,
      },
    }
  })

const TEXT_SHORTCUT_KEYBINDINGS: readonly KeybindingDefinition[] =
  TEXT_SHORTCUT_RUNTIME_DEFINITIONS.map((definition) => {
    const shortcut = getTextShortcut(definition.shortcutId)
    return {
      commandId: definition.commandId,
      context: 'text',
      keys: toShortcutStrokesFromBindings(shortcut.bindings, { wildcardUnspecifiedModifiers: false }),
      display: shortcut.helpKeys,
    }
  })

export const TEXT_SHORTCUT_REGISTRY: ShortcutRegistryDefinition<unknown> = defineShortcutRegistry({
  commands: TEXT_SHORTCUT_COMMANDS,
  keybindings: TEXT_SHORTCUT_KEYBINDINGS,
})

assertValidShortcutRegistry(TEXT_SHORTCUT_REGISTRY)

const BINDINGS_BY_COMMAND_ID = new Map<TextShortcutCommandId, KeybindingDefinition[]>()
for (const binding of TEXT_SHORTCUT_REGISTRY.keybindings) {
  const shortcutId = SHORTCUT_ID_BY_COMMAND_ID.get(binding.commandId as TextShortcutCommandId)
  if (!shortcutId) {
    continue
  }

  const commandId = COMMAND_ID_BY_SHORTCUT_ID.get(shortcutId)
  if (!commandId) {
    continue
  }

  const current = BINDINGS_BY_COMMAND_ID.get(commandId)
  if (current) {
    current.push(binding)
  } else {
    BINDINGS_BY_COMMAND_ID.set(commandId, [binding])
  }
}

export function getTextShortcutRegistry(): ShortcutRegistryDefinition<unknown> {
  return TEXT_SHORTCUT_REGISTRY
}

export function getTextShortcutBindingsForCommandId(commandId: TextShortcutCommandId): readonly KeybindingDefinition[] {
  return BINDINGS_BY_COMMAND_ID.get(commandId) ?? []
}

export function getTextShortcutDisplayForCommandId(commandId: TextShortcutCommandId): string {
  const binding = getTextShortcutBindingsForCommandId(commandId)[0]
  if (!binding?.display) {
    throw new Error(`Missing text shortcut display for command "${commandId}"`)
  }
  return binding.display
}

export function getTextBubbleTitle(commandId: TextShortcutCommandId, fallbackTitle: string): string {
  const display = getTextShortcutDisplayForCommandId(commandId)
  const normalizedDisplay = display.replaceAll('{cmd}', 'Ctrl/Cmd')
  return `${fallbackTitle} (${normalizedDisplay})`
}
