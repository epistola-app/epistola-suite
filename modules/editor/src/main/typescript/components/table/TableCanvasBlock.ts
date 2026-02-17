/**
 * TableCanvasBlock — Lit component that renders a table as a CSS Grid
 * inside the editor canvas.
 *
 * Features:
 * - CSS Grid layout computed from columnWidths
 * - Merged cell support via grid-column/grid-row span
 * - Cell selection (click + shift-click)
 * - Delegates slot rendering to parent canvas
 */

import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import { styleMap } from 'lit/directives/style-map.js'
import type { TemplateDocument, Node, NodeId, SlotId } from '../../types/index.js'
import type { EditorEngine } from '../../engine/EditorEngine.js'
import {
  cellSlotName,
  findMergeAt,
  isCellCovered,
  type CellMerge,
  type CellSelection,
} from './table-utils.js'

@customElement('table-canvas-block')
export class TableCanvasBlock extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) node!: Node
  @property({ attribute: false }) doc!: TemplateDocument
  @property({ attribute: false }) engine!: EditorEngine
  @property({ attribute: false }) renderSlotCallback!: (slotId: SlotId) => unknown
  @property({ attribute: false }) selectedNodeId: NodeId | null = null

  @state() private _cellSelection: CellSelection | null = null

  private get _props() {
    const props = this.node.props ?? {}
    return {
      rows: (props.rows as number) ?? 0,
      columns: (props.columns as number) ?? 0,
      columnWidths: (props.columnWidths as number[]) ?? [],
      merges: (props.merges as CellMerge[]) ?? [],
      headerRows: (props.headerRows as number) ?? 0,
      borderStyle: (props.borderStyle as string) ?? 'all',
    }
  }

  get cellSelection(): CellSelection | null {
    return this._cellSelection
  }

  private _handleCellClick(e: MouseEvent, row: number, col: number) {
    e.stopPropagation()

    if (e.shiftKey && this._cellSelection) {
      // Extend selection
      this._cellSelection = {
        ...this._cellSelection,
        endRow: row,
        endCol: col,
      }
    } else {
      // Start new selection
      this._cellSelection = {
        startRow: row,
        startCol: col,
        endRow: row,
        endCol: col,
      }
    }

    this.requestUpdate()

    // Emit event for inspector to pick up
    this.dispatchEvent(new CustomEvent('table-cell-selection-changed', {
      detail: { selection: this._cellSelection },
      bubbles: true,
      composed: true,
    }))
  }

  override render() {
    const { rows, columns, columnWidths, merges, headerRows, borderStyle } = this._props
    if (rows <= 0 || columns <= 0) return html`<div class="table-canvas-empty">Empty table</div>`

    // Build slot lookup by name
    const slotsByName = new Map<string, SlotId>()
    for (const slotId of this.node.slots) {
      const slot = this.doc.slots[slotId]
      if (slot) slotsByName.set(slot.name, slot.id)
    }

    // Compute grid template columns from widths
    const total = columnWidths.reduce((a, b) => a + b, 0) || columns
    const gridTemplateColumns = columnWidths
      .map(w => `${((w / total) * 100).toFixed(2)}%`)
      .join(' ')

    // Normalize selection
    const sel = this._cellSelection
    const normSel = sel ? {
      startRow: Math.min(sel.startRow, sel.endRow),
      startCol: Math.min(sel.startCol, sel.endCol),
      endRow: Math.max(sel.startRow, sel.endRow),
      endCol: Math.max(sel.startCol, sel.endCol),
    } : null

    return html`
      <div
        class="table-canvas-grid border-${borderStyle}"
        style=${styleMap({ 'grid-template-columns': gridTemplateColumns })}
      >
        ${this._renderCells(rows, columns, merges, headerRows, slotsByName, normSel)}
      </div>
    `
  }

  private _renderCells(
    rows: number,
    columns: number,
    merges: CellMerge[],
    headerRows: number,
    slotsByName: Map<string, SlotId>,
    normSel: CellSelection | null,
  ) {
    const cells: unknown[] = []

    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < columns; c++) {
        // Skip covered cells (part of a merge but not the anchor)
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
            @click=${(e: MouseEvent) => this._handleCellClick(e, r, c)}
          >
            ${slotId ? this.renderSlotCallback(slotId) : nothing}
          </div>
        `)
      }
    }

    return cells
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'table-canvas-block': TableCanvasBlock
  }
}
