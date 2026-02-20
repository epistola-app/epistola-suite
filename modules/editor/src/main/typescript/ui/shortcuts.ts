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
  { key: 'c', label: 'Leader + C', action: 'Focus canvas', successMessage: 'Focused canvas', idleToken: 'C' },
  { key: 'd', label: 'Leader + D', action: 'Duplicate selected block', successMessage: 'Duplicated block', idleToken: 'D' },
  { key: 'a', label: 'Leader + A', action: 'Open insert block dialog', successMessage: 'Insert dialog opened', idleToken: 'A' },
  { key: 'r', label: 'Leader + R', action: 'Focus preview divider', successMessage: 'Focused preview divider', idleToken: 'R' },
  { key: '?', label: 'Leader + ?', action: 'Open shortcuts help', successMessage: 'Opened shortcuts help', idleToken: '?' },
  { key: '1', label: 'Leader + 1', action: 'Focus Blocks panel', successMessage: 'Focused Blocks panel', idleToken: '1' },
  { key: '2', label: 'Leader + 2', action: 'Focus Structure panel', successMessage: 'Focused Structure panel', idleToken: '2' },
  { key: '3', label: 'Leader + 3', action: 'Focus Inspector panel', successMessage: 'Focused Inspector panel', idleToken: '3' },
  { key: 'arrowup', label: 'Leader + ↑', action: 'Move selected block up', successMessage: 'Moved block up', idleToken: '↑' },
  { key: 'arrowdown', label: 'Leader + ↓', action: 'Move selected block down', successMessage: 'Moved block down', idleToken: '↓' },
]

export const CORE_SHORTCUTS: Array<{ keys: string; action: string }> = [
  { keys: '{cmd} + S', action: 'Save' },
  { keys: '{cmd} + Z', action: 'Undo' },
  { keys: '{cmd} + Shift + Z or {cmd} + Y', action: 'Redo' },
  { keys: 'Escape', action: 'Clear selected block and focus canvas' },
  { keys: 'Arrow Up / Arrow Down', action: 'Move selected block' },
  { keys: 'Delete / Backspace', action: 'Delete selected block' },
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
  ]
}
