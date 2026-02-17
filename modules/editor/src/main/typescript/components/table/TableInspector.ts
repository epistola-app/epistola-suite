/**
 * TableInspector — Lit component that renders table-specific inspector controls.
 *
 * Features:
 * - Row/column count (+/- buttons)
 * - Per-column width inputs
 * - Header rows number input
 * - Merge/unmerge controls (when cells are selected)
 *
 * Cell selection is read from engine.getComponentState('table:cellSelection')
 * and reactively updated via the 'component-state:change' event.
 */

import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import type { Node } from '../../types/index.js'
import type { EditorEngine } from '../../engine/EditorEngine.js'
import {
  findMergeAt,
  canMerge,
  normalizeSelection,
  expandSelectionForMerges,
  type CellMerge,
  type CellSelection,
} from './table-utils.js'

@customElement('table-inspector')
export class TableInspector extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) node!: Node
  @property({ attribute: false }) engine!: EditorEngine

  @state() private _cellSelection: CellSelection | null = null

  private _unsubState?: () => void

  override connectedCallback(): void {
    super.connectedCallback()
    // Read current cell selection and subscribe to changes
    this._cellSelection = this.engine.getComponentState<CellSelection>('table:cellSelection') ?? null
    this._unsubState = this.engine.events.on('component-state:change', ({ key, value }) => {
      if (key === 'table:cellSelection') {
        this._cellSelection = (value as CellSelection) ?? null
      }
    })
  }

  override disconnectedCallback(): void {
    this._unsubState?.()
    super.disconnectedCallback()
  }

  private get _props() {
    const props = this.node.props ?? {}
    return {
      rows: (props.rows as number) ?? 0,
      columns: (props.columns as number) ?? 0,
      columnWidths: (props.columnWidths as number[]) ?? [],
      merges: (props.merges as CellMerge[]) ?? [],
      headerRows: (props.headerRows as number) ?? 0,
    }
  }

  override render() {
    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Table Layout</div>
        ${this._renderRowCount()}
        ${this._renderColumnCount()}
        ${this._renderColumnWidths()}
        ${this._renderHeaderRows()}
        ${this._renderMergeControls()}
      </div>
    `
  }

  // -----------------------------------------------------------------------
  // Row count
  // -----------------------------------------------------------------------

  private _renderRowCount() {
    const { rows } = this._props
    return html`
      <div class="inspector-field">
        <label class="inspector-field-label">Rows</label>
        <div class="inspector-column-count">
          <button
            class="inspector-column-btn"
            ?disabled=${rows <= 1}
            @click=${() => this._handleRemoveRow()}
          >&minus;</button>
          <span class="inspector-column-count-value">${rows}</span>
          <button
            class="inspector-column-btn"
            @click=${() => this._handleAddRow()}
          >+</button>
        </div>
      </div>
    `
  }

  private _handleAddRow() {
    this.engine.dispatch({
      type: 'AddTableRow',
      nodeId: this.node.id,
      position: this._props.rows,
    })
  }

  private _handleRemoveRow() {
    const { rows } = this._props
    if (rows <= 1) return
    this.engine.dispatch({
      type: 'RemoveTableRow',
      nodeId: this.node.id,
      position: rows - 1,
    })
  }

  // -----------------------------------------------------------------------
  // Column count
  // -----------------------------------------------------------------------

  private _renderColumnCount() {
    const { columns } = this._props
    return html`
      <div class="inspector-field">
        <label class="inspector-field-label">Columns</label>
        <div class="inspector-column-count">
          <button
            class="inspector-column-btn"
            ?disabled=${columns <= 1}
            @click=${() => this._handleRemoveColumn()}
          >&minus;</button>
          <span class="inspector-column-count-value">${columns}</span>
          <button
            class="inspector-column-btn"
            @click=${() => this._handleAddColumn()}
          >+</button>
        </div>
      </div>
    `
  }

  private _handleAddColumn() {
    const { columns, columnWidths } = this._props
    // Use the average existing width as the default for new column
    const avgWidth = columnWidths.length > 0
      ? Math.round(columnWidths.reduce((a, b) => a + b, 0) / columnWidths.length)
      : 50
    this.engine.dispatch({
      type: 'AddTableColumn',
      nodeId: this.node.id,
      position: columns,
      width: avgWidth,
    })
  }

  private _handleRemoveColumn() {
    const { columns } = this._props
    if (columns <= 1) return
    this.engine.dispatch({
      type: 'RemoveTableColumn',
      nodeId: this.node.id,
      position: columns - 1,
    })
  }

  // -----------------------------------------------------------------------
  // Column widths
  // -----------------------------------------------------------------------

  private _renderColumnWidths() {
    const { columnWidths } = this._props
    return html`
      <div class="inspector-field">
        <label class="inspector-field-label">Column Widths</label>
        <div class="inspector-column-sizes">
          ${columnWidths.map((width, i) => html`
            <div class="inspector-column-size">
              <span class="inspector-column-size-label">${i + 1}</span>
              <input
                type="number"
                class="ep-input inspector-column-size-input"
                min="1"
                .value=${String(width)}
                @change=${(e: Event) => this._handleColumnWidthChange(i, Number((e.target as HTMLInputElement).value))}
              />
            </div>
          `)}
        </div>
      </div>
    `
  }

  private _handleColumnWidthChange(index: number, width: number) {
    const props = this.node.props ?? {}
    const columnWidths = [...((props.columnWidths as number[]) ?? [])]
    columnWidths[index] = Math.max(1, width)

    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.node.id,
      props: { ...props, columnWidths },
    })
  }

  // -----------------------------------------------------------------------
  // Header rows
  // -----------------------------------------------------------------------

  private _renderHeaderRows() {
    const { headerRows, rows } = this._props
    return html`
      <div class="inspector-field">
        <label class="inspector-field-label">Header Rows</label>
        <input
          type="number"
          class="ep-input"
          min="0"
          max=${rows}
          .value=${String(headerRows)}
          @change=${(e: Event) => {
            const value = Math.min(Math.max(0, Number((e.target as HTMLInputElement).value)), rows)
            this.engine.dispatch({
              type: 'SetTableHeaderRows',
              nodeId: this.node.id,
              headerRows: value,
            })
          }}
        />
      </div>
    `
  }

  // -----------------------------------------------------------------------
  // Merge controls
  // -----------------------------------------------------------------------

  private _renderMergeControls() {
    if (!this._cellSelection) return nothing

    const { merges } = this._props
    const sel = normalizeSelection(this._cellSelection)
    const isMultiCell = sel.endRow > sel.startRow || sel.endCol > sel.startCol

    // Check if anchor cell of selection is a merged cell
    const mergeAtAnchor = findMergeAt(sel.startRow, sel.startCol, merges)
    const isMergedCell = mergeAtAnchor &&
      mergeAtAnchor.row === sel.startRow && mergeAtAnchor.col === sel.startCol

    const canDoMerge = isMultiCell && canMerge(
      sel.startRow, sel.startCol, sel.endRow, sel.endCol, merges,
    )

    return html`
      <div class="inspector-field table-merge-controls">
        <label class="inspector-field-label">Selection (${sel.startRow},${sel.startCol}) - (${sel.endRow},${sel.endCol})</label>
        <div class="table-merge-buttons">
          ${canDoMerge ? html`
            <button
              class="ep-btn-sm"
              @click=${() => this._handleMerge(sel)}
            >Merge Cells</button>
          ` : nothing}
          ${isMergedCell ? html`
            <button
              class="ep-btn-sm"
              @click=${() => this._handleUnmerge(sel.startRow, sel.startCol)}
            >Unmerge</button>
          ` : nothing}
        </div>
      </div>
    `
  }

  private _handleMerge(sel: CellSelection) {
    const { merges } = this._props
    const expanded = expandSelectionForMerges(sel, merges)
    this.engine.dispatch({
      type: 'MergeTableCells',
      nodeId: this.node.id,
      startRow: expanded.startRow,
      startCol: expanded.startCol,
      endRow: expanded.endRow,
      endCol: expanded.endCol,
    })
  }

  private _handleUnmerge(row: number, col: number) {
    this.engine.dispatch({
      type: 'UnmergeTableCells',
      nodeId: this.node.id,
      row,
      col,
    })
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'table-inspector': TableInspector
  }
}
