import { describe, it, expect } from 'vitest'
import {
  LEADER_SHORTCUTS,
  LEADER_KEY_LABEL,
  CORE_SHORTCUTS,
  RESIZE_SHORTCUTS,
  TEXT_SHORTCUTS,
  INSERT_DIALOG_SHORTCUTS,
  buildShortcutGroups,
} from './shortcuts.js'

describe('shortcuts', () => {
  it('builds shortcut groups in expected order', () => {
    const groups = buildShortcutGroups()

    expect(groups).toHaveLength(6)
    expect(groups.map((group) => group.title)).toEqual([
      'Leader Key',
      'Leader Commands',
      'Core',
      'Resize Handle',
      'Text Editing',
      'Insert Dialog',
    ])
    expect(groups[0].items).toEqual([{ keys: LEADER_KEY_LABEL, action: 'Enter leader mode' }])
    expect(groups[2].items).toEqual(CORE_SHORTCUTS)
    expect(groups[3].items).toEqual(RESIZE_SHORTCUTS)
    expect(groups[4].items).toEqual(TEXT_SHORTCUTS)
    expect(groups[5].items).toEqual(INSERT_DIALOG_SHORTCUTS)
  })

  it('maps leader commands into Leader Commands group', () => {
    const groups = buildShortcutGroups()
    const leaderCommandGroup = groups[1]

    expect(leaderCommandGroup.items).toEqual(
      LEADER_SHORTCUTS.map((command) => ({ keys: command.label, action: command.action })),
    )
  })

  it('defines unique leader keys and idle tokens', () => {
    const keys = LEADER_SHORTCUTS.map((command) => command.key)
    const idleTokens = LEADER_SHORTCUTS.map((command) => command.idleToken)

    expect(new Set(keys).size).toBe(keys.length)
    expect(new Set(idleTokens).size).toBe(idleTokens.length)
  })

  it('includes command metadata required by leader mode', () => {
    for (const command of LEADER_SHORTCUTS) {
      expect(command.key.length).toBeGreaterThan(0)
      expect(command.idleToken.length).toBeGreaterThan(0)
      expect(command.successMessage.length).toBeGreaterThan(0)
    }
  })

  it('uses consistent Leader + label format for displayed commands', () => {
    for (const command of LEADER_SHORTCUTS) {
      expect(command.label.startsWith('Leader + ')).toBe(true)
      const suffix = command.label.slice('Leader + '.length).trim()
      expect(suffix.length).toBeGreaterThan(0)
    }
  })

  it('includes leader shortcut for focusing resize handle', () => {
    expect(LEADER_SHORTCUTS).toContainEqual(
      expect.objectContaining({
        key: 'r',
        label: 'Leader + R',
        action: 'Focus resize handle',
        successMessage: 'Focused resize handle',
        idleToken: 'R',
      }),
    )
  })
})
