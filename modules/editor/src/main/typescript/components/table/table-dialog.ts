/**
 * Table insert dialog — visual grid picker for choosing table dimensions.
 *
 * Follows the same pattern as expression-dialog.ts:
 * native <dialog> element, returns a Promise.
 */

export interface TableDialogResult {
  rows: number
  columns: number
  cancelled: boolean
}

const MAX_GRID = 8
const DEFAULT_ROWS = 3
const DEFAULT_COLS = 3

/**
 * Open a dialog for selecting table dimensions.
 * Returns rows/columns or cancelled=true.
 */
export function openTableDialog(): Promise<TableDialogResult> {
  return new Promise((resolve) => {
    let hoverRow = 0
    let hoverCol = 0

    const dialog = document.createElement('dialog')
    dialog.className = 'table-dialog'

    dialog.innerHTML = `
      <div class="table-dialog-content">
        <div class="table-dialog-label">Insert Table</div>
        <div class="table-dialog-grid"></div>
        <div class="table-dialog-size-label">${DEFAULT_ROWS} x ${DEFAULT_COLS}</div>
        <div class="table-dialog-inputs">
          <label>
            Rows
            <input type="number" class="ep-input table-dialog-rows" min="1" max="50" value="${DEFAULT_ROWS}" />
          </label>
          <label>
            Columns
            <input type="number" class="ep-input table-dialog-cols" min="1" max="20" value="${DEFAULT_COLS}" />
          </label>
        </div>
        <div class="table-dialog-actions">
          <button type="button" class="table-dialog-btn cancel">Cancel</button>
          <button type="button" class="table-dialog-btn insert">Insert</button>
        </div>
      </div>
    `

    const gridContainer = dialog.querySelector<HTMLElement>('.table-dialog-grid')!
    const sizeLabel = dialog.querySelector<HTMLElement>('.table-dialog-size-label')!
    const rowsInput = dialog.querySelector<HTMLInputElement>('.table-dialog-rows')!
    const colsInput = dialog.querySelector<HTMLInputElement>('.table-dialog-cols')!
    const cancelBtn = dialog.querySelector('.cancel')!
    const insertBtn = dialog.querySelector('.insert')!

    // Build grid cells
    for (let r = 0; r < MAX_GRID; r++) {
      for (let c = 0; c < MAX_GRID; c++) {
        const cell = document.createElement('div')
        cell.className = 'table-dialog-cell'
        cell.dataset.row = String(r)
        cell.dataset.col = String(c)

        cell.addEventListener('mouseenter', () => {
          hoverRow = r
          hoverCol = c
          updateGrid()
          rowsInput.value = String(r + 1)
          colsInput.value = String(c + 1)
          sizeLabel.textContent = `${r + 1} x ${c + 1}`
        })

        cell.addEventListener('click', () => {
          close({ rows: r + 1, columns: c + 1, cancelled: false })
        })

        gridContainer.appendChild(cell)
      }
    }

    // Style grid as CSS grid
    gridContainer.style.display = 'grid'
    gridContainer.style.gridTemplateColumns = `repeat(${MAX_GRID}, 1fr)`
    gridContainer.style.gap = '2px'

    function updateGrid() {
      const cells = gridContainer.querySelectorAll<HTMLElement>('.table-dialog-cell')
      for (const cell of cells) {
        const r = Number(cell.dataset.row)
        const c = Number(cell.dataset.col)
        cell.classList.toggle('active', r <= hoverRow && c <= hoverCol)
      }
    }

    // Initialize grid with default selection
    hoverRow = DEFAULT_ROWS - 1
    hoverCol = DEFAULT_COLS - 1
    updateGrid()

    // Sync inputs → grid
    const syncFromInputs = () => {
      const r = Math.max(1, Math.min(50, Number(rowsInput.value) || 1))
      const c = Math.max(1, Math.min(20, Number(colsInput.value) || 1))
      hoverRow = Math.min(r - 1, MAX_GRID - 1)
      hoverCol = Math.min(c - 1, MAX_GRID - 1)
      sizeLabel.textContent = `${r} x ${c}`
      updateGrid()
    }
    rowsInput.addEventListener('input', syncFromInputs)
    colsInput.addEventListener('input', syncFromInputs)

    // Close helpers
    const close = (result: TableDialogResult) => {
      dialog.close()
      dialog.remove()
      resolve(result)
    }

    cancelBtn.addEventListener('click', () => close({ rows: 0, columns: 0, cancelled: true }))

    insertBtn.addEventListener('click', () => {
      const rows = Math.max(1, Number(rowsInput.value) || 1)
      const columns = Math.max(1, Number(colsInput.value) || 1)
      close({ rows, columns, cancelled: false })
    })

    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        close({ rows: 0, columns: 0, cancelled: true })
      }
    })

    dialog.addEventListener('click', (e) => {
      if (e.target === dialog) close({ rows: 0, columns: 0, cancelled: true })
    })

    document.body.appendChild(dialog)
    dialog.showModal()
  })
}
