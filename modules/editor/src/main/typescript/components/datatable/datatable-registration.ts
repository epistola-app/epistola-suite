/**
 * Data Table component definition for the component registry.
 *
 * A datatable iterates over an array expression and renders one row per item.
 * Columns are child nodes (datatable-column) that define headers, widths, and
 * per-row template content via their "body" slot.
 *
 * Uses the createSubtree hook to atomically create the datatable node,
 * its "columns" slot, and the initial column child nodes with their body slots.
 */

import type { NodeId, SlotId, Slot, Node } from '../../types/index.js'
import type { ComponentDefinition } from '../../engine/registry.js'
import type { EditorEngine } from '../../engine/EditorEngine.js'
import { openDatatableDialog } from './datatable-dialog.js'
import { nanoid } from 'nanoid'
import { html, nothing } from 'lit'
import { styleMap } from 'lit/directives/style-map.js'
import { icon } from '../../ui/icons.js'

/** Layout style properties available on datatable nodes. */
const LAYOUT_STYLES = [
  'padding', 'margin',
  'backgroundColor',
  'borderWidth', 'borderStyle', 'borderColor', 'borderRadius',
]

export function createDatatableDefinition(): ComponentDefinition {
  return {
    type: 'datatable',
    label: 'Data Table',
    icon: 'sheet',
    category: 'logic',
    slots: [{ name: 'columns' }],
    allowedChildren: { mode: 'allowlist', types: ['datatable-column'] },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
      { key: 'expression.raw', label: 'Data Expression', type: 'expression' },
      { key: 'itemAlias', label: 'Item Alias', type: 'text', defaultValue: 'item' },
      { key: 'indexAlias', label: 'Index Alias', type: 'text' },
      {
        key: 'borderStyle',
        label: 'Border Style',
        type: 'select',
        options: [
          { label: 'None', value: 'none' },
          { label: 'All', value: 'all' },
          { label: 'Horizontal', value: 'horizontal' },
          { label: 'Vertical', value: 'vertical' },
        ],
        defaultValue: 'all',
      },
      { key: 'headerEnabled', label: 'Show Header', type: 'boolean', defaultValue: true },
    ],
    defaultProps: {
      expression: { raw: '', language: 'jsonata' },
      itemAlias: 'item',
      indexAlias: undefined,
      borderStyle: 'all',
      headerEnabled: true,
    },

    // ----- Subtree creation -----
    createSubtree: (nodeId: NodeId, props?: Record<string, unknown>) => {
      const columnCount = (props?._columnCount as number | undefined) ?? 3

      const columnsSlotId = nanoid() as SlotId
      const columnNodeIds: NodeId[] = []
      const extraNodes: Node[] = []
      const extraSlots: Slot[] = []

      for (let i = 0; i < columnCount; i++) {
        const colNodeId = nanoid() as NodeId
        const bodySlotId = nanoid() as SlotId

        columnNodeIds.push(colNodeId)

        // Column body slot
        extraSlots.push({
          id: bodySlotId,
          nodeId: colNodeId,
          name: 'body',
          children: [],
        })

        // Column node
        extraNodes.push({
          id: colNodeId,
          type: 'datatable-column',
          slots: [bodySlotId],
          props: {
            header: `Column ${i + 1}`,
            width: Math.round(100 / columnCount),
          },
        })
      }

      // The datatable's "columns" slot with the column node IDs as children
      const columnsSlot: Slot = {
        id: columnsSlotId,
        nodeId,
        name: 'columns',
        children: columnNodeIds,
      }

      return {
        slots: [columnsSlot],
        extraNodes,
        extraSlots,
      }
    },

    // ----- Palette pre-insert hook -----
    onBeforeInsert: async () => {
      const result = await openDatatableDialog()
      if (result.cancelled) return null
      return { _columnCount: result.columns }
    },

    // ----- Canvas hook -----
    renderCanvas: ({ node, doc, engine: eng, renderSlot, selectedNodeId }) => {
      const engine = eng as EditorEngine
      const props = node.props ?? {}
      const borderStyle = (props.borderStyle as string) ?? 'all'
      const headerEnabled = (props.headerEnabled as boolean) ?? true
      const expression = props.expression as { raw?: string } | undefined
      const itemAlias = (props.itemAlias as string) ?? 'item'

      // Find the "columns" slot
      const columnsSlotId = node.slots[0]
      const columnsSlot = doc.slots[columnsSlotId]
      if (!columnsSlot) return html`<div class="datatable-canvas">No columns slot</div>`

      // Gather column nodes
      const columnNodes = columnsSlot.children
        .map(id => doc.nodes[id])
        .filter((n): n is Node => n != null)

      if (columnNodes.length === 0) {
        return html`<div class="datatable-canvas">
          <div class="datatable-label">
            <span class="datatable-label-icon">${icon('sheet', 12)}</span>
            Data Table — no columns
          </div>
        </div>`
      }

      // Compute grid template columns from column widths
      const widths = columnNodes.map(cn => (cn.props?.width as number) ?? 33)
      const total = widths.reduce((a, b) => a + b, 0) || columnNodes.length
      const gridTemplateColumns = widths
        .map(w => `${((w / total) * 100).toFixed(2)}%`)
        .join(' ')

      const expressionLabel = expression?.raw
        ? `${expression.raw} as ${itemAlias}`
        : `(no expression)`

      const handleColumnClick = (e: MouseEvent, columnNodeId: NodeId) => {
        e.stopPropagation()
        engine.selectNode(columnNodeId)
      }

      // Header row
      const headerCells = headerEnabled
        ? columnNodes.map(cn => {
          const header = (cn.props?.header as string) ?? ''
          return html`<div class="datatable-header-cell">${header || '\u00A0'}</div>`
        })
        : nothing

      // Body row (template slots)
      const bodyCells = columnNodes.map(cn => {
        const bodySlotId = cn.slots[0]
        const isSelected = selectedNodeId === cn.id
        return html`
          <div
            class="datatable-body-cell ${isSelected ? 'column-selected' : ''}"
            @click=${(e: MouseEvent) => handleColumnClick(e, cn.id as NodeId)}
          >
            ${bodySlotId ? renderSlot(bodySlotId as SlotId) : nothing}
          </div>
        `
      })

      return html`
        <div class="datatable-canvas">
          <div class="datatable-label">
            <span class="datatable-label-icon">${icon('sheet', 12)}</span>
            Data Table — ${expressionLabel}
          </div>
          <div
            class="datatable-grid border-${borderStyle}"
            style=${styleMap({ 'grid-template-columns': gridTemplateColumns })}
          >
            ${headerCells}
            ${bodyCells}
          </div>
        </div>
      `
    },

    // ----- Inspector hook -----
    renderInspector: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine
      const doc = engine.doc

      // Find column nodes
      const columnsSlotId = node.slots[0]
      const columnsSlot = doc.slots[columnsSlotId]
      const columnCount = columnsSlot?.children.length ?? 0

      const handleAddColumn = () => {
        if (!columnsSlot) return
        const colNodeId = nanoid() as NodeId
        const bodySlotId = nanoid() as SlotId

        const colNode: Node = {
          id: colNodeId,
          type: 'datatable-column',
          slots: [bodySlotId],
          props: {
            header: `Column ${columnCount + 1}`,
            width: Math.round(100 / (columnCount + 1)),
          },
        }

        const bodySlot: Slot = {
          id: bodySlotId,
          nodeId: colNodeId,
          name: 'body',
          children: [],
        }

        engine.dispatch({
          type: 'InsertNode',
          node: colNode,
          slots: [bodySlot],
          targetSlotId: columnsSlotId as SlotId,
          index: -1,
        })
      }

      const handleRemoveColumn = () => {
        if (!columnsSlot || columnCount <= 1) return
        const lastColId = columnsSlot.children[columnsSlot.children.length - 1]
        engine.dispatch({
          type: 'RemoveNode',
          nodeId: lastColId as NodeId,
        })
      }

      return html`
        <div class="inspector-section">
          <div class="inspector-section-label">Columns</div>
          <div class="inspector-field">
            <label class="inspector-field-label">Column Count</label>
            <div class="inspector-column-count">
              <button
                class="inspector-column-btn"
                ?disabled=${columnCount <= 1}
                @click=${handleRemoveColumn}
              >&minus;</button>
              <span class="inspector-column-count-value">${columnCount}</span>
              <button
                class="inspector-column-btn"
                ?disabled=${columnCount >= 20}
                @click=${handleAddColumn}
              >+</button>
            </div>
          </div>
        </div>
      `
    },
  }
}
