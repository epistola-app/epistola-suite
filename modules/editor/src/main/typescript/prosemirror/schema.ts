/**
 * ProseMirror schema definition for Epistola rich text editing.
 *
 * Combines basic schema nodes/marks with custom expression node.
 * Produces JSON compatible with TipTap (ProseMirror JSON format).
 */

import { Schema, type NodeSpec, type MarkSpec } from 'prosemirror-model'
import { nodes as basicNodes, marks as basicMarks } from 'prosemirror-schema-basic'
import { addListNodes } from 'prosemirror-schema-list'

// ---------------------------------------------------------------------------
// Custom node: expression (inline atom)
// ---------------------------------------------------------------------------

const expressionNode: NodeSpec = {
  group: 'inline',
  inline: true,
  atom: true,
  attrs: {
    expression: { default: '' },
    isNew: { default: false },
  },
  toDOM(node) {
    return [
      'span',
      {
        class: 'expression-chip',
        'data-expression': node.attrs.expression,
      },
      node.attrs.expression || '\u200B',
    ]
  },
  parseDOM: [
    {
      tag: 'span[data-expression]',
      getAttrs(dom) {
        const el = dom as HTMLElement
        return { expression: el.getAttribute('data-expression') ?? '', isNew: false }
      },
    },
  ],
}

// ---------------------------------------------------------------------------
// Underline mark (not in prosemirror-schema-basic)
// ---------------------------------------------------------------------------

const underlineMark: MarkSpec = {
  toDOM() {
    return ['u', 0]
  },
  parseDOM: [
    { tag: 'u' },
    { style: 'text-decoration=underline' },
  ],
}

// ---------------------------------------------------------------------------
// Strikethrough mark (basic only has 'code', 'em', 'strong', 'link')
// ---------------------------------------------------------------------------

const strikethroughMark: MarkSpec = {
  toDOM() {
    return ['s', 0]
  },
  parseDOM: [
    { tag: 's' },
    { tag: 'del' },
    { tag: 'strike' },
    { style: 'text-decoration=line-through' },
  ],
}

// ---------------------------------------------------------------------------
// Schema assembly
// ---------------------------------------------------------------------------

const baseNodes: Record<string, NodeSpec> = {
  doc: {
    content: 'block+',
  },
  paragraph: basicNodes.paragraph,
  text: basicNodes.text,
  heading: {
    content: 'inline*',
    group: 'block',
    defining: true,
    attrs: { level: { default: 1 } },
    toDOM(node) {
      return ['h' + node.attrs.level, 0]
    },
    parseDOM: [
      { tag: 'h1', attrs: { level: 1 } },
      { tag: 'h2', attrs: { level: 2 } },
      { tag: 'h3', attrs: { level: 3 } },
    ],
  },
  hard_break: basicNodes.hard_break,
  expression: expressionNode,
}

// Add list nodes from prosemirror-schema-list.
// addListNodes expects an OrderedMap — we create a temp Schema to get one.
const tempSchema = new Schema({ nodes: baseNodes, marks: {} })
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const withListNodes = addListNodes(tempSchema.spec.nodes as any, 'paragraph block*', 'block')

// Combine marks: basic (strong, em, code, link) + underline + strikethrough
const allMarks: Record<string, MarkSpec> = {
  ...basicMarks,
  underline: underlineMark,
  strikethrough: strikethroughMark,
}

/**
 * The ProseMirror schema for Epistola text blocks.
 */
export const epistolaSchema = new Schema({
  nodes: withListNodes,
  marks: allMarks,
})

export { expressionNode, underlineMark, strikethroughMark }
