/**
 * Datatable insert dialog — simple column count picker.
 *
 * Follows the same pattern as table-dialog.ts:
 * native <dialog> element, returns a Promise.
 */

export interface DatatableDialogResult {
  columns: number
  cancelled: boolean
}

const DEFAULT_COLUMNS = 3

/**
 * Open a dialog for selecting datatable column count.
 * Returns column count or cancelled=true.
 */
export function openDatatableDialog(): Promise<DatatableDialogResult> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog')
    dialog.className = 'datatable-dialog'

    dialog.innerHTML = `
      <div class="datatable-dialog-content">
        <div class="datatable-dialog-label">Insert Data Table</div>
        <div class="datatable-dialog-field">
          <label>
            Number of columns
            <input type="number" class="ep-input datatable-dialog-cols" min="1" max="20" value="${DEFAULT_COLUMNS}" />
          </label>
        </div>
        <div class="datatable-dialog-actions">
          <button type="button" class="datatable-dialog-btn cancel">Cancel</button>
          <button type="button" class="datatable-dialog-btn insert">Insert</button>
        </div>
      </div>
    `

    const colsInput = dialog.querySelector<HTMLInputElement>('.datatable-dialog-cols')!
    const cancelBtn = dialog.querySelector('.cancel')!
    const insertBtn = dialog.querySelector('.insert')!

    const close = (result: DatatableDialogResult) => {
      dialog.close()
      dialog.remove()
      resolve(result)
    }

    cancelBtn.addEventListener('click', () => close({ columns: 0, cancelled: true }))

    insertBtn.addEventListener('click', () => {
      const columns = Math.max(1, Math.min(20, Number(colsInput.value) || DEFAULT_COLUMNS))
      close({ columns, cancelled: false })
    })

    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        close({ columns: 0, cancelled: true })
      }
      if (e.key === 'Enter') {
        e.preventDefault()
        const columns = Math.max(1, Math.min(20, Number(colsInput.value) || DEFAULT_COLUMNS))
        close({ columns, cancelled: false })
      }
    })

    dialog.addEventListener('click', (e) => {
      if (e.target === dialog) close({ columns: 0, cancelled: true })
    })

    document.body.appendChild(dialog)
    dialog.showModal()
    colsInput.focus()
    colsInput.select()
  })
}
