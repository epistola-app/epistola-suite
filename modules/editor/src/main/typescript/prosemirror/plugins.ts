/**
 * ProseMirror plugin assembly for Epistola text editing.
 *
 * Creates the full plugin array: history, keymaps, input rules,
 * gap/drop cursor, and bubble menu.
 */

import { history } from 'prosemirror-history'
import { keymap } from 'prosemirror-keymap'
import { baseKeymap, chainCommands, exitCode } from 'prosemirror-commands'
import { dropCursor } from 'prosemirror-dropcursor'
import { gapCursor } from 'prosemirror-gapcursor'
import { toggleMark } from 'prosemirror-commands'
import type { Schema } from 'prosemirror-model'
import type { Plugin, Command } from 'prosemirror-state'
import {
  TEXT_SHORTCUT_COMMAND_IDS,
  getTextShortcutBindingsForCommandId,
} from '../shortcuts/text-runtime.js'
import { epistolaInputRules } from './input-rules.js'
import { bubbleMenuPlugin } from './bubble-menu.js'
import type { ExpressionNodeViewOptions } from './ExpressionNodeView.js'

type TextShortcutCommandId = (typeof TEXT_SHORTCUT_COMMAND_IDS)[keyof typeof TEXT_SHORTCUT_COMMAND_IDS]

export interface PluginOptions {
  expressionNodeViewOptions: ExpressionNodeViewOptions
}

const PROSEMIRROR_SPECIAL_KEYS: Record<string, string> = {
  enter: 'Enter',
  escape: 'Esc',
  backspace: 'Backspace',
  delete: 'Delete',
  arrowup: 'ArrowUp',
  arrowdown: 'ArrowDown',
  arrowleft: 'ArrowLeft',
  arrowright: 'ArrowRight',
  space: 'Space',
}

function formatProseMirrorKey(modifiers: string[], key: string): string {
  return modifiers.length > 0 ? `${modifiers.join('-')}-${key}` : key
}

function normalizeProseMirrorKeyToken(token: string): string {
  const normalized = token.toLowerCase()
  if (PROSEMIRROR_SPECIAL_KEYS[normalized]) {
    return PROSEMIRROR_SPECIAL_KEYS[normalized]
  }
  if (normalized.length === 1) {
    return normalized
  }
  return token
}

export function toProseMirrorKeysFromShortcutStroke(shortcutStroke: string): string[] {
  const compact = shortcutStroke.trim().toLowerCase()
  if (!compact || compact.includes(' ')) {
    return []
  }

  const parts = compact.split('+').filter((part) => part.length > 0)
  if (parts.length === 0) {
    return []
  }

  const modifiers: string[] = []
  let keyToken = ''

  for (const part of parts) {
    if (part === 'mod') {
      modifiers.push('Mod')
      continue
    }
    if (part === 'shift') {
      modifiers.push('Shift')
      continue
    }
    if (part === 'alt') {
      modifiers.push('Alt')
      continue
    }

    keyToken = part.startsWith('code:') ? part.slice('code:'.length) : part
  }

  if (!keyToken) {
    return []
  }

  const keys = [formatProseMirrorKey(modifiers, normalizeProseMirrorKeyToken(keyToken))]
  const hasShiftModifier = modifiers.includes('Shift')
  const isSingleLetter = keyToken.length === 1 && /^[a-z]$/i.test(keyToken)
  if (isSingleLetter && !hasShiftModifier) {
    keys.push(formatProseMirrorKey(modifiers, keyToken.toUpperCase()))
  }

  return [...new Set(keys)]
}

export function getProseMirrorKeysForTextCommand(commandId: TextShortcutCommandId): string[] {
  const bindings = getTextShortcutBindingsForCommandId(commandId)
  return [...new Set(bindings.flatMap((binding) => binding.keys.flatMap((key) => toProseMirrorKeysFromShortcutStroke(key))))]
}

function assignTextCommandBindings(
  bindings: Record<string, Command>,
  commandId: TextShortcutCommandId,
  command: Command,
): void {
  for (const key of getProseMirrorKeysForTextCommand(commandId)) {
    bindings[key] = command
  }
}

/**
 * Create mark toggle keymaps (Ctrl/Cmd+B/I/U).
 */
function markKeymap(schema: Schema) {
  const bindings: Record<string, Command> = {}

  if (schema.marks.strong) {
    assignTextCommandBindings(bindings, TEXT_SHORTCUT_COMMAND_IDS.bold, toggleMark(schema.marks.strong))
  }
  if (schema.marks.em) {
    assignTextCommandBindings(bindings, TEXT_SHORTCUT_COMMAND_IDS.italic, toggleMark(schema.marks.em))
  }
  if (schema.marks.underline) {
    assignTextCommandBindings(bindings, TEXT_SHORTCUT_COMMAND_IDS.underline, toggleMark(schema.marks.underline))
  }

  return keymap(bindings)
}

/**
 * Create a keymap for Shift-Enter → hard_break (line break without new paragraph).
 */
function hardBreakKeymap(schema: Schema) {
  const br = schema.nodes.hard_break
  if (!br) return keymap({})

  const cmd: Command = chainCommands(exitCode, (state, dispatch) => {
    if (dispatch) dispatch(state.tr.replaceSelectionWith(br.create()).scrollIntoView())
    return true
  })

  const bindings: Record<string, Command> = {}
  assignTextCommandBindings(bindings, TEXT_SHORTCUT_COMMAND_IDS.lineBreakShiftEnter, cmd)
  assignTextCommandBindings(bindings, TEXT_SHORTCUT_COMMAND_IDS.lineBreakModEnter, cmd)

  return keymap(bindings)
}

/**
 * Create the full set of ProseMirror plugins for Epistola text blocks.
 */
export function createPlugins(schema: Schema, _options: PluginOptions): Plugin[] {
  return [
    history(),
    markKeymap(schema),
    hardBreakKeymap(schema),
    keymap(baseKeymap),
    epistolaInputRules(schema),
    dropCursor(),
    gapCursor(),
    bubbleMenuPlugin(schema),
  ]
}
