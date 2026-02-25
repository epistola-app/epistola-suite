import { EDITOR_SHORTCUTS_CONFIG } from '../shortcuts-config.js'
import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  type CommandDefinition,
  type KeybindingDefinition,
  type ShortcutRegistryDefinition,
} from './foundation.js'

type InsertDialogPlacementMode = 'after' | 'before' | 'inside' | 'start' | 'end'

export interface InsertDialogShortcutRuntimeContext {
  hasPlacementMode: boolean
  hasSelectionMode: boolean
  isDocumentContext: boolean
  optionCount: number
  highlight: number
  closeOrBack: () => void
  selectMode: (mode: InsertDialogPlacementMode) => void
  setHighlight: (index: number) => void
  selectOption: (index: number) => void
  setOptionOutOfRange: () => void
}

export const INSERT_DIALOG_SHORTCUT_COMMAND_IDS = {
  closeOrBack: 'insertDialog.close-or-back',
  modeStart: 'insertDialog.mode.start',
  modeEnd: 'insertDialog.mode.end',
  modeAfter: 'insertDialog.mode.after',
  modeBefore: 'insertDialog.mode.before',
  modeInside: 'insertDialog.mode.inside',
  navigatePrevious: 'insertDialog.navigate.previous',
  navigateNext: 'insertDialog.navigate.next',
  confirm: 'insertDialog.confirm',
} as const

function toQuickSelectCommandId(index: number): `insertDialog.quick-select.key-${number}` {
  return `insertDialog.quick-select.key-${index}`
}

const INSERT_DIALOG_COMMANDS: CommandDefinition<InsertDialogShortcutRuntimeContext>[] = [
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.closeOrBack,
    label: 'Back / close insert dialog',
    category: 'Insert',
    run: (context) => {
      context.closeOrBack()
      return { ok: true }
    },
  },
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeStart,
    label: 'Select document start placement',
    category: 'Insert',
    run: (context) => {
      context.selectMode('start')
      return { ok: true }
    },
  },
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeEnd,
    label: 'Select document end placement',
    category: 'Insert',
    run: (context) => {
      context.selectMode('end')
      return { ok: true }
    },
  },
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeAfter,
    label: 'Select insert after placement',
    category: 'Insert',
    run: (context) => {
      context.selectMode('after')
      return { ok: true }
    },
  },
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeBefore,
    label: 'Select insert before placement',
    category: 'Insert',
    run: (context) => {
      context.selectMode('before')
      return { ok: true }
    },
  },
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeInside,
    label: 'Select insert inside placement',
    category: 'Insert',
    run: (context) => {
      context.selectMode('inside')
      return { ok: true }
    },
  },
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.navigatePrevious,
    label: 'Navigate previous insert option',
    category: 'Insert',
    run: (context) => {
      if (context.optionCount <= 0) {
        return { ok: true }
      }
      if (context.highlight <= 0) {
        context.setHighlight(1)
        return { ok: true }
      }

      const next = context.highlight - 1
      context.setHighlight(next < 1 ? context.optionCount : next)
      return { ok: true }
    },
  },
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.navigateNext,
    label: 'Navigate next insert option',
    category: 'Insert',
    run: (context) => {
      if (context.optionCount <= 0) {
        return { ok: true }
      }
      if (context.highlight <= 0) {
        context.setHighlight(1)
        return { ok: true }
      }

      const next = context.highlight + 1
      context.setHighlight(next > context.optionCount ? 1 : next)
      return { ok: true }
    },
  },
  {
    id: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.confirm,
    label: 'Confirm insert dialog selection',
    category: 'Insert',
    run: (context) => {
      if (context.highlight <= 0) {
        return { ok: true }
      }
      context.selectOption(context.highlight)
      return { ok: true }
    },
  },
]

for (const [index, key] of EDITOR_SHORTCUTS_CONFIG.insertDialog.navigation.quickSelect.entries()) {
  const optionIndex = index + 1
  INSERT_DIALOG_COMMANDS.push({
    id: toQuickSelectCommandId(optionIndex),
    label: `Quick select insert option ${optionIndex}`,
    category: 'Insert',
    run: (context) => {
      if (optionIndex > context.optionCount) {
        context.setOptionOutOfRange()
        return { ok: false, message: 'Option out of range' }
      }
      context.selectOption(optionIndex)
      return { ok: true }
    },
    metadata: {
      quickSelectKey: key,
      quickSelectIndex: optionIndex,
    },
  })
}

const placement = EDITOR_SHORTCUTS_CONFIG.insertDialog.placement
const navigation = EDITOR_SHORTCUTS_CONFIG.insertDialog.navigation

const INSERT_DIALOG_KEYBINDINGS: KeybindingDefinition[] = [
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.closeOrBack,
    context: 'insertDialog',
    keys: [navigation.close],
    preventDefault: true,
    display: 'Esc',
  },
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeStart,
    context: 'insertDialog',
    keys: [placement.document.start],
    preventDefault: true,
    when: (context) => {
      const typed = context as InsertDialogShortcutRuntimeContext
      return typed.hasPlacementMode && typed.isDocumentContext
    },
    display: placement.document.start.toUpperCase(),
  },
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeEnd,
    context: 'insertDialog',
    keys: [placement.document.end],
    preventDefault: true,
    when: (context) => {
      const typed = context as InsertDialogShortcutRuntimeContext
      return typed.hasPlacementMode && typed.isDocumentContext
    },
    display: placement.document.end.toUpperCase(),
  },
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeAfter,
    context: 'insertDialog',
    keys: [placement.selected.after],
    preventDefault: true,
    when: (context) => {
      const typed = context as InsertDialogShortcutRuntimeContext
      return typed.hasPlacementMode && !typed.isDocumentContext
    },
    display: placement.selected.after.toUpperCase(),
  },
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeBefore,
    context: 'insertDialog',
    keys: [placement.selected.before],
    preventDefault: true,
    when: (context) => {
      const typed = context as InsertDialogShortcutRuntimeContext
      return typed.hasPlacementMode && !typed.isDocumentContext
    },
    display: placement.selected.before.toUpperCase(),
  },
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeInside,
    context: 'insertDialog',
    keys: [placement.selected.inside],
    preventDefault: true,
    when: (context) => {
      const typed = context as InsertDialogShortcutRuntimeContext
      return typed.hasPlacementMode && !typed.isDocumentContext
    },
    display: placement.selected.inside.toUpperCase(),
  },
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.navigatePrevious,
    context: 'insertDialog',
    keys: [navigation.previous],
    preventDefault: true,
    when: (context) => (context as InsertDialogShortcutRuntimeContext).hasSelectionMode,
    display: 'Arrow Up',
  },
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.navigateNext,
    context: 'insertDialog',
    keys: [navigation.next],
    preventDefault: true,
    when: (context) => (context as InsertDialogShortcutRuntimeContext).hasSelectionMode,
    display: 'Arrow Down',
  },
  {
    commandId: INSERT_DIALOG_SHORTCUT_COMMAND_IDS.confirm,
    context: 'insertDialog',
    keys: [navigation.confirm],
    preventDefault: true,
    when: (context) => (context as InsertDialogShortcutRuntimeContext).hasSelectionMode,
    display: 'Enter',
  },
]

for (const [index, key] of navigation.quickSelect.entries()) {
  const optionIndex = index + 1
  INSERT_DIALOG_KEYBINDINGS.push({
    commandId: toQuickSelectCommandId(optionIndex),
    context: 'insertDialog',
    keys: [key],
    preventDefault: true,
    when: (context) => (context as InsertDialogShortcutRuntimeContext).hasSelectionMode,
    display: key,
  })
}

export const INSERT_DIALOG_SHORTCUT_REGISTRY: ShortcutRegistryDefinition<InsertDialogShortcutRuntimeContext> =
  defineShortcutRegistry({
    commands: INSERT_DIALOG_COMMANDS,
    keybindings: INSERT_DIALOG_KEYBINDINGS,
  })

assertValidShortcutRegistry(INSERT_DIALOG_SHORTCUT_REGISTRY)

export function getInsertDialogShortcutRegistry(): ShortcutRegistryDefinition<InsertDialogShortcutRuntimeContext> {
  return INSERT_DIALOG_SHORTCUT_REGISTRY
}
