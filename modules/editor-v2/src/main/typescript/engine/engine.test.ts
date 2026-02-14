import { describe, it, expect, beforeEach } from 'vitest'
import { EditorEngine } from './EditorEngine.js'
import { buildIndexes } from './indexes.js'
import type { Command, InsertNode, MoveNode } from './commands.js'
import { ComponentRegistry, createDefaultRegistry } from './registry.js'
import {
  createTestDocument,
  createTestDocumentWithChildren,
  testRegistry,
  nodeId,
  slotId,
  resetCounter,
} from './test-helpers.js'
import type { NodeId, SlotId, TemplateDocument, Node, Slot } from '../types/index.js'

beforeEach(() => {
  resetCounter()
})

// ---------------------------------------------------------------------------
// Indexes
// ---------------------------------------------------------------------------

describe('buildIndexes', () => {
  it('builds parent maps from a document', () => {
    const { doc, rootId, rootSlotId, textNodeId, containerNodeId } =
      createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)

    expect(indexes.parentSlotByNodeId.get(textNodeId)).toBe(rootSlotId)
    expect(indexes.parentSlotByNodeId.get(containerNodeId)).toBe(rootSlotId)
    expect(indexes.parentNodeByNodeId.get(textNodeId)).toBe(rootId)
    expect(indexes.parentSlotByNodeId.has(rootId)).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// InsertNode
// ---------------------------------------------------------------------------

describe('InsertNode', () => {
  let engine: EditorEngine
  let registry: ComponentRegistry
  let doc: TemplateDocument
  let rootSlotId: SlotId

  beforeEach(() => {
    registry = testRegistry()
    const setup = createTestDocumentWithChildren()
    doc = setup.doc
    rootSlotId = setup.rootSlotId
    engine = new EditorEngine(doc, registry)
  })

  it('inserts a node at the end of a slot', () => {
    const { node, slots } = registry.createNode('text')
    const result = engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: rootSlotId,
      index: -1,
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.slots[rootSlotId].children).toContain(node.id)
    expect(engine.doc.nodes[node.id]).toBeDefined()
  })

  it('inserts a node at a specific index', () => {
    const { node, slots } = registry.createNode('text')
    const result = engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: rootSlotId,
      index: 0,
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.slots[rootSlotId].children[0]).toBe(node.id)
  })

  it('rejects inserting into non-existent slot', () => {
    const { node, slots } = registry.createNode('text')
    const result = engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: slotId('nonexistent'),
      index: -1,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toContain('not found')
    }
  })

  it('rejects inserting a duplicate node ID', () => {
    const { doc: docWithChildren, textNodeId } = createTestDocumentWithChildren()
    const eng = new EditorEngine(docWithChildren, registry)
    const existingNode = eng.doc.nodes[textNodeId]

    const result = eng.dispatch({
      type: 'InsertNode',
      node: existingNode,
      slots: [],
      targetSlotId: eng.doc.nodes[eng.doc.root].slots[0],
      index: -1,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toContain('already exists')
    }
  })

  it('inserts a container with its own slots', () => {
    const { node, slots } = registry.createNode('container')

    expect(slots.length).toBe(1)
    expect(slots[0].name).toBe('children')

    const result = engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: rootSlotId,
      index: -1,
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.slots[slots[0].id]).toBeDefined()
    expect(engine.doc.slots[slots[0].id].nodeId).toBe(node.id)
  })
})

// ---------------------------------------------------------------------------
// RemoveNode
// ---------------------------------------------------------------------------

describe('RemoveNode', () => {
  it('removes a leaf node', () => {
    const registry = testRegistry()
    const { doc, textNodeId, rootSlotId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'RemoveNode',
      nodeId: textNodeId,
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.nodes[textNodeId]).toBeUndefined()
    expect(engine.doc.slots[rootSlotId].children).not.toContain(textNodeId)
  })

  it('removes a subtree (container + nested children)', () => {
    const registry = testRegistry()
    const { doc, containerNodeId, containerSlotId, rootSlotId } =
      createTestDocumentWithChildren()

    // Add a text node inside the container
    const innerTextId = nodeId('inner-text')
    const modifiedDoc: TemplateDocument = {
      ...doc,
      nodes: {
        ...doc.nodes,
        [innerTextId]: { id: innerTextId, type: 'text', slots: [], props: { content: null } },
      },
      slots: {
        ...doc.slots,
        [containerSlotId]: {
          ...doc.slots[containerSlotId],
          children: [innerTextId],
        },
      },
    }

    const engine = new EditorEngine(modifiedDoc, registry)
    const result = engine.dispatch({
      type: 'RemoveNode',
      nodeId: containerNodeId,
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.nodes[containerNodeId]).toBeUndefined()
    expect(engine.doc.nodes[innerTextId]).toBeUndefined()
    expect(engine.doc.slots[containerSlotId]).toBeUndefined()
    expect(engine.doc.slots[rootSlotId].children).not.toContain(containerNodeId)
  })

  it('rejects removing the root node', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'RemoveNode',
      nodeId: doc.root,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toContain('root')
    }
  })

  it('rejects removing a non-existent node', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'RemoveNode',
      nodeId: nodeId('nonexistent'),
    })

    expect(result.ok).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// MoveNode
// ---------------------------------------------------------------------------

describe('MoveNode', () => {
  it('moves a node to a different slot', () => {
    const registry = testRegistry()
    const { doc, textNodeId, containerSlotId, rootSlotId } =
      createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'MoveNode',
      nodeId: textNodeId,
      targetSlotId: containerSlotId,
      index: 0,
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.slots[rootSlotId].children).not.toContain(textNodeId)
    expect(engine.doc.slots[containerSlotId].children).toContain(textNodeId)
  })

  it('reorders within the same slot', () => {
    const registry = testRegistry()
    const { doc, textNodeId, containerNodeId, rootSlotId } =
      createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    // Text is at index 0, container at index 1. Move text to end.
    const result = engine.dispatch({
      type: 'MoveNode',
      nodeId: textNodeId,
      targetSlotId: rootSlotId,
      index: 1,
    })

    expect(result.ok).toBe(true)
    const children = engine.doc.slots[rootSlotId].children
    expect(children[0]).toBe(containerNodeId)
    expect(children[1]).toBe(textNodeId)
  })

  it('detects cycles (cannot move parent into child)', () => {
    const registry = testRegistry()
    const { doc, containerNodeId, containerSlotId, rootSlotId } =
      createTestDocumentWithChildren()

    // Add a nested container inside the first container
    const innerContainerId = nodeId('inner-container')
    const innerSlotId = slotId('inner-slot')
    const modifiedDoc: TemplateDocument = {
      ...doc,
      nodes: {
        ...doc.nodes,
        [innerContainerId]: {
          id: innerContainerId,
          type: 'container',
          slots: [innerSlotId],
        },
      },
      slots: {
        ...doc.slots,
        [containerSlotId]: {
          ...doc.slots[containerSlotId],
          children: [innerContainerId],
        },
        [innerSlotId]: {
          id: innerSlotId,
          nodeId: innerContainerId,
          name: 'children',
          children: [],
        },
      },
    }

    const engine = new EditorEngine(modifiedDoc, registry)

    // Try to move the outer container into the inner container's slot
    const result = engine.dispatch({
      type: 'MoveNode',
      nodeId: containerNodeId,
      targetSlotId: innerSlotId,
      index: 0,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toContain('cycle')
    }
  })

  it('rejects moving into itself', () => {
    const registry = testRegistry()
    const { doc, containerNodeId, containerSlotId } =
      createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'MoveNode',
      nodeId: containerNodeId,
      targetSlotId: containerSlotId,
      index: 0,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toContain('itself')
    }
  })

  it('rejects moving the root node', () => {
    const registry = testRegistry()
    const { doc, rootSlotId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'MoveNode',
      nodeId: doc.root,
      targetSlotId: rootSlotId,
      index: 0,
    })

    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toContain('root')
    }
  })
})

// ---------------------------------------------------------------------------
// UpdateNodeProps
// ---------------------------------------------------------------------------

describe('UpdateNodeProps', () => {
  it('updates props on a node', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: textNodeId,
      props: { content: { type: 'doc', content: [] } },
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.nodes[textNodeId].props).toEqual({
      content: { type: 'doc', content: [] },
    })
  })

  it('rejects updating non-existent node', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: nodeId('nonexistent'),
      props: { content: null },
    })

    expect(result.ok).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// UpdateNodeStyles
// ---------------------------------------------------------------------------

describe('UpdateNodeStyles', () => {
  it('sets styles on a node', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'UpdateNodeStyles',
      nodeId: textNodeId,
      styles: { color: '#ff0000', fontSize: '16px' },
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.nodes[textNodeId].styles).toEqual({
      color: '#ff0000',
      fontSize: '16px',
    })
  })
})

// ---------------------------------------------------------------------------
// SetStylePreset
// ---------------------------------------------------------------------------

describe('SetStylePreset', () => {
  it('sets a style preset on a node', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'SetStylePreset',
      nodeId: textNodeId,
      stylePreset: 'heading-1',
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.nodes[textNodeId].stylePreset).toBe('heading-1')
  })

  it('clears a style preset', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()

    // Set the preset first
    const docWithPreset: TemplateDocument = {
      ...doc,
      nodes: {
        ...doc.nodes,
        [textNodeId]: {
          ...doc.nodes[textNodeId],
          stylePreset: 'heading-1',
        },
      },
    }

    const engine = new EditorEngine(docWithPreset, registry)
    const result = engine.dispatch({
      type: 'SetStylePreset',
      nodeId: textNodeId,
      stylePreset: undefined,
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.nodes[textNodeId].stylePreset).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// UpdateDocumentStyles / UpdatePageSettings
// ---------------------------------------------------------------------------

describe('UpdateDocumentStyles', () => {
  it('sets document styles', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'UpdateDocumentStyles',
      styles: { fontFamily: 'Inter', fontSize: '14px' },
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.documentStylesOverride).toEqual({
      fontFamily: 'Inter',
      fontSize: '14px',
    })
  })
})

describe('UpdatePageSettings', () => {
  it('sets page settings', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    const result = engine.dispatch({
      type: 'UpdatePageSettings',
      settings: {
        format: 'Letter',
        orientation: 'landscape',
        margins: { top: 10, right: 10, bottom: 10, left: 10 },
      },
    })

    expect(result.ok).toBe(true)
    expect(engine.doc.pageSettingsOverride?.format).toBe('Letter')
    expect(engine.doc.pageSettingsOverride?.orientation).toBe('landscape')
  })
})

// ---------------------------------------------------------------------------
// Undo / Redo
// ---------------------------------------------------------------------------

describe('Undo / Redo', () => {
  it('undoes an InsertNode', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    const { node, slots } = registry.createNode('text')
    engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: rootSlotId,
      index: -1,
    })

    expect(engine.doc.nodes[node.id]).toBeDefined()
    expect(engine.canUndo).toBe(true)

    engine.undo()

    expect(engine.doc.nodes[node.id]).toBeUndefined()
    expect(engine.doc.slots[rootSlotId].children).not.toContain(node.id)
  })

  it('redoes after undo', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    const { node, slots } = registry.createNode('text')
    engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: rootSlotId,
      index: -1,
    })

    engine.undo()
    expect(engine.doc.nodes[node.id]).toBeUndefined()

    expect(engine.canRedo).toBe(true)
    engine.redo()

    expect(engine.doc.nodes[node.id]).toBeDefined()
    expect(engine.doc.slots[rootSlotId].children).toContain(node.id)
  })

  it('undoes a RemoveNode (restores subtree)', () => {
    const registry = testRegistry()
    const { doc, textNodeId, rootSlotId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    engine.dispatch({ type: 'RemoveNode', nodeId: textNodeId })
    expect(engine.doc.nodes[textNodeId]).toBeUndefined()

    engine.undo()
    expect(engine.doc.nodes[textNodeId]).toBeDefined()
    expect(engine.doc.slots[rootSlotId].children).toContain(textNodeId)
  })

  it('undoes a MoveNode', () => {
    const registry = testRegistry()
    const { doc, textNodeId, rootSlotId, containerSlotId } =
      createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    engine.dispatch({
      type: 'MoveNode',
      nodeId: textNodeId,
      targetSlotId: containerSlotId,
      index: 0,
    })

    expect(engine.doc.slots[containerSlotId].children).toContain(textNodeId)

    engine.undo()

    expect(engine.doc.slots[rootSlotId].children).toContain(textNodeId)
    expect(engine.doc.slots[containerSlotId].children).not.toContain(textNodeId)
  })

  it('undoes UpdateNodeProps', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const originalProps = engine.doc.nodes[textNodeId].props

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: textNodeId,
      props: { content: { type: 'doc', content: [] } },
    })

    engine.undo()

    expect(engine.doc.nodes[textNodeId].props).toEqual(originalProps)
  })

  it('clears redo stack on new dispatch', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    const { node: n1, slots: s1 } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node: n1, slots: s1, targetSlotId: rootSlotId, index: -1 })
    engine.undo()
    expect(engine.canRedo).toBe(true)

    // New action clears redo
    const { node: n2, slots: s2 } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node: n2, slots: s2, targetSlotId: rootSlotId, index: -1 })
    expect(engine.canRedo).toBe(false)
  })

  it('reports canUndo/canRedo correctly', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    expect(engine.canUndo).toBe(false)
    expect(engine.canRedo).toBe(false)

    const rootSlotId = doc.nodes[doc.root].slots[0]
    const { node, slots } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId: rootSlotId, index: -1 })

    expect(engine.canUndo).toBe(true)
    expect(engine.canRedo).toBe(false)

    engine.undo()

    expect(engine.canUndo).toBe(false)
    expect(engine.canRedo).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// Subscription
// ---------------------------------------------------------------------------

describe('Subscription', () => {
  it('notifies listeners on dispatch', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    let notified = false
    engine.subscribe(() => {
      notified = true
    })

    const { node, slots } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId: rootSlotId, index: -1 })

    expect(notified).toBe(true)
  })

  it('does not notify on failed dispatch', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    let notified = false
    engine.subscribe(() => {
      notified = true
    })

    engine.dispatch({ type: 'RemoveNode', nodeId: nodeId('nonexistent') })

    expect(notified).toBe(false)
  })

  it('unsubscribe stops notifications', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    let count = 0
    const unsub = engine.subscribe(() => { count++ })

    const { node: n1, slots: s1 } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node: n1, slots: s1, targetSlotId: rootSlotId, index: -1 })
    expect(count).toBe(1)

    unsub()

    const { node: n2, slots: s2 } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node: n2, slots: s2, targetSlotId: rootSlotId, index: -1 })
    expect(count).toBe(1)
  })
})

// ---------------------------------------------------------------------------
// Selection
// ---------------------------------------------------------------------------

describe('Selection', () => {
  it('selects and deselects nodes', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    expect(engine.selectedNodeId).toBeNull()

    engine.selectNode(textNodeId)
    expect(engine.selectedNodeId).toBe(textNodeId)

    engine.selectNode(null)
    expect(engine.selectedNodeId).toBeNull()
  })

  it('notifies selection listeners', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const selections: (NodeId | null)[] = []
    engine.onSelectionChange(id => selections.push(id))

    engine.selectNode(textNodeId)
    engine.selectNode(null)

    expect(selections).toEqual([textNodeId, null])
  })

  it('does not fire on same selection', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    let count = 0
    engine.onSelectionChange(() => { count++ })

    engine.selectNode(textNodeId)
    engine.selectNode(textNodeId)

    expect(count).toBe(1)
  })
})

// ---------------------------------------------------------------------------
// replaceDocument
// ---------------------------------------------------------------------------

describe('replaceDocument', () => {
  it('replaces the document and clears undo stack', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    const { node, slots } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId: rootSlotId, index: -1 })
    expect(engine.canUndo).toBe(true)

    const newDoc = createTestDocument()
    engine.replaceDocument(newDoc)

    expect(engine.canUndo).toBe(false)
    expect(engine.selectedNodeId).toBeNull()
    expect(Object.keys(engine.doc.nodes)).toHaveLength(1) // Just root
  })
})

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

describe('ComponentRegistry', () => {
  it('registers and retrieves component definitions', () => {
    const registry = createDefaultRegistry()

    expect(registry.has('text')).toBe(true)
    expect(registry.has('container')).toBe(true)
    expect(registry.has('columns')).toBe(true)
    expect(registry.has('table')).toBe(true)
    expect(registry.has('conditional')).toBe(true)
    expect(registry.has('loop')).toBe(true)
    expect(registry.has('pagebreak')).toBe(true)
    expect(registry.has('pageheader')).toBe(true)
    expect(registry.has('pagefooter')).toBe(true)
    expect(registry.has('root')).toBe(true)
  })

  it('canContain respects allowlist', () => {
    const registry = new ComponentRegistry()
    registry.register({
      type: 'parent',
      label: 'Parent',
      category: 'layout',
      slots: [{ name: 'children' }],
      allowedChildren: { mode: 'allowlist', types: ['text'] },
      stylePolicy: { mode: 'all' },
      inspector: [],
    })

    expect(registry.canContain('parent', 'text')).toBe(true)
    expect(registry.canContain('parent', 'container')).toBe(false)
  })

  it('canContain respects denylist', () => {
    const registry = new ComponentRegistry()
    registry.register({
      type: 'parent',
      label: 'Parent',
      category: 'layout',
      slots: [{ name: 'children' }],
      allowedChildren: { mode: 'denylist', types: ['pagebreak'] },
      stylePolicy: { mode: 'all' },
      inspector: [],
    })

    expect(registry.canContain('parent', 'text')).toBe(true)
    expect(registry.canContain('parent', 'pagebreak')).toBe(false)
  })

  it('canContain returns false for none mode', () => {
    const registry = createDefaultRegistry()
    expect(registry.canContain('text', 'text')).toBe(false) // text has mode: none
  })

  it('createNode produces node + slots', () => {
    const registry = createDefaultRegistry()
    const { node, slots } = registry.createNode('container')

    expect(node.type).toBe('container')
    expect(node.id).toBeTruthy()
    expect(node.slots).toHaveLength(1)
    expect(slots).toHaveLength(1)
    expect(slots[0].nodeId).toBe(node.id)
    expect(slots[0].name).toBe('children')
  })

  it('createNode skips dynamic slots', () => {
    const registry = createDefaultRegistry()
    const { node, slots } = registry.createNode('columns')

    // columns has only a dynamic slot template, so no static slots are created
    expect(slots).toHaveLength(0)
    expect(node.slots).toHaveLength(0)
  })

  it('throws for unknown type', () => {
    const registry = createDefaultRegistry()
    expect(() => registry.createNode('nonexistent')).toThrow('Unknown component type')
  })
})

// ---------------------------------------------------------------------------
// Deep freeze (immutability)
// ---------------------------------------------------------------------------

describe('Immutability', () => {
  it('document state is frozen', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    expect(() => {
      ;(engine.doc as Record<string, unknown>).root = 'hacked' as NodeId
    }).toThrow()
  })

  it('modifying input doc does not affect engine state', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    // Mutate the original doc
    doc.root = 'hacked' as NodeId

    // Engine should be unaffected (structuredClone in constructor)
    expect(engine.doc.root).not.toBe('hacked')
  })
})
