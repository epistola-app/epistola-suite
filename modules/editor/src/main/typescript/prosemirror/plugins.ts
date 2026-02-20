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
import { epistolaInputRules } from './input-rules.js'
import { bubbleMenuPlugin } from './bubble-menu.js'
import type { ExpressionNodeViewOptions } from './ExpressionNodeView.js'

export interface PluginOptions {
  expressionNodeViewOptions: ExpressionNodeViewOptions
}

/**
 * Create mark toggle keymaps (Ctrl/Cmd+B/I/U).
 */
function markKeymap(schema: Schema) {
  const bindings: Record<string, ReturnType<typeof toggleMark>> = {}

  if (schema.marks.strong) {
    bindings['Mod-b'] = toggleMark(schema.marks.strong)
    bindings['Mod-B'] = toggleMark(schema.marks.strong)
  }
  if (schema.marks.em) {
    bindings['Mod-i'] = toggleMark(schema.marks.em)
    bindings['Mod-I'] = toggleMark(schema.marks.em)
  }
  if (schema.marks.underline) {
    bindings['Mod-u'] = toggleMark(schema.marks.underline)
    bindings['Mod-U'] = toggleMark(schema.marks.underline)
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

  return keymap({ 'Shift-Enter': cmd, 'Mod-Enter': cmd })
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
