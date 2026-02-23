export interface LeaderShortcutDefinition {
  key: string
  label: string
  action: string
  successMessage: string
  idleToken: string
}

export interface ShortcutGroup {
  title: string
  dividerAfter?: boolean
  items: Array<{ keys: string; action: string }>
}

export const LEADER_KEY_LABEL = '{cmd} + Space'

export const LEADER_SHORTCUTS: LeaderShortcutDefinition[] = [
  { key: 'p', label: 'Leader + P', action: 'Preview', successMessage: 'Preview toggled', idleToken: 'P' },
  { key: 'd', label: 'Leader + D', action: 'Duplicate selected block', successMessage: 'Duplicated block', idleToken: 'D' },
  { key: 'a', label: 'Leader + A', action: 'Open insert block dialog', successMessage: 'Insert dialog opened', idleToken: 'A' },
  { key: '?', label: 'Leader + ? or /', action: 'Open shortcuts help', successMessage: 'Opened shortcuts help', idleToken: '?' },
  { key: '1', label: 'Leader + 1', action: 'Focus Blocks panel', successMessage: 'Focused Blocks panel', idleToken: '1' },
  { key: '2', label: 'Leader + 2', action: 'Focus Structure panel', successMessage: 'Focused Structure panel', idleToken: '2' },
  { key: '3', label: 'Leader + 3', action: 'Focus Inspector panel', successMessage: 'Focused Inspector panel', idleToken: '3' },
  { key: 'r', label: 'Leader + R', action: 'Focus resize handle', successMessage: 'Focused resize handle', idleToken: 'R' },
  { key: 'arrowup', label: 'Leader + ↑', action: 'Move selected block up', successMessage: 'Moved block up', idleToken: '↑' },
  { key: 'arrowdown', label: 'Leader + ↓', action: 'Move selected block down', successMessage: 'Moved block down', idleToken: '↓' },
]

export const CORE_SHORTCUTS: Array<{ keys: string; action: string }> = [
  { keys: '{cmd} + S', action: 'Save' },
  { keys: '{cmd} + Z', action: 'Undo' },
  { keys: '{cmd} + Shift + Z / {cmd} + Y', action: 'Redo' },
  { keys: 'Delete / Backspace', action: 'Delete selected block' },
  { keys: 'Esc', action: 'Deselect selected block' },
]

export const RESIZE_SHORTCUTS: Array<{ keys: string; action: string }> = [
  { keys: '← (focused handle)', action: 'Grow preview width' },
  { keys: '→ (focused handle)', action: 'Shrink preview width' },
  { keys: '→ at min width', action: 'Close preview' },
]

export const TEXT_SHORTCUTS: Array<{ keys: string; action: string }> = [
  { keys: '{cmd} + B', action: 'Bold' },
  { keys: '{cmd} + I', action: 'Italic' },
  { keys: '{cmd} + U', action: 'Underline' },
  { keys: 'Shift + Enter', action: 'Line break' },
  { keys: '{cmd} + Enter', action: 'Line break' },
]

export const INSERT_DIALOG_SHORTCUTS: Array<{ keys: string; action: string }> = [
  { keys: 'A / B / I', action: 'Choose insert placement' },
  { keys: 'S / E', action: 'Insert at start / end (document)' },
  { keys: '1-9', action: 'Choose option' },
  { keys: '↑ / ↓', action: 'Navigate options' },
  { keys: 'Enter', action: 'Confirm selection' },
  { keys: 'Esc', action: 'Back / close dialog' },
]

export function buildShortcutGroups(): ShortcutGroup[] {
  return [
    {
      title: 'Leader Key',
      dividerAfter: true,
      items: [{ keys: LEADER_KEY_LABEL, action: 'Enter leader mode' }],
    },
    {
      title: 'Leader Commands',
      items: LEADER_SHORTCUTS.map((command) => ({ keys: command.label, action: command.action })),
    },
    {
      title: 'Core',
      items: CORE_SHORTCUTS,
    },
    {
      title: 'Resize Handle',
      items: RESIZE_SHORTCUTS,
    },
    {
      title: 'Text Editing',
      items: TEXT_SHORTCUTS,
    },
    {
      title: 'Insert Dialog',
      items: INSERT_DIALOG_SHORTCUTS,
    },
  ]
}
