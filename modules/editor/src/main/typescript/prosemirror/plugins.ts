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
  EDITOR_SHORTCUTS_CONFIG,
  type ShortcutBinding,
  type TextShortcutConfig,
  type TextShortcutId,
} from '../shortcuts-config.js'
import { epistolaInputRules } from './input-rules.js'
import { bubbleMenuPlugin } from './bubble-menu.js'
import type { ExpressionNodeViewOptions } from './ExpressionNodeView.js'

export interface PluginOptions {
  expressionNodeViewOptions: ExpressionNodeViewOptions
}

const TEXT_SHORTCUTS_BY_ID = new Map(
  EDITOR_SHORTCUTS_CONFIG.text.map((shortcut) => [shortcut.id, shortcut] as const),
)

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

function getTextShortcut(shortcutId: TextShortcutId): TextShortcutConfig {
  const shortcut = TEXT_SHORTCUTS_BY_ID.get(shortcutId)
  if (!shortcut) {
    throw new Error(`Missing text shortcut config for "${shortcutId}"`)
  }
  return shortcut
}

function formatProseMirrorKey(modifiers: string[], key: string): string {
  return modifiers.length > 0 ? `${modifiers.join('-')}-${key}` : key
}

function normalizeProseMirrorKey(binding: ShortcutBinding): string {
  const normalized = binding.key.toLowerCase()
  if (PROSEMIRROR_SPECIAL_KEYS[normalized]) {
    return PROSEMIRROR_SPECIAL_KEYS[normalized]
  }
  if (normalized.length === 1) {
    return normalized
  }
  return binding.key
}

function toProseMirrorKeys(binding: ShortcutBinding): string[] {
  const modifiers: string[] = []
  if (binding.mod) modifiers.push('Mod')
  if (binding.shift) modifiers.push('Shift')
  if (binding.alt) modifiers.push('Alt')

  const keys = [formatProseMirrorKey(modifiers, normalizeProseMirrorKey(binding))]
  const isSingleLetter = binding.key.length === 1 && /^[a-z]$/i.test(binding.key)
  if (isSingleLetter && binding.shift === undefined) {
    keys.push(formatProseMirrorKey(modifiers, binding.key.toUpperCase()))
  }

  return [...new Set(keys)]
}

/**
 * Create mark toggle keymaps (Ctrl/Cmd+B/I/U).
 */
function markKeymap(schema: Schema) {
  const bindings: Record<string, ReturnType<typeof toggleMark>> = {}

  if (schema.marks.strong) {
    const boldShortcut = getTextShortcut('bold')
    for (const shortcutBinding of boldShortcut.bindings) {
      for (const key of toProseMirrorKeys(shortcutBinding)) {
        bindings[key] = toggleMark(schema.marks.strong)
      }
    }
  }
  if (schema.marks.em) {
    const italicShortcut = getTextShortcut('italic')
    for (const shortcutBinding of italicShortcut.bindings) {
      for (const key of toProseMirrorKeys(shortcutBinding)) {
        bindings[key] = toggleMark(schema.marks.em)
      }
    }
  }
  if (schema.marks.underline) {
    const underlineShortcut = getTextShortcut('underline')
    for (const shortcutBinding of underlineShortcut.bindings) {
      for (const key of toProseMirrorKeys(shortcutBinding)) {
        bindings[key] = toggleMark(schema.marks.underline)
      }
    }
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
  const hardBreakShortcuts = [
    getTextShortcut('line-break-shift-enter'),
    getTextShortcut('line-break-mod-enter'),
  ]

  for (const shortcut of hardBreakShortcuts) {
    for (const shortcutBinding of shortcut.bindings) {
      for (const key of toProseMirrorKeys(shortcutBinding)) {
        bindings[key] = cmd
      }
    }
  }

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
