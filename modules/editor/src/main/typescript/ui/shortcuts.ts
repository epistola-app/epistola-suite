import {
  buildShortcutHelperProjection,
  type ShortcutHelperProjection,
  type ShortcutHelperProjectionGroup,
  type ShortcutHelperProjectionItem,
  type ShortcutHelperProjectionOptions,
} from '../shortcuts/helper-projection.js'

export interface ShortcutHelpItem {
  keys: string
  action: string
  active: boolean
}

export interface ShortcutGroup {
  id: string
  title: string
  fullWidth: boolean
  layout: 'one-column' | 'two-column'
  items: readonly ShortcutHelpItem[]
}

export interface ShortcutGroupsProjection {
  groups: readonly ShortcutGroup[]
  footerTip: string
}

function toShortcutHelpItem(item: ShortcutHelperProjectionItem): ShortcutHelpItem {
  return {
    keys: item.keys,
    action: item.action,
    active: item.active,
  }
}

function toShortcutGroup(group: ShortcutHelperProjectionGroup): ShortcutGroup {
  return {
    id: group.id,
    title: group.title,
    fullWidth: group.fullWidth,
    layout: group.layout,
    items: group.items.map((item) => toShortcutHelpItem(item)),
  }
}

export function buildShortcutGroupsProjection(
  options: ShortcutHelperProjectionOptions = {},
): ShortcutGroupsProjection {
  const projection: ShortcutHelperProjection = buildShortcutHelperProjection(options)
  return {
    groups: projection.groups.map((group) => toShortcutGroup(group)),
    footerTip: projection.footerTip,
  }
}

export function buildShortcutGroups(options: ShortcutHelperProjectionOptions = {}): ShortcutGroup[] {
  return [...buildShortcutGroupsProjection(options).groups]
}
