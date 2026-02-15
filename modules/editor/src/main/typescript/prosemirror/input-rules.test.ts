import { describe, it, expect } from 'vitest'
import { EditorState } from 'prosemirror-state'
import { epistolaSchema } from './schema.js'
import { expressionInputRule } from './input-rules.js'

/**
 * Input rules are triggered by EditorView's handleTextInput, which is
 * not available in headless tests. Instead we test the InputRule's
 * handler function directly by simulating what ProseMirror does:
 * check if the regex matches the text before cursor, then call the handler.
 */

describe('expressionInputRule', () => {
  const rule = expressionInputRule(epistolaSchema)

  it('rule regex matches {{ at end of text', () => {
    // The regex /\{\{$/ should match `{{` at the end of a string
    const match = '{{'.match(rule.match)
    expect(match).not.toBeNull()
  })

  it('rule regex does not match single {', () => {
    const match = '{'.match(rule.match)
    expect(match).toBeNull()
  })

  it('rule regex matches {{ after text', () => {
    const match = 'Hello {{'.match(rule.match)
    expect(match).not.toBeNull()
  })

  it('handler creates expression node in transaction', () => {
    // Create a state with a paragraph containing "X{{" text
    const text = 'X{{'
    const doc = epistolaSchema.node('doc', null, [
      epistolaSchema.node('paragraph', null, [epistolaSchema.text(text)]),
    ])
    const state = EditorState.create({ doc })

    // The rule handler expects (state, match, start, end)
    // match position: the `{{` starts at position 2 inside the paragraph
    // In ProseMirror coords: paragraph opens at 1, text "X" is at 2, "{{" is at 3-4
    // So start=3, end=5 (exclusive)
    const match = text.match(rule.match)!
    const start = 3 // position of first `{` in the doc
    const end = 5 // position after second `{`

    const handler = rule.handler as (
      state: EditorState,
      match: RegExpMatchArray,
      start: number,
      end: number,
    ) => ReturnType<typeof state.tr.replaceWith> | null

    const tr = handler(state, match, start, end)
    expect(tr).not.toBeNull()

    if (tr) {
      const newState = state.apply(tr)
      const para = newState.doc.content.firstChild!

      let found = false
      para.forEach((node) => {
        if (node.type.name === 'expression') {
          expect(node.attrs.expression).toBe('')
          expect(node.attrs.isNew).toBe(true)
          found = true
        }
      })
      expect(found).toBe(true)
    }
  })
})
