import { describe, it, expect } from 'vitest'
import { epistolaSchema } from './schema.js'

describe('epistolaSchema', () => {
  it('creates a valid empty document', () => {
    const doc = epistolaSchema.node('doc', null, [
      epistolaSchema.node('paragraph'),
    ])
    expect(doc.type.name).toBe('doc')
    expect(doc.content.childCount).toBe(1)
    expect(doc.content.firstChild!.type.name).toBe('paragraph')
  })

  it('supports heading levels 1-3', () => {
    for (const level of [1, 2, 3]) {
      const heading = epistolaSchema.node('heading', { level })
      expect(heading.attrs.level).toBe(level)
    }
  })

  it('has expression node type', () => {
    const nodeType = epistolaSchema.nodes.expression
    expect(nodeType).toBeDefined()
    expect(nodeType.isInline).toBe(true)
    expect(nodeType.isAtom).toBe(true)
  })

  it('creates expression node with attrs', () => {
    const node = epistolaSchema.nodes.expression.create({
      expression: 'customer.name',
      isNew: false,
    })
    expect(node.attrs.expression).toBe('customer.name')
    expect(node.attrs.isNew).toBe(false)
  })

  it('creates expression node with defaults', () => {
    const node = epistolaSchema.nodes.expression.create()
    expect(node.attrs.expression).toBe('')
    expect(node.attrs.isNew).toBe(false)
  })

  // -------------------------------------------------------------------------
  // JSON roundtrip (TipTap compatibility)
  // -------------------------------------------------------------------------

  it('expression node roundtrips through JSON', () => {
    const node = epistolaSchema.nodes.expression.create({
      expression: 'order.total',
      isNew: false,
    })

    const json = node.toJSON()
    expect(json).toEqual({
      type: 'expression',
      attrs: { expression: 'order.total', isNew: false },
    })

    const restored = epistolaSchema.nodeFromJSON(json)
    expect(restored.attrs.expression).toBe('order.total')
    expect(restored.type.name).toBe('expression')
  })

  it('document with expressions roundtrips through JSON', () => {
    const doc = epistolaSchema.node('doc', null, [
      epistolaSchema.node('paragraph', null, [
        epistolaSchema.text('Hello '),
        epistolaSchema.nodes.expression.create({
          expression: 'name',
          isNew: false,
        }),
        epistolaSchema.text('!'),
      ]),
    ])

    const json = doc.toJSON()
    const restored = epistolaSchema.nodeFromJSON(json)

    expect(restored.content.childCount).toBe(1)
    const para = restored.content.firstChild!
    expect(para.content.childCount).toBe(3)
    expect(para.content.child(1).type.name).toBe('expression')
    expect(para.content.child(1).attrs.expression).toBe('name')
  })

  // -------------------------------------------------------------------------
  // Marks
  // -------------------------------------------------------------------------

  it('has bold (strong) mark', () => {
    expect(epistolaSchema.marks.strong).toBeDefined()
  })

  it('has italic (em) mark', () => {
    expect(epistolaSchema.marks.em).toBeDefined()
  })

  it('has underline mark', () => {
    expect(epistolaSchema.marks.underline).toBeDefined()
  })

  it('has strikethrough mark', () => {
    expect(epistolaSchema.marks.strikethrough).toBeDefined()
  })

  it('applies marks to text and roundtrips', () => {
    const bold = epistolaSchema.marks.strong.create()
    const textNode = epistolaSchema.text('bold text', [bold])
    expect(textNode.marks.length).toBe(1)
    expect(textNode.marks[0].type.name).toBe('strong')

    const json = textNode.toJSON()
    expect(json.marks).toEqual([{ type: 'strong' }])

    const restored = epistolaSchema.nodeFromJSON(json)
    expect(restored.marks.length).toBe(1)
    expect(restored.marks[0].type.name).toBe('strong')
  })

  // -------------------------------------------------------------------------
  // List nodes
  // -------------------------------------------------------------------------

  it('has bullet_list node type', () => {
    expect(epistolaSchema.nodes.bullet_list).toBeDefined()
  })

  it('has ordered_list node type', () => {
    expect(epistolaSchema.nodes.ordered_list).toBeDefined()
  })

  it('has list_item node type', () => {
    expect(epistolaSchema.nodes.list_item).toBeDefined()
  })
})
