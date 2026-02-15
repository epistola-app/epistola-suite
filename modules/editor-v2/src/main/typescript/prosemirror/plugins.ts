/**
 * ProseMirror plugin assembly for Epistola text editing.
 *
 * Creates the full plugin array: history, keymaps, input rules,
 * gap/drop cursor, and bubble menu.
 */

import { history, undo, redo } from 'prosemirror-history'
import { keymap } from 'prosemirror-keymap'
import { baseKeymap } from 'prosemirror-commands'
import { dropCursor } from 'prosemirror-dropcursor'
import { gapCursor } from 'prosemirror-gapcursor'
import { toggleMark } from 'prosemirror-commands'
import type { Schema } from 'prosemirror-model'
import type { Plugin } from 'prosemirror-state'
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
 * Create the full set of ProseMirror plugins for Epistola text blocks.
 */
export function createPlugins(schema: Schema, _options: PluginOptions): Plugin[] {
  return [
    history(),
    keymap({ 'Mod-z': undo, 'Mod-y': redo, 'Mod-Shift-z': redo }),
    markKeymap(schema),
    keymap(baseKeymap),
    epistolaInputRules(schema),
    dropCursor(),
    gapCursor(),
    bubbleMenuPlugin(schema),
  ]
}
