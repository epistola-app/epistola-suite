/**
 * Test helpers for creating minimal TemplateDocuments.
 */
import type { TemplateDocument, NodeId, SlotId } from '../types/index.js'
import { createDefaultRegistry, type ComponentRegistry } from './registry.js'

let counter = 0

export function nodeId(id?: string): NodeId {
  return (id ?? `n${++counter}`) as NodeId
}

export function slotId(id?: string): SlotId {
  return (id ?? `s${++counter}`) as SlotId
}

/**
 * Create a minimal valid TemplateDocument with a single root container.
 */
export function createTestDocument(overrides?: Partial<TemplateDocument>): TemplateDocument {
  const rootId = nodeId('root')
  const rootSlotId = slotId('root-children')

  return {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: {
        id: rootId,
        type: 'root',
        slots: [rootSlotId],
      },
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootId,
        name: 'children',
        children: [],
      },
    },
    themeRef: { type: 'inherit' },
    ...overrides,
  }
}

/**
 * Create a test document with some children pre-populated.
 * Returns the document plus the IDs for easy reference.
 */
export function createTestDocumentWithChildren(): {
  doc: TemplateDocument
  rootId: NodeId
  rootSlotId: SlotId
  textNodeId: NodeId
  containerNodeId: NodeId
  containerSlotId: SlotId
} {
  const rootId = nodeId('root')
  const rootSlotId = slotId('root-slot')
  const textNodeId = nodeId('text1')
  const containerNodeId = nodeId('container1')
  const containerSlotId = slotId('container-slot')

  const doc: TemplateDocument = {
    modelVersion: 1,
    root: rootId,
    nodes: {
      [rootId]: {
        id: rootId,
        type: 'root',
        slots: [rootSlotId],
      },
      [textNodeId]: {
        id: textNodeId,
        type: 'text',
        slots: [],
        props: { content: null },
      },
      [containerNodeId]: {
        id: containerNodeId,
        type: 'container',
        slots: [containerSlotId],
      },
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootId,
        name: 'children',
        children: [textNodeId, containerNodeId],
      },
      [containerSlotId]: {
        id: containerSlotId,
        nodeId: containerNodeId,
        name: 'children',
        children: [],
      },
    },
    themeRef: { type: 'inherit' },
  }

  return { doc, rootId, rootSlotId, textNodeId, containerNodeId, containerSlotId }
}

export function testRegistry(): ComponentRegistry {
  return createDefaultRegistry()
}

/** Reset the counter between tests. */
export function resetCounter(): void {
  counter = 0
}
