import { describe, expect, it } from 'vitest'
import {
  getProseMirrorKeysForTextCommand,
  toProseMirrorKeysFromShortcutStroke,
} from './plugins.js'
import {
  TEXT_SHORTCUT_COMMAND_IDS,
  getTextShortcutBindingsForCommandId,
} from '../shortcuts/text-runtime.js'

describe('prosemirror shortcut adapter', () => {
  it('converts canonical shortcut strokes to ProseMirror keys', () => {
    expect(toProseMirrorKeysFromShortcutStroke('mod+b')).toContain('Mod-b')
    expect(toProseMirrorKeysFromShortcutStroke('shift+enter')).toContain('Shift-Enter')
    expect(toProseMirrorKeysFromShortcutStroke('mod+code:space')).toContain('Mod-Space')
  })

  it('derives text command keymaps from text command registry bindings', () => {
    const boldBindings = getTextShortcutBindingsForCommandId(TEXT_SHORTCUT_COMMAND_IDS.bold)
    expect(boldBindings.length).toBeGreaterThan(0)

    const boldKeys = getProseMirrorKeysForTextCommand(TEXT_SHORTCUT_COMMAND_IDS.bold)
    expect(boldKeys).toContain('Mod-b')

    const hardBreakKeys = getProseMirrorKeysForTextCommand(TEXT_SHORTCUT_COMMAND_IDS.lineBreakShiftEnter)
    expect(hardBreakKeys).toContain('Shift-Enter')
  })
})
