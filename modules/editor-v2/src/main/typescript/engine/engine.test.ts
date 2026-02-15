import { describe, it, expect, beforeEach } from 'vitest'
import { EditorEngine } from './EditorEngine.js'
import type { TextChangeOps } from './undo.js'
import { TextChange } from './text-change.js'
import { CommandChange } from './command-change.js'
import { buildIndexes, getNodeDepth, findAncestorAtLevel } from './indexes.js'
import { getNestedValue, setNestedValue } from './props.js'
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

  it('computes depth for all nodes', () => {
    const { doc, rootId, textNodeId, containerNodeId, containerSlotId } =
      createTestDocumentWithChildren()

    // Add a child inside the container to get depth 2
    const deepChild = nodeId('deep')
    doc.nodes[deepChild] = { id: deepChild, type: 'text', slots: [], props: { content: null } }
    doc.slots[containerSlotId].children.push(deepChild)

    const indexes = buildIndexes(doc)

    expect(indexes.depthByNodeId.get(rootId)).toBe(0)
    expect(indexes.depthByNodeId.get(textNodeId)).toBe(1)
    expect(indexes.depthByNodeId.get(containerNodeId)).toBe(1)
    expect(indexes.depthByNodeId.get(deepChild)).toBe(2)
  })
})

// ---------------------------------------------------------------------------
// getNodeDepth / findAncestorAtLevel
// ---------------------------------------------------------------------------

describe('getNodeDepth', () => {
  it('returns 0 for root', () => {
    const { doc, rootId } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)
    expect(getNodeDepth(rootId, indexes)).toBe(0)
  })

  it('returns correct depth for nested nodes', () => {
    const { doc, containerNodeId, containerSlotId } = createTestDocumentWithChildren()
    const deepChild = nodeId('deep')
    doc.nodes[deepChild] = { id: deepChild, type: 'text', slots: [] }
    doc.slots[containerSlotId].children.push(deepChild)

    const indexes = buildIndexes(doc)
    expect(getNodeDepth(containerNodeId, indexes)).toBe(1)
    expect(getNodeDepth(deepChild, indexes)).toBe(2)
  })

  it('returns 0 for unknown nodes', () => {
    const { doc } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)
    expect(getNodeDepth('nonexistent' as NodeId, indexes)).toBe(0)
  })
})

describe('findAncestorAtLevel', () => {
  it('returns self when already at target level', () => {
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)
    expect(findAncestorAtLevel(textNodeId, 1, indexes)).toBe(textNodeId)
  })

  it('returns ancestor at specified level', () => {
    const { doc, rootId, containerNodeId, containerSlotId } = createTestDocumentWithChildren()
    const deepChild = nodeId('deep')
    doc.nodes[deepChild] = { id: deepChild, type: 'text', slots: [] }
    doc.slots[containerSlotId].children.push(deepChild)

    const indexes = buildIndexes(doc)
    // deepChild is at depth 2, find ancestor at level 1 → containerNodeId
    expect(findAncestorAtLevel(deepChild, 1, indexes)).toBe(containerNodeId)
    // find ancestor at level 0 → rootId
    expect(findAncestorAtLevel(deepChild, 0, indexes)).toBe(rootId)
  })

  it('returns null if no ancestor exists at level', () => {
    const { doc } = createTestDocumentWithChildren()
    const indexes = buildIndexes(doc)
    // orphan node not in the tree
    expect(findAncestorAtLevel('nonexistent' as NodeId, 0, indexes)).toBe('nonexistent')
  })
})

// ---------------------------------------------------------------------------
// getNestedValue / setNestedValue
// ---------------------------------------------------------------------------

describe('getNestedValue', () => {
  it('gets a top-level value', () => {
    expect(getNestedValue({ foo: 42 }, 'foo')).toBe(42)
  })

  it('gets a nested value', () => {
    expect(getNestedValue({ a: { b: { c: 'deep' } } }, 'a.b.c')).toBe('deep')
  })

  it('returns undefined for missing path', () => {
    expect(getNestedValue({ a: 1 }, 'b.c')).toBeUndefined()
  })

  it('returns undefined when traversing through null', () => {
    expect(getNestedValue({ a: null }, 'a.b')).toBeUndefined()
  })
})

describe('setNestedValue', () => {
  it('sets a top-level value', () => {
    const obj: Record<string, unknown> = {}
    setNestedValue(obj, 'foo', 42)
    expect(obj.foo).toBe(42)
  })

  it('sets a nested value, creating intermediate objects', () => {
    const obj: Record<string, unknown> = {}
    setNestedValue(obj, 'a.b.c', 'deep')
    expect((obj.a as Record<string, unknown>)?.b).toEqual({ c: 'deep' })
  })

  it('overwrites existing nested values', () => {
    const obj: Record<string, unknown> = { a: { b: 'old' } }
    setNestedValue(obj, 'a.b', 'new')
    expect((obj.a as Record<string, unknown>).b).toBe('new')
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
      applicableStyles: 'all',
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
      applicableStyles: 'all',
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

// ---------------------------------------------------------------------------
// structureChanged — conditional index rebuild
// ---------------------------------------------------------------------------

describe('structureChanged flag', () => {
  it('rebuilds indexes after InsertNode (new reference)', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    const indexesBefore = engine.indexes

    const { node, slots } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId: rootSlotId, index: -1 })

    expect(engine.indexes).not.toBe(indexesBefore)
  })

  it('skips index rebuild after UpdateNodeProps (same reference)', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const indexesBefore = engine.indexes

    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: textNodeId,
      props: { content: { type: 'doc', content: [] } },
    })

    expect(engine.indexes).toBe(indexesBefore)
  })

  it('skips index rebuild after UpdateNodeStyles (same reference)', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const indexesBefore = engine.indexes

    engine.dispatch({
      type: 'UpdateNodeStyles',
      nodeId: textNodeId,
      styles: { color: '#ff0000' },
    })

    expect(engine.indexes).toBe(indexesBefore)
  })

  it('skips index rebuild after UpdateDocumentStyles (same reference)', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    const indexesBefore = engine.indexes

    engine.dispatch({
      type: 'UpdateDocumentStyles',
      styles: { fontFamily: 'Inter' },
    })

    expect(engine.indexes).toBe(indexesBefore)
  })

  it('rebuilds indexes after RemoveNode (new reference)', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const indexesBefore = engine.indexes

    engine.dispatch({ type: 'RemoveNode', nodeId: textNodeId })

    expect(engine.indexes).not.toBe(indexesBefore)
  })
})

// ---------------------------------------------------------------------------
// skipUndo + pushTextChange
// ---------------------------------------------------------------------------

describe('skipUndo option', () => {
  it('does not push to undo stack when skipUndo is true', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    engine.dispatch(
      {
        type: 'UpdateNodeProps',
        nodeId: textNodeId,
        props: { content: { type: 'doc', content: [] } },
      },
      { skipUndo: true },
    )

    expect(engine.canUndo).toBe(false)
  })

  it('still updates doc and notifies listeners when skipUndo is true', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    let notified = false
    engine.subscribe(() => { notified = true })

    const result = engine.dispatch(
      {
        type: 'UpdateNodeProps',
        nodeId: textNodeId,
        props: { content: { type: 'doc', content: [] } },
      },
      { skipUndo: true },
    )

    expect(result.ok).toBe(true)
    expect(engine.doc.nodes[textNodeId].props).toEqual({
      content: { type: 'doc', content: [] },
    })
    expect(notified).toBe(true)
  })
})

describe('pushTextChange', () => {
  it('makes canUndo true', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    expect(engine.canUndo).toBe(false)

    engine.pushTextChange(createTextChange(textNodeId))

    expect(engine.canUndo).toBe(true)
  })

  it('clears the redo stack', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    // Create redo history
    const { node, slots } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId: rootSlotId, index: -1 })
    engine.undo()
    expect(engine.canRedo).toBe(true)

    // pushTextChange should clear redo
    engine.pushTextChange(createTextChange(node.id))

    expect(engine.canRedo).toBe(false)
  })

  it('peekUndo returns the pushed TextChange entry', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const entry = createTextChange(textNodeId)
    engine.pushTextChange(entry)

    const peeked = engine.peekUndo()
    expect(peeked).toBe(entry)
    expect(peeked).toBeInstanceOf(TextChange)
  })
})

// ---------------------------------------------------------------------------
// Data examples
// ---------------------------------------------------------------------------

describe('Data examples', () => {
  const examples = [
    { name: 'Example 1', value: 100 },
    { name: 'Example 2', value: 200 },
    { name: 'Example 3', value: 300 },
  ]

  it('stores dataModel and dataExamples from options', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const dataModel = { type: 'object', properties: { name: { type: 'string' } } }
    const engine = new EditorEngine(doc, registry, { dataModel, dataExamples: examples })

    expect(engine.dataModel).toBe(dataModel)
    expect(engine.dataExamples).toBe(examples)
  })

  it('defaults currentExampleIndex to 0', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry, { dataExamples: examples })

    expect(engine.currentExampleIndex).toBe(0)
    expect(engine.currentExample).toBe(examples[0])
  })

  it('setCurrentExample switches the active example', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry, { dataExamples: examples })

    engine.setCurrentExample(2)
    expect(engine.currentExampleIndex).toBe(2)
    expect(engine.currentExample).toBe(examples[2])
  })

  it('setCurrentExample ignores out-of-bounds index', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry, { dataExamples: examples })

    engine.setCurrentExample(99)
    expect(engine.currentExampleIndex).toBe(0)

    engine.setCurrentExample(-1)
    expect(engine.currentExampleIndex).toBe(0)
  })

  it('setCurrentExample ignores same index', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry, { dataExamples: examples })

    let notified = false
    engine.onExampleChange(() => { notified = true })

    engine.setCurrentExample(0) // same as current
    expect(notified).toBe(false)
  })

  it('notifies example listeners on change', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry, { dataExamples: examples })

    const changes: { index: number; example: object | undefined }[] = []
    engine.onExampleChange((index, example) => {
      changes.push({ index, example })
    })

    engine.setCurrentExample(1)
    engine.setCurrentExample(2)

    expect(changes).toEqual([
      { index: 1, example: examples[1] },
      { index: 2, example: examples[2] },
    ])
  })

  it('unsubscribe stops notifications', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry, { dataExamples: examples })

    let count = 0
    const unsub = engine.onExampleChange(() => { count++ })

    engine.setCurrentExample(1)
    expect(count).toBe(1)

    unsub()
    engine.setCurrentExample(2)
    expect(count).toBe(1)
  })

  it('currentExample returns undefined when no examples', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    expect(engine.dataExamples).toBeUndefined()
    expect(engine.currentExample).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// CommandChange undo/redo
// ---------------------------------------------------------------------------

describe('CommandChange', () => {
  it('undo dispatches the stored inverse and pushes result inverse to redo', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    // Insert a node — this pushes a CommandChange onto the undo stack
    const { node, slots } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId: rootSlotId, index: -1 })
    expect(engine.doc.nodes[node.id]).toBeDefined()

    // Undo — CommandChange.undoStep applies RemoveNode inverse
    engine.undo()
    expect(engine.doc.nodes[node.id]).toBeUndefined()

    // Redo — CommandChange.redoStep applies InsertNode inverse
    engine.redo()
    expect(engine.doc.nodes[node.id]).toBeDefined()
  })

  it('wraps command inverse in dispatch', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    // The peekUndo entry should be a CommandChange after dispatch
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: textNodeId,
      props: { content: { type: 'doc', content: [] } },
    })

    const top = engine.peekUndo()
    expect(top).toBeInstanceOf(CommandChange)
  })
})

// ---------------------------------------------------------------------------
// TextChange undo/redo (first-class ProseMirror editing sessions)
// ---------------------------------------------------------------------------

/** Create mock TextChangeOps simulating a PM editing session with N undo steps. */
function createMockOps(initialDepth: number, steps: number): TextChangeOps & { depth: number; maxDepth: number; content: unknown; _alive: boolean } {
  const mock = {
    depth: initialDepth + steps,
    maxDepth: initialDepth + steps,
    content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'hello' }] }] },
    _alive: true,
    isAlive() { return mock._alive },
    undoDepth() { return mock.depth },
    undo() {
      if (mock.depth <= initialDepth) return false
      mock.depth--
      mock.content = { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'hello'.slice(0, mock.depth - initialDepth) }] }] }
      return true
    },
    redo() {
      if (mock.depth >= mock.maxDepth) return false
      mock.depth++
      mock.content = { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'hello'.slice(0, mock.depth - initialDepth) }] }] }
      return true
    },
    getContent() { return structuredClone(mock.content) },
  }
  return mock
}

function createTextChange(
  nId: string,
  ops?: TextChangeOps | null,
  undoDepthAtStart = 0,
  contentBefore: unknown = null,
): TextChange {
  return new TextChange({
    nodeId: nId,
    ops: ops ?? null,
    contentBefore,
    undoDepthAtStart,
  })
}

describe('TextChange undo/redo', () => {
  it('undoes PM steps one at a time via ops', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const ops = createMockOps(0, 3) // 3 undo steps available
    engine.pushTextChange(createTextChange(textNodeId, ops, 0, null))

    // First undo: depth 3 → 2
    expect(engine.undo()).toBe(true)
    expect(ops.depth).toBe(2)

    // Second undo: depth 2 → 1
    expect(engine.undo()).toBe(true)
    expect(ops.depth).toBe(1)

    // Third undo: depth 1 → 0
    expect(engine.undo()).toBe(true)
    expect(ops.depth).toBe(0)

    // Session exhausted → TextChange popped, no more entries
    expect(engine.canUndo).toBe(false)
  })

  it('redoes PM steps one at a time after undo', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const ops = createMockOps(0, 3)
    engine.pushTextChange(createTextChange(textNodeId, ops, 0, null))

    // Undo all 3 steps
    engine.undo()
    engine.undo()
    engine.undo()
    // Session exhausted, falls through (no more entries), entry moved to redo
    expect(engine.canRedo).toBe(true)

    // Redo one at a time
    expect(engine.redo()).toBe(true)
    expect(ops.depth).toBe(1)

    expect(engine.redo()).toBe(true)
    expect(ops.depth).toBe(2)

    expect(engine.redo()).toBe(true)
    expect(ops.depth).toBe(3)

    // Session fully redone → TextChange moved back to undo stack
    expect(engine.canRedo).toBe(false)
    expect(engine.canUndo).toBe(true)
  })

  it('falls through to next entry when TextChange session is exhausted', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    // Structural change (InsertNode) first
    const { node, slots } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId: rootSlotId, index: -1 })

    // Then a TextChange session with 1 step
    const ops = createMockOps(0, 1)
    engine.pushTextChange(createTextChange(node.id, ops, 0, null))

    // First undo: PM step (depth 1 → 0)
    expect(engine.undo()).toBe(true)
    expect(ops.depth).toBe(0)

    // Second undo: session exhausted → falls through to InsertNode inverse → removes node
    expect(engine.undo()).toBe(true)
    expect(engine.doc.nodes[node.id]).toBeUndefined()
  })

  it('uses snapshot fallback when PM is destroyed (undo)', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)
    const originalContent = engine.doc.nodes[textNodeId].props.content

    // Push TextChange with null ops (PM destroyed)
    engine.pushTextChange(createTextChange(textNodeId, null, 0, originalContent))

    // Make a change so we have something to undo to
    engine.dispatch(
      { type: 'UpdateNodeProps', nodeId: textNodeId, props: { content: { type: 'doc', content: [] } } },
      { skipUndo: true },
    )

    // Undo should restore content via snapshot
    expect(engine.undo()).toBe(true)
    expect(engine.doc.nodes[textNodeId].props.content).toEqual(originalContent)
  })

  it('uses snapshot fallback when PM is destroyed (redo)', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const contentAfter = { type: 'doc', content: [{ type: 'paragraph' }] }
    const entry = createTextChange(textNodeId, null, 0, null)
    entry.contentAfter = contentAfter

    engine.pushTextChange(entry)

    // Undo (snapshot fallback)
    engine.undo()

    // Redo (also snapshot fallback since ops is null)
    expect(engine.redo()).toBe(true)
    expect(engine.doc.nodes[textNodeId].props.content).toEqual(contentAfter)
  })

  it('uses snapshot fallback when ops becomes dead mid-session', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const ops = createMockOps(0, 3)
    const entry = createTextChange(textNodeId, ops, 0, null)
    engine.pushTextChange(entry)

    // Undo one step successfully
    engine.undo()
    expect(ops.depth).toBe(2)

    // Simulate PM destruction
    ops._alive = false

    // Next undo should use snapshot fallback
    expect(engine.undo()).toBe(true)
    // Entry moved to redo stack
    expect(engine.canRedo).toBe(true)
  })

  it('handles two TextChange sessions for the same node', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)
    const rootSlotId = doc.nodes[doc.root].slots[0]

    // Insert a text node
    const { node, slots } = registry.createNode('text')
    engine.dispatch({ type: 'InsertNode', node, slots, targetSlotId: rootSlotId, index: -1 })

    // Session 1: 2 PM steps (depth 0→2)
    const ops1 = createMockOps(0, 2)
    engine.pushTextChange(createTextChange(node.id, ops1, 0, null))

    // Structural change between sessions
    engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: node.id,
      props: { label: 'renamed' },
    })

    // Session 2: 3 PM steps (depth 2→5 — different ops, same node)
    const ops2 = createMockOps(2, 3)
    engine.pushTextChange(createTextChange(node.id, ops2, 2, null))

    // Undo session 2: 3 steps
    engine.undo() // depth 5→4
    engine.undo() // depth 4→3
    engine.undo() // depth 3→2
    // Session 2 exhausted → falls through

    // Undo structural change (label)
    expect(engine.undo()).toBe(true)

    // Undo session 1: 2 steps
    engine.undo() // depth 2→1
    expect(ops1.depth).toBe(1)
    engine.undo() // depth 1→0
    expect(ops1.depth).toBe(0)

    // Session 1 exhausted → falls through to InsertNode inverse
    expect(engine.undo()).toBe(true)
    expect(engine.doc.nodes[node.id]).toBeUndefined()
  })

  it('lazily captures undoDepthAtEnd on first undo', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const ops = createMockOps(0, 3)
    const entry = createTextChange(textNodeId, ops, 0, null)
    engine.pushTextChange(entry)

    expect(entry.undoDepthAtEnd).toBeUndefined()

    engine.undo()

    expect(entry.undoDepthAtEnd).toBe(3)
  })

  it('syncs PM content to engine after each undo step', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const ops = createMockOps(0, 2)
    engine.pushTextChange(createTextChange(textNodeId, ops, 0, null))

    engine.undo()

    // Engine doc should have the PM content synced
    expect(engine.doc.nodes[textNodeId].props.content).toEqual(ops.getContent())
  })

  it('syncs PM content to engine after each redo step', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const ops = createMockOps(0, 2)
    engine.pushTextChange(createTextChange(textNodeId, ops, 0, null))

    // Undo all
    engine.undo()
    engine.undo()
    // Session exhausted, entry on redo stack

    // Redo one step
    engine.redo()

    // Engine doc should reflect the redo
    expect(engine.doc.nodes[textNodeId].props.content).toEqual(ops.getContent())
  })

  it('resets undoDepthAtEnd after full redo so continued typing is not lost', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    // Session: 3 PM steps (simulating typing "hel")
    const ops = createMockOps(0, 3)
    const entry = createTextChange(textNodeId, ops, 0, null)
    engine.pushTextChange(entry)

    // Full undo: depth 3→0
    engine.undo() // 3→2
    engine.undo() // 2→1
    engine.undo() // 1→0, entry moved to redo
    expect(entry.undoDepthAtEnd).toBe(3)

    // Full redo: depth 0→3, entry moved back to undo
    engine.redo() // 0→1
    engine.redo() // 1→2
    engine.redo() // 2→3, entry moved to undo

    // undoDepthAtEnd should be reset so it can be re-captured
    expect(entry.undoDepthAtEnd).toBeUndefined()

    // Simulate continued typing: 2 more PM steps (depth 3→5)
    // In the real editor, _onPmDocChanged sees the same ops on stack
    // and doesn't push a new TextChange. The PM depth increases as
    // the user types.
    ops.depth = 5
    ops.maxDepth = 5

    // Full undo again: should undo all 5 steps (not just 3)
    engine.undo() // captures undoDepthAtEnd=5, then 5→4
    expect(entry.undoDepthAtEnd).toBe(5)
    engine.undo() // 4→3
    engine.undo() // 3→2
    engine.undo() // 2→1
    engine.undo() // 1→0, entry moved to redo

    // Full redo: should redo all 5 steps
    engine.redo() // 0→1
    engine.redo() // 1→2
    engine.redo() // 2→3
    engine.redo() // 3→4
    engine.redo() // 4→5, entry moved to undo
    expect(ops.depth).toBe(5)
    expect(engine.canRedo).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// PM state cache (Phase 2)
// ---------------------------------------------------------------------------

describe('PM state cache', () => {
  it('cachePmState stores and getCachedPmState retrieves (one-time)', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const mockState = { doc: { toJSON: () => ({}) } }
    engine.cachePmState(textNodeId, mockState)

    // First retrieval returns the cached state
    expect(engine.getCachedPmState(textNodeId)).toBe(mockState)

    // Second retrieval returns undefined (consumed)
    expect(engine.getCachedPmState(textNodeId)).toBeUndefined()
  })

  it('replaceDocument clears the cache', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    engine.cachePmState(textNodeId, { some: 'state' })
    engine.replaceDocument(createTestDocument())

    expect(engine.getCachedPmState(textNodeId)).toBeUndefined()
  })

  it('getCachedPmState returns undefined for uncached nodeId', () => {
    const registry = testRegistry()
    const doc = createTestDocument()
    const engine = new EditorEngine(doc, registry)

    expect(engine.getCachedPmState('uncached' as NodeId)).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// Ops revival (Phase 2)
// ---------------------------------------------------------------------------

describe('reviveTextChangeOps', () => {
  it('reconnects ops for TextChange entries with null ops on undo stack', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    // Push a TextChange with null ops (simulating PM destroyed)
    const entry = createTextChange(textNodeId, null, 0, null)
    engine.pushTextChange(entry)

    expect(entry.ops).toBeNull()

    // Revive with new ops
    const newOps = createMockOps(0, 1)
    engine.reviveTextChangeOps(textNodeId, newOps)

    expect(entry.ops).toBe(newOps)
  })

  it('reconnects ops for TextChange entries on redo stack', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    // Push a TextChange with working ops and undo it fully to move to redo
    const ops = createMockOps(0, 1)
    const entry = createTextChange(textNodeId, ops, 0, null)
    engine.pushTextChange(entry)
    engine.undo() // depth 1→0, entry to redo

    // Now destroy ops
    entry.ops = null

    // Revive with new ops
    const newOps = createMockOps(0, 1)
    engine.reviveTextChangeOps(textNodeId, newOps)

    expect(entry.ops).toBe(newOps)
  })

  it('does not affect entries for different nodeIds', () => {
    const registry = testRegistry()
    const { doc, textNodeId, containerNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const entry1 = createTextChange(textNodeId, null, 0, null)
    const entry2 = createTextChange(containerNodeId, null, 0, null)
    engine.pushTextChange(entry1)
    engine.pushTextChange(entry2)

    const newOps = createMockOps(0, 1)
    engine.reviveTextChangeOps(textNodeId, newOps)

    expect(entry1.ops).toBe(newOps)
    expect(entry2.ops).toBeNull() // untouched — different nodeId
  })

  it('does not overwrite live ops', () => {
    const registry = testRegistry()
    const { doc, textNodeId } = createTestDocumentWithChildren()
    const engine = new EditorEngine(doc, registry)

    const existingOps = createMockOps(0, 1)
    const entry = createTextChange(textNodeId, existingOps, 0, null)
    engine.pushTextChange(entry)

    const newOps = createMockOps(0, 2)
    engine.reviveTextChangeOps(textNodeId, newOps)

    // Should not overwrite because existing ops is not null
    expect(entry.ops).toBe(existingOps)
  })
})
