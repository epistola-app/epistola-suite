/**
 * Reusable expression dialog for editing JSONata expressions.
 *
 * Extracted from ExpressionNodeView to be shared between:
 * - Inline expression chips (ProseMirror nodes)
 * - Inspector expression fields (conditional, loop)
 *
 * Features:
 * - Field path autocomplete with filtering
 * - Instant JSONata validation (green/red border)
 * - Debounced live preview against example data
 * - JSONata quick reference panel
 */

import type { FieldPath } from '../engine/schema-paths.js'
import {
  tryEvaluateExpression,
  formatForPreview,
  isValidExpression,
} from '../engine/resolve-expression.js'

/** Common JSONata patterns for the quick reference panel. */
const JSONATA_QUICK_REFERENCE: { code: string; desc: string }[] = [
  { code: 'customer.name', desc: 'Access a field' },
  { code: 'address.line1 & ", " & address.city', desc: 'Concatenate strings' },
  { code: 'age >= 18 ? "Adult" : "Minor"', desc: 'Conditional' },
  { code: '$sum(items.price)', desc: 'Sum numbers' },
  { code: '$count(items)', desc: 'Count array items' },
  { code: '$join(tags, ", ")', desc: 'Join array to string' },
  { code: '$uppercase(name)', desc: 'Uppercase text' },
  { code: '$lowercase(name)', desc: 'Lowercase text' },
  { code: '$now()', desc: 'Current timestamp' },
  { code: '$substring(name, 0, 10)', desc: 'Substring' },
  { code: 'items[price > 100]', desc: 'Filter array' },
  { code: '$number(value)', desc: 'Convert to number' },
]

export interface ExpressionDialogOptions {
  initialValue: string
  fieldPaths: FieldPath[]
  getExampleData?: () => Record<string, unknown> | undefined
  label?: string
  placeholder?: string
  /** Optional filter to highlight certain field paths (e.g., array fields for loops). */
  fieldPathFilter?: (fp: FieldPath) => boolean
  /**
   * Optional validator for the evaluated result.
   * Return an error message string if the result is invalid, or null if valid.
   * Called after successful evaluation — not called on parse errors or missing data.
   */
  resultValidator?: (value: unknown) => string | null
}

export interface ExpressionDialogResult {
  /** The expression value, or null if the user cancelled. */
  value: string | null
}

/**
 * Open a modal dialog for editing a JSONata expression.
 * Returns a promise that resolves when the dialog closes.
 */
export function openExpressionDialog(options: ExpressionDialogOptions): Promise<ExpressionDialogResult> {
  return new Promise((resolve) => {
    const {
      initialValue,
      fieldPaths,
      getExampleData,
      label = 'Expression',
      placeholder = 'e.g. customer.name',
      fieldPathFilter,
      resultValidator,
    } = options

    let previewTimer: ReturnType<typeof setTimeout> | null = null
    let previewGeneration = 0

    const dialog = document.createElement('dialog')
    dialog.className = 'expression-dialog'

    dialog.innerHTML = `
      <form method="dialog" class="expression-dialog-form">
        <label class="expression-dialog-label">${escapeHtml(label)}</label>
        <input
          type="text"
          class="expression-dialog-input"
          value="${escapeAttr(initialValue)}"
          placeholder="${escapeAttr(placeholder)}"
          autocomplete="off"
        />
        <div class="expression-dialog-preview" style="display:none"></div>
        <div class="expression-dialog-paths"></div>
        <details class="expression-dialog-reference">
          <summary class="expression-dialog-ref-summary">JSONata Quick Reference</summary>
          <div class="expression-dialog-ref-list"></div>
        </details>
        <div class="expression-dialog-actions">
          <button type="button" class="expression-dialog-btn cancel">Cancel</button>
          <button type="submit" class="expression-dialog-btn save">Save</button>
        </div>
      </form>
    `

    const input = dialog.querySelector<HTMLInputElement>('.expression-dialog-input')!
    const cancelBtn = dialog.querySelector('.cancel')!
    const pathsContainer = dialog.querySelector<HTMLElement>('.expression-dialog-paths')!
    const previewEl = dialog.querySelector<HTMLElement>('.expression-dialog-preview')!
    const refList = dialog.querySelector<HTMLElement>('.expression-dialog-ref-list')!

    // --- Field paths ---
    renderFieldPaths(pathsContainer, input, fieldPaths, fieldPathFilter)

    // --- Quick reference ---
    renderQuickReference(refList, input)

    // --- Validation + preview ---
    const applyValidation = () => {
      const val = input.value.trim()
      input.classList.remove('valid', 'invalid')
      if (val) {
        input.classList.add(isValidExpression(val) ? 'valid' : 'invalid')
      }
    }

    const cancelPreviewTimer = () => {
      if (previewTimer !== null) {
        clearTimeout(previewTimer)
        previewTimer = null
      }
    }

    const schedulePreview = () => {
      cancelPreviewTimer()
      const val = input.value.trim()
      if (!val) {
        previewEl.style.display = 'none'
        return
      }
      previewTimer = setTimeout(() => {
        updatePreview(val, previewEl, getExampleData, () => ++previewGeneration, () => previewGeneration, resultValidator)
      }, 250)
    }

    input.addEventListener('input', () => {
      applyValidation()
      schedulePreview()
    })

    // --- Close helpers ---
    const close = (value: string | null) => {
      cancelPreviewTimer()
      previewGeneration++ // discard in-flight previews
      dialog.close()
      dialog.remove()
      resolve({ value })
    }

    // Cancel
    cancelBtn.addEventListener('click', () => close(null))

    // Escape
    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        close(null)
      }
    })

    // Submit
    dialog.querySelector('form')!.addEventListener('submit', (e) => {
      e.preventDefault()
      const trimmed = input.value.trim()
      close(trimmed || null)
    })

    // Backdrop click
    dialog.addEventListener('click', (e) => {
      if (e.target === dialog) close(null)
    })

    // Show
    document.body.appendChild(dialog)
    dialog.showModal()
    input.focus()
    input.select()

    // Initial validation + preview
    if (initialValue) {
      applyValidation()
      updatePreview(initialValue, previewEl, getExampleData, () => ++previewGeneration, () => previewGeneration, resultValidator)
    }
  })
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function renderFieldPaths(
  container: HTMLElement,
  input: HTMLInputElement,
  fieldPaths: FieldPath[],
  fieldPathFilter?: (fp: FieldPath) => boolean,
): void {
  if (fieldPaths.length === 0) return

  const header = document.createElement('div')
  header.className = 'expression-dialog-paths-header'

  const headerLabel = document.createElement('span')
  headerLabel.textContent = 'Available fields'

  const filterInput = document.createElement('input')
  filterInput.type = 'text'
  filterInput.className = 'expression-dialog-filter'
  filterInput.placeholder = 'Filter...'

  header.appendChild(headerLabel)
  header.appendChild(filterInput)
  container.appendChild(header)

  const list = document.createElement('ul')
  list.className = 'expression-dialog-paths-list'

  const items: { li: HTMLLIElement; path: string }[] = []

  for (const fp of fieldPaths) {
    const li = document.createElement('li')
    li.className = 'expression-dialog-path-item'

    // Highlight items matching the filter function (e.g., array fields for loops)
    if (fieldPathFilter?.(fp)) {
      li.classList.add('highlighted')
    }

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
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.focus()
    })

    list.appendChild(li)
    items.push({ li, path: fp.path })
  }

  // Filter field paths on typing
  filterInput.addEventListener('input', () => {
    const query = filterInput.value.toLowerCase()
    for (const item of items) {
      item.li.style.display = item.path.toLowerCase().includes(query) ? '' : 'none'
    }
  })

  container.appendChild(list)
}

function renderQuickReference(container: HTMLElement, input: HTMLInputElement): void {
  for (const entry of JSONATA_QUICK_REFERENCE) {
    const row = document.createElement('div')
    row.className = 'expression-dialog-ref-row'

    const code = document.createElement('code')
    code.className = 'expression-dialog-ref-code'
    code.textContent = entry.code

    const desc = document.createElement('span')
    desc.className = 'expression-dialog-ref-desc'
    desc.textContent = entry.desc

    row.appendChild(code)
    row.appendChild(desc)

    row.addEventListener('click', () => {
      input.value = entry.code
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.focus()
    })

    container.appendChild(row)
  }
}

function updatePreview(
  expression: string,
  previewEl: HTMLElement,
  getExampleData: (() => Record<string, unknown> | undefined) | undefined,
  incrementGeneration: () => number,
  getGeneration: () => number,
  resultValidator?: (value: unknown) => string | null,
): void {
  const data = getExampleData?.()
  if (!data) {
    previewEl.style.display = ''
    previewEl.className = 'expression-dialog-preview no-data'
    previewEl.textContent = 'No data example selected'
    return
  }

  const generation = incrementGeneration()
  tryEvaluateExpression(expression, data).then((result) => {
    if (generation !== getGeneration()) return // stale

    previewEl.style.display = ''
    if (result.ok) {
      // Run result validator if provided (e.g., loop expressions must be arrays)
      const validationError = resultValidator?.(result.value)
      if (validationError) {
        previewEl.className = 'expression-dialog-preview error'
        previewEl.textContent = validationError
      } else {
        previewEl.className = 'expression-dialog-preview success'
        previewEl.textContent = `Preview: ${formatForPreview(result.value)}`
      }
    } else {
      previewEl.className = 'expression-dialog-preview error'
      previewEl.textContent = result.error
    }
  })
}

function escapeAttr(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
