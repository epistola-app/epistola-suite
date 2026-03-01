import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  type CommandDefinition,
  type KeybindingDefinition,
  type ShortcutRegistryDefinition,
} from './foundation.js'

// ---------------------------------------------------------------------------
// Command IDs
// ---------------------------------------------------------------------------

export const TEXT_SHORTCUT_COMMAND_IDS = {
  bold: 'text.mark.bold',
  italic: 'text.mark.italic',
  underline: 'text.mark.underline',
  lineBreakShiftEnter: 'text.line-break.shift-enter',
  lineBreakModEnter: 'text.line-break.mod-enter',
} as const

export type TextShortcutCommandId =
  (typeof TEXT_SHORTCUT_COMMAND_IDS)[keyof typeof TEXT_SHORTCUT_COMMAND_IDS]

// ---------------------------------------------------------------------------
// Commands — text shortcuts are marker-only (ProseMirror handles execution)
// ---------------------------------------------------------------------------

const TEXT_SHORTCUT_COMMANDS: readonly CommandDefinition<unknown>[] = [
  { id: TEXT_SHORTCUT_COMMAND_IDS.bold, label: 'Bold', category: 'Text', run: () => ({ ok: true }) },
  { id: TEXT_SHORTCUT_COMMAND_IDS.italic, label: 'Italic', category: 'Text', run: () => ({ ok: true }) },
  { id: TEXT_SHORTCUT_COMMAND_IDS.underline, label: 'Underline', category: 'Text', run: () => ({ ok: true }) },
  { id: TEXT_SHORTCUT_COMMAND_IDS.lineBreakShiftEnter, label: 'Line break', category: 'Text', run: () => ({ ok: true }) },
  { id: TEXT_SHORTCUT_COMMAND_IDS.lineBreakModEnter, label: 'Line break', category: 'Text', run: () => ({ ok: true }) },
]

// ---------------------------------------------------------------------------
// Keybindings
// ---------------------------------------------------------------------------

const TEXT_SHORTCUT_KEYBINDINGS: readonly KeybindingDefinition[] = [
  { commandId: TEXT_SHORTCUT_COMMAND_IDS.bold, context: 'text', keys: ['mod+b'], display: '{cmd} + B' },
  { commandId: TEXT_SHORTCUT_COMMAND_IDS.italic, context: 'text', keys: ['mod+i'], display: '{cmd} + I' },
  { commandId: TEXT_SHORTCUT_COMMAND_IDS.underline, context: 'text', keys: ['mod+u'], display: '{cmd} + U' },
  { commandId: TEXT_SHORTCUT_COMMAND_IDS.lineBreakShiftEnter, context: 'text', keys: ['shift+enter'], display: 'Shift + Enter' },
  { commandId: TEXT_SHORTCUT_COMMAND_IDS.lineBreakModEnter, context: 'text', keys: ['mod+enter'], display: '{cmd} + Enter' },
]

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

export const TEXT_SHORTCUT_REGISTRY: ShortcutRegistryDefinition<unknown> = defineShortcutRegistry({
  commands: TEXT_SHORTCUT_COMMANDS,
  keybindings: TEXT_SHORTCUT_KEYBINDINGS,
})

assertValidShortcutRegistry(TEXT_SHORTCUT_REGISTRY)

// ---------------------------------------------------------------------------
// Lookups
// ---------------------------------------------------------------------------

const BINDINGS_BY_COMMAND_ID = new Map<TextShortcutCommandId, KeybindingDefinition[]>()
for (const binding of TEXT_SHORTCUT_REGISTRY.keybindings) {
  const commandId = binding.commandId as TextShortcutCommandId
  const current = BINDINGS_BY_COMMAND_ID.get(commandId)
  if (current) {
    current.push(binding)
  } else {
    BINDINGS_BY_COMMAND_ID.set(commandId, [binding])
  }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

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
