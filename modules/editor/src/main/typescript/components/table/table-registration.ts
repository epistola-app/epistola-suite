/**
 * Table component definition for the component registry.
 *
 * Exports a factory that creates the ComponentDefinition including all
 * extension hooks — keeping table-specific logic out of generic files.
 */

import type { NodeId, SlotId, Slot } from '../../types/index.js'
import type { ComponentDefinition } from '../../engine/registry.js'
import type { EditorEngine } from '../../engine/EditorEngine.js'
import { cellSlotName } from './table-utils.js'
import { applyTableCommand, type TableCommand } from './table-commands.js'
import { openTableDialog } from './table-dialog.js'
import './TableInspector.js'
import { nanoid } from 'nanoid'
import { html, nothing } from 'lit'
import { styleMap } from 'lit/directives/style-map.js'
import {
  findMergeAt,
  isCellCovered,
  type CellMerge,
  type CellSelection,
} from './table-utils.js'

/** Layout style properties available on table nodes. */
const LAYOUT_STYLES = [
  'padding', 'margin',
  'backgroundColor',
  'borderWidth', 'borderStyle', 'borderColor', 'borderRadius',
]

export const TABLE_DEFAULT_PROPS = {
  rows: 2,
  columns: 2,
  columnWidths: [50, 50],
  borderStyle: 'all',
  headerRows: 0,
  merges: [],
}

/** All table command type strings for registry routing. */
const TABLE_COMMAND_TYPES = [
  'AddTableRow', 'RemoveTableRow',
  'AddTableColumn', 'RemoveTableColumn',
  'MergeTableCells', 'UnmergeTableCells',
  'SetTableHeaderRows',
]

export function createTableDefinition(): ComponentDefinition {
  return {
    type: 'table',
    label: 'Table',
    icon: 'table',
    category: 'layout',
    slots: [{ name: 'cell-{r}-{c}', dynamic: true }],
    allowedChildren: { mode: 'all' },
    applicableStyles: LAYOUT_STYLES,
    inspector: [
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
    ],
    defaultProps: { ...TABLE_DEFAULT_PROPS },
    createInitialSlots: (nodeId: NodeId, props?: Record<string, unknown>) => {
      const rows = (props?.rows as number | undefined) ?? TABLE_DEFAULT_PROPS.rows
      const columns = (props?.columns as number | undefined) ?? TABLE_DEFAULT_PROPS.columns

      const slots: Slot[] = []
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c < columns; c++) {
          slots.push({
            id: nanoid() as SlotId,
            nodeId,
            name: cellSlotName(r, c),
            children: [],
          })
        }
      }
      return slots
    },

    // ----- Command routing -----
    commandTypes: TABLE_COMMAND_TYPES,
    commandHandler: (doc, indexes, command) =>
      applyTableCommand(doc, indexes, command as TableCommand),

    // ----- Canvas hook -----
    renderCanvas: ({ node, doc, engine: eng, renderSlot }) => {
      const engine = eng as EditorEngine
      const props = node.props ?? {}
      const rows = (props.rows as number) ?? 0
      const columns = (props.columns as number) ?? 0
      const columnWidths = (props.columnWidths as number[]) ?? []
      const merges = (props.merges as CellMerge[]) ?? []
      const headerRows = (props.headerRows as number) ?? 0
      const borderStyle = (props.borderStyle as string) ?? 'all'

      if (rows <= 0 || columns <= 0) return html`<div class="table-canvas-empty">Empty table</div>`

      // Build slot lookup by name
      const slotsByName = new Map<string, SlotId>()
      for (const slotId of node.slots) {
        const slot = doc.slots[slotId]
        if (slot) slotsByName.set(slot.name, slot.id)
      }

      // Compute grid template columns from widths
      const total = columnWidths.reduce((a, b) => a + b, 0) || columns
      const gridTemplateColumns = columnWidths
        .map(w => `${((w / total) * 100).toFixed(2)}%`)
        .join(' ')

      // Get cell selection from engine component state
      const cellSelection = engine.getComponentState<CellSelection>('table:cellSelection')
      const normSel = cellSelection ? {
        startRow: Math.min(cellSelection.startRow, cellSelection.endRow),
        startCol: Math.min(cellSelection.startCol, cellSelection.endCol),
        endRow: Math.max(cellSelection.startRow, cellSelection.endRow),
        endCol: Math.max(cellSelection.startCol, cellSelection.endCol),
      } : null

      const handleCellClick = (e: MouseEvent, row: number, col: number) => {
        e.stopPropagation()

        // Select the table node so the inspector shows table controls.
        // Must happen before setComponentState because the selection:change
        // listener clears cell selection — we set it again right after.
        engine.selectNode(node.id)

        // Read current selection from engine (not the render-time closure) to
        // handle shift-click correctly even when the canvas hasn't re-rendered.
        const currentSel = engine.getComponentState<CellSelection>('table:cellSelection')
        let newSel: CellSelection
        if (e.shiftKey && currentSel) {
          newSel = { ...currentSel, endRow: row, endCol: col }
        } else {
          newSel = { startRow: row, startCol: col, endRow: row, endCol: col }
        }
        engine.setComponentState('table:cellSelection', newSel)
      }

      // Build cells
      const cells: unknown[] = []
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c < columns; c++) {
          if (isCellCovered(r, c, merges)) continue

          const merge = findMergeAt(r, c, merges)
          const isHeader = r < headerRows
          const isSelected = normSel
            ? r >= normSel.startRow && r <= normSel.endRow &&
              c >= normSel.startCol && c <= normSel.endCol
            : false

          const cellStyle: Record<string, string> = {}
          if (merge) {
            if (merge.colSpan > 1) cellStyle['grid-column'] = `span ${merge.colSpan}`
            if (merge.rowSpan > 1) cellStyle['grid-row'] = `span ${merge.rowSpan}`
          }

          const slotName = cellSlotName(r, c)
          const slotId = slotsByName.get(slotName)

          cells.push(html`
            <div
              class="table-canvas-cell ${isHeader ? 'header' : ''} ${isSelected ? 'cell-selected' : ''}"
              style=${styleMap(cellStyle)}
              data-cell-row=${r}
              data-cell-col=${c}
              @click=${(e: MouseEvent) => handleCellClick(e, r, c)}
            >
              ${slotId ? renderSlot(slotId) : nothing}
            </div>
          `)
        }
      }

      return html`
        <div
          class="table-canvas-grid border-${borderStyle}"
          style=${styleMap({ 'grid-template-columns': gridTemplateColumns })}
        >
          ${cells}
        </div>
      `
    },

    // ----- Inspector hook -----
    renderInspector: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine
      return html`<table-inspector
        .node=${node}
        .engine=${engine}
      ></table-inspector>`
    },

    // ----- Palette pre-insert hook -----
    onBeforeInsert: async () => {
      const result = await openTableDialog()
      if (result.cancelled) return null

      const columnWidths = Array(result.columns).fill(
        Math.round(100 / result.columns),
      )
      return {
        rows: result.rows,
        columns: result.columns,
        columnWidths,
      }
    },
  }
}
