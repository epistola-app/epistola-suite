/**
 * ProseMirror NodeView for expression chips.
 *
 * Renders as an inline `<span class="expression-chip">` showing either:
 * - The resolved value from the current data example (e.g., "John Doe")
 * - The raw expression as fallback (e.g., `{{customer.name}}`)
 *
 * Click opens a `<dialog>` with an `<input>` and a list of available field paths.
 *
 * - Enter → save expression
 * - Escape → cancel (delete if isNew, else close)
 * - Auto-opens dialog when `isNew` is true
 */

import type { Node as ProsemirrorNode } from 'prosemirror-model'
import type { EditorView, NodeView } from 'prosemirror-view'
import type { FieldPath } from '../engine/schema-paths.js'
import { evaluateExpression, formatResolvedValue } from '../engine/resolve-expression.js'

export interface ExpressionNodeViewOptions {
  fieldPaths: FieldPath[]
  getExampleData?: () => Record<string, unknown> | undefined
}

export class ExpressionNodeView implements NodeView {
  /** All live instances, for bulk refresh when data example changes. */
  private static _instances = new Set<ExpressionNodeView>()

  /** Refresh the display of all live expression chips (e.g., after example switch). */
  static refreshAll(): void {
    for (const instance of ExpressionNodeView._instances) {
      instance._updateDisplay()
    }
  }

  dom: HTMLSpanElement
  private _node: ProsemirrorNode
  private _view: EditorView
  private _getPos: () => number | undefined
  private _dialog: HTMLDialogElement | null = null
  private _fieldPaths: FieldPath[]
  private _getExampleData: (() => Record<string, unknown> | undefined) | undefined

  /** Monotonic counter to discard stale async resolution results. */
  private _displayGeneration = 0

  constructor(
    node: ProsemirrorNode,
    view: EditorView,
    getPos: () => number | undefined,
    options: ExpressionNodeViewOptions,
  ) {
    this._node = node
    this._view = view
    this._getPos = getPos
    this._fieldPaths = options.fieldPaths
    this._getExampleData = options.getExampleData

    // Create the chip element
    this.dom = document.createElement('span')
    this.dom.className = 'expression-chip'
    this.dom.contentEditable = 'false'
    this._updateDisplay()

    // Click → open dialog
    this.dom.addEventListener('click', (e) => {
      e.preventDefault()
      e.stopPropagation()
      this._openDialog()
    })

    // Auto-open for new expressions
    if (node.attrs.isNew) {
      // Small delay so the node is rendered and positioned in the DOM
      requestAnimationFrame(() => this._openDialog())
    }

    ExpressionNodeView._instances.add(this)
  }

  update(node: ProsemirrorNode): boolean {
    if (node.type !== this._node.type) return false
    this._node = node
    this._updateDisplay()
    return true
  }

  destroy(): void {
    ExpressionNodeView._instances.delete(this)
    this._displayGeneration++ // invalidate any in-flight async resolution
    this._closeDialog()
  }

  // Prevent ProseMirror from handling events inside the chip
  stopEvent(): boolean {
    return true
  }

  ignoreMutation(): boolean {
    return true
  }

  // ---------------------------------------------------------------------------
  // Display
  // ---------------------------------------------------------------------------

  private _updateDisplay(): void {
    const expr = this._node.attrs.expression as string
    if (!expr) {
      this.dom.textContent = '{{...}}'
      this.dom.title = 'Click to edit expression'
      return
    }

    const data = this._getExampleData?.()
    if (!data) {
      // No data example available — show raw expression
      this.dom.textContent = `{{${expr}}}`
      this.dom.title = expr
      return
    }

    // Show raw expression immediately, then kick off async resolution
    this.dom.textContent = `{{${expr}}}`
    this.dom.title = expr
    this._resolveAndDisplay(expr, data)
  }

  private _resolveAndDisplay(expr: string, data: Record<string, unknown>): void {
    const generation = ++this._displayGeneration

    evaluateExpression(expr, data).then((result) => {
      // Discard stale result (example switched or node destroyed since we started)
      if (generation !== this._displayGeneration) return

      const formatted = formatResolvedValue(result)
      if (formatted !== undefined) {
        // Resolved: show value as text, expression in tooltip
        this.dom.textContent = formatted
        this.dom.title = `{{${expr}}}`
      }
      // Unresolved: keep the {{expression}} already set in _updateDisplay
    })
  }

  // ---------------------------------------------------------------------------
  // Dialog
  // ---------------------------------------------------------------------------

  private _openDialog(): void {
    if (this._dialog) return

    const dialog = document.createElement('dialog')
    dialog.className = 'expression-dialog'

    const expr = this._node.attrs.expression as string

    dialog.innerHTML = `
      <form method="dialog" class="expression-dialog-form">
        <label class="expression-dialog-label">Expression</label>
        <input
          type="text"
          class="expression-dialog-input"
          value="${this._escapeAttr(expr)}"
          placeholder="e.g. customer.name"
          autocomplete="off"
        />
        <div class="expression-dialog-paths"></div>
        <div class="expression-dialog-actions">
          <button type="button" class="expression-dialog-btn cancel">Cancel</button>
          <button type="submit" class="expression-dialog-btn save">Save</button>
        </div>
      </form>
    `

    const input = dialog.querySelector('input')!
    const cancelBtn = dialog.querySelector('.cancel')!
    const pathsContainer = dialog.querySelector('.expression-dialog-paths')!

    // Render field paths
    this._renderFieldPaths(pathsContainer as HTMLElement, input)

    // Cancel
    cancelBtn.addEventListener('click', () => this._handleCancel())

    // Escape
    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        this._handleCancel()
      }
    })

    // Submit
    dialog.querySelector('form')!.addEventListener('submit', (e) => {
      e.preventDefault()
      this._handleSave(input.value)
    })

    // Close on backdrop click
    dialog.addEventListener('click', (e) => {
      if (e.target === dialog) {
        this._handleCancel()
      }
    })

    document.body.appendChild(dialog)
    dialog.showModal()
    this._dialog = dialog

    // Focus and select input content
    input.focus()
    input.select()
  }

  private _renderFieldPaths(container: HTMLElement, input: HTMLInputElement): void {
    if (this._fieldPaths.length === 0) return

    const header = document.createElement('div')
    header.className = 'expression-dialog-paths-header'
    header.textContent = 'Available fields'
    container.appendChild(header)

    const list = document.createElement('ul')
    list.className = 'expression-dialog-paths-list'

    for (const fp of this._fieldPaths) {
      const li = document.createElement('li')
      li.className = 'expression-dialog-path-item'

      const pathSpan = document.createElement('span')
      pathSpan.className = 'expression-dialog-path-name'
      pathSpan.textContent = fp.path

      const typeSpan = document.createElement('span')
      typeSpan.className = 'expression-dialog-path-type'
      typeSpan.textContent = fp.type

      li.appendChild(pathSpan)
      li.appendChild(typeSpan)

      li.addEventListener('click', () => {
        input.value = fp.path
        input.focus()
      })

      list.appendChild(li)
    }

    container.appendChild(list)
  }

  private _handleSave(value: string): void {
    const trimmed = value.trim()
    if (trimmed) {
      this._updateAttrs({ expression: trimmed, isNew: false })
    } else {
      this._deleteNode()
    }
    this._closeDialog()
  }

  private _handleCancel(): void {
    if (this._node.attrs.isNew) {
      this._deleteNode()
    }
    this._closeDialog()
  }

  private _closeDialog(): void {
    if (this._dialog) {
      this._dialog.close()
      this._dialog.remove()
      this._dialog = null
    }
    this._view.focus()
  }

  // ---------------------------------------------------------------------------
  // ProseMirror transactions
  // ---------------------------------------------------------------------------

  private _updateAttrs(attrs: Record<string, unknown>): void {
    const pos = this._getPos()
    if (pos == null) return
    const tr = this._view.state.tr.setNodeMarkup(pos, undefined, {
      ...this._node.attrs,
      ...attrs,
    })
    this._view.dispatch(tr)
  }

  private _deleteNode(): void {
    const pos = this._getPos()
    if (pos == null) return
    const tr = this._view.state.tr.delete(pos, pos + this._node.nodeSize)
    this._view.dispatch(tr)
  }

  // ---------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------

  private _escapeAttr(str: string): string {
    return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
}
