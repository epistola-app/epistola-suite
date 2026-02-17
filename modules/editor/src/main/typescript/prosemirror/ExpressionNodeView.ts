/**
 * ProseMirror NodeView for expression chips.
 *
 * Renders as an inline `<span class="expression-chip">` showing either:
 * - The resolved value from the current data example (e.g., "John Doe")
 * - The raw expression as fallback (e.g., `{{customer.name}}`)
 *
 * Click opens the shared expression dialog.
 *
 * - Enter → save expression
 * - Escape → cancel (delete if isNew, else close)
 * - Auto-opens dialog when `isNew` is true
 */

import type { Node as ProsemirrorNode } from 'prosemirror-model'
import type { EditorView, NodeView } from 'prosemirror-view'
import type { FieldPath } from '../engine/schema-paths.js'
import {
  evaluateExpression,
  formatResolvedValue,
} from '../engine/resolve-expression.js'
import { openExpressionDialog } from '../ui/expression-dialog.js'

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
  private _dialogOpen = false
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
    if (this._dialogOpen) return
    this._dialogOpen = true

    const expr = this._node.attrs.expression as string

    openExpressionDialog({
      initialValue: expr,
      fieldPaths: this._fieldPaths,
      getExampleData: this._getExampleData,
      label: 'Expression',
      placeholder: 'e.g. customer.name',
    }).then(({ value }) => {
      this._dialogOpen = false
      if (value !== null) {
        this._updateAttrs({ expression: value, isNew: false })
      } else if (this._node.attrs.isNew) {
        this._deleteNode()
      }
      this._view.focus()
    })
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
}
