import {
  EDITOR_SHORTCUTS_CONFIG,
  buildShortcutGroupsFromConfig,
  type LeaderShortcutCommandConfig,
  type ShortcutGroupConfig,
  type ShortcutHelpItem,
} from '../shortcuts-config.js'

export interface LeaderShortcutDefinition {
  key: string
  label: string
  action: string
  successMessage: string
  idleToken: string
}

export type ShortcutGroup = ShortcutGroupConfig

function toLegacyLeaderShortcut(command: LeaderShortcutCommandConfig): LeaderShortcutDefinition {
  const key = command.keys[0]
  if (!key) {
    throw new Error(`Leader shortcut command "${command.id}" must define at least one key`)
  }

  return {
    key,
    label: command.helpKeys,
    action: command.action,
    successMessage: command.successMessage,
    idleToken: command.idleToken,
  }
}

export const LEADER_KEY_LABEL = EDITOR_SHORTCUTS_CONFIG.leader.activation.helpKeys

export const LEADER_SHORTCUTS: LeaderShortcutDefinition[] = EDITOR_SHORTCUTS_CONFIG.leader.commands.map(
  toLegacyLeaderShortcut,
)

export const CORE_SHORTCUTS: ShortcutHelpItem[] = EDITOR_SHORTCUTS_CONFIG.core.map((shortcut) => ({
  keys: shortcut.helpKeys,
  action: shortcut.action,
}))

export const RESIZE_SHORTCUTS: ShortcutHelpItem[] = EDITOR_SHORTCUTS_CONFIG.resize.keyboard.map((shortcut) => ({
  keys: shortcut.helpKeys,
  action: shortcut.action,
}))

export const TEXT_SHORTCUTS: ShortcutHelpItem[] = EDITOR_SHORTCUTS_CONFIG.text.map((shortcut) => ({
  keys: shortcut.helpKeys,
  action: shortcut.action,
}))

export const INSERT_DIALOG_SHORTCUTS: ShortcutHelpItem[] = EDITOR_SHORTCUTS_CONFIG.insertDialog.help.map(
  (shortcut) => ({ keys: shortcut.keys, action: shortcut.action }),
)

export function buildShortcutGroups(): ShortcutGroup[] {
  return buildShortcutGroupsFromConfig()
}
