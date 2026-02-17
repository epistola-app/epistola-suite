/**
 * ComponentRegistry — defines what node types exist and their constraints.
 *
 * Used by: command validation, block palette, inspector, DnD.
 */

import type { NodeId, SlotId, Node, Slot } from '../types/index.js'
import { nanoid } from 'nanoid'
import { createTableDefinition } from '../components/table/table-registration.js'

// ---------------------------------------------------------------------------
// Component definition
// ---------------------------------------------------------------------------

export type ComponentCategory = 'content' | 'layout' | 'logic' | 'page'

export type AllowedChildren =
  | { mode: 'all' }
  | { mode: 'allowlist'; types: string[] }
  | { mode: 'denylist'; types: string[] }
  | { mode: 'none' }

export interface SlotTemplate {
  name: string
  dynamic?: boolean
}

export interface InspectorField {
  key: string
  label: string
  type: 'text' | 'number' | 'boolean' | 'select' | 'expression' | 'json' | 'color'
  options?: { label: string; value: unknown }[]
  defaultValue?: unknown
}

export interface ComponentDefinition {
  type: string
  label: string
  icon?: string
  category: ComponentCategory
  slots: SlotTemplate[]
  allowedChildren: AllowedChildren
  /**
   * Which style property keys this component supports.
   * - `'all'` = every property in the style registry
   * - `string[]` = explicit allowlist of property keys (empty = no styles)
   */
  applicableStyles: 'all' | string[]
  inspector: InspectorField[]
  defaultProps?: Record<string, unknown>
  /**
   * Optional hook to create initial slots for a node of this type.
   * Used for components (like columns, tables) whose slot count is derived
   * from props at creation time rather than from static slot templates.
   *
   * @param nodeId — the ID of the node being created
   * @param props — merged props (defaultProps overridden by any overrideProps)
   */
  createInitialSlots?: (nodeId: NodeId, props?: Record<string, unknown>) => Slot[]
}

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

export class ComponentRegistry {
  private _definitions: Map<string, ComponentDefinition> = new Map()

  register(def: ComponentDefinition): void {
    this._definitions.set(def.type, def)
  }

  get(type: string): ComponentDefinition | undefined {
    return this._definitions.get(type)
  }

  getOrThrow(type: string): ComponentDefinition {
    const def = this._definitions.get(type)
    if (!def) throw new Error(`Unknown component type: ${type}`)
    return def
  }

  has(type: string): boolean {
    return this._definitions.has(type)
  }

  all(): ComponentDefinition[] {
    return Array.from(this._definitions.values())
  }

  /** Get only components that can be inserted by the user (palette items). */
  insertable(): ComponentDefinition[] {
    return this.all()
  }

  /**
   * Check whether a node of `childType` can be placed in a slot owned by
   * a node of `parentType`.
   */
  canContain(parentType: string, childType: string): boolean {
    const def = this._definitions.get(parentType)
    if (!def) return false

    switch (def.allowedChildren.mode) {
      case 'all':
        return true
      case 'none':
        return false
      case 'allowlist':
        return def.allowedChildren.types.includes(childType)
      case 'denylist':
        return !def.allowedChildren.types.includes(childType)
    }
  }

  /**
   * Create a new node + its initial slots for a given component type.
   * Returns the node and slots ready to be inserted into the document.
   *
   * @param overrideProps — optional props to merge over defaultProps
   *   (e.g. table dialog can pass `{ rows: 4, columns: 3 }`)
   */
  createNode(type: string, overrideProps?: Record<string, unknown>): { node: Node; slots: Slot[] } {
    const def = this.getOrThrow(type)
    const nodeId = nanoid() as NodeId

    const mergedProps = overrideProps
      ? { ...(def.defaultProps ? structuredClone(def.defaultProps) : {}), ...overrideProps }
      : (def.defaultProps ? structuredClone(def.defaultProps) : undefined)

    // If the component defines a custom slot initializer, use it
    if (def.createInitialSlots) {
      const slots = def.createInitialSlots(nodeId, mergedProps)
      const slotIds = slots.map(s => s.id)
      const node: Node = {
        id: nodeId,
        type,
        slots: slotIds,
        props: mergedProps,
      }
      return { node, slots }
    }

    const slots: Slot[] = []
    const slotIds: SlotId[] = []

    for (const template of def.slots) {
      if (template.dynamic) continue // Dynamic slots are created on demand
      const slotId = nanoid() as SlotId
      slotIds.push(slotId)
      slots.push({
        id: slotId,
        nodeId,
        name: template.name,
        children: [],
      })
    }

    const node: Node = {
      id: nodeId,
      type,
      slots: slotIds,
      props: mergedProps,
    }

    return { node, slots }
  }
}

// ---------------------------------------------------------------------------
// Built-in component definitions
// ---------------------------------------------------------------------------

/** Layout-oriented style properties (spacing + background + borders, no typography). */
const LAYOUT_STYLES = [
  'padding', 'margin',
  'backgroundColor',
  'borderWidth', 'borderStyle', 'borderColor', 'borderRadius',
]

export function createDefaultRegistry(): ComponentRegistry {
  const registry = new ComponentRegistry()

  registry.register({
    type: 'root',
    label: 'Document Root',
    icon: 'file-text',
    category: 'layout',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'denylist', types: ['root'] },
    applicableStyles: [],
    inspector: [],
  })

  registry.register({
    type: 'text',
    label: 'Text',
    icon: 'type',
    category: 'content',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: 'all',
    inspector: [],
    defaultProps: { content: null },
  })

  registry.register({
    type: 'container',
    label: 'Container',
    icon: 'box',
    category: 'layout',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [],
  })

  registry.register({
    type: 'columns',
    label: 'Columns',
    icon: 'columns-2',
    category: 'layout',
    slots: [{ name: 'column-{i}', dynamic: true }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      { key: 'gap', label: 'Gap', type: 'number', defaultValue: 0 },
    ],
    defaultProps: { columnSizes: [1, 1], gap: 0 },
    createInitialSlots: (nodeId: NodeId) => {
      const sizes = [1, 1] // matches defaultProps.columnSizes
      return sizes.map((_, i) => ({
        id: nanoid() as SlotId,
        nodeId,
        name: `column-${i}`,
        children: [],
      }))
    },
  })

  registry.register(createTableDefinition())

  registry.register({
    type: 'conditional',
    label: 'Conditional',
    icon: 'git-branch',
    category: 'logic',
    slots: [{ name: 'body' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      { key: 'condition.raw', label: 'Condition', type: 'expression' },
      { key: 'inverse', label: 'Inverse (else)', type: 'boolean', defaultValue: false },
    ],
    defaultProps: {
      condition: { raw: '', language: 'jsonata' },
      inverse: false,
    },
  })

  registry.register({
    type: 'loop',
    label: 'Loop',
    icon: 'repeat',
    category: 'logic',
    slots: [{ name: 'body' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      { key: 'expression.raw', label: 'Expression', type: 'expression' },
      { key: 'itemAlias', label: 'Item Alias', type: 'text', defaultValue: 'item' },
      { key: 'indexAlias', label: 'Index Alias', type: 'text' },
    ],
    defaultProps: {
      expression: { raw: '', language: 'jsonata' },
      itemAlias: 'item',
      indexAlias: undefined,
    },
  })

  registry.register({
    type: 'pagebreak',
    label: 'Page Break',
    icon: 'minus',
    category: 'page',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: [],
    inspector: [],
  })

  registry.register({
    type: 'pageheader',
    label: 'Page Header',
    icon: 'panel-top',
    category: 'page',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [],
  })

  registry.register({
    type: 'pagefooter',
    label: 'Page Footer',
    icon: 'panel-bottom',
    category: 'page',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [],
  })

  return registry
}
