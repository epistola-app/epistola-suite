/**
 * Pure utility functions for table operations.
 *
 * All functions are stateless — they take data in and return results.
 * Used by table commands, canvas rendering, and inspector.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface CellMerge {
  /** Top-left row of the merge region. */
  row: number
  /** Top-left column of the merge region. */
  col: number
  /** Number of rows this merge spans. */
  rowSpan: number
  /** Number of columns this merge spans. */
  colSpan: number
}

export interface CellCoord {
  row: number
  col: number
}

export interface CellSelection {
  startRow: number
  startCol: number
  endRow: number
  endCol: number
}

// ---------------------------------------------------------------------------
// Cell naming
// ---------------------------------------------------------------------------

/** Slot name for a cell at the given row and column. */
export function cellSlotName(row: number, col: number): string {
  return `cell-${row}-${col}`
}

/** Parse a cell slot name like "cell-2-3" into {row, col}, or null if invalid. */
export function parseCellName(name: string): CellCoord | null {
  const match = /^cell-(\d+)-(\d+)$/.exec(name)
  if (!match) return null
  return { row: Number(match[1]), col: Number(match[2]) }
}

// ---------------------------------------------------------------------------
// Merge queries
// ---------------------------------------------------------------------------

/**
 * Find the merge entry whose region contains (row, col), if any.
 * Returns the merge or undefined.
 */
export function findMergeAt(row: number, col: number, merges: CellMerge[]): CellMerge | undefined {
  return merges.find(
    m => row >= m.row && row < m.row + m.rowSpan && col >= m.col && col < m.col + m.colSpan,
  )
}

/**
 * Whether the cell at (row, col) is covered by a merge but is NOT the
 * top-left anchor cell. Covered cells should not be rendered.
 */
export function isCellCovered(row: number, col: number, merges: CellMerge[]): boolean {
  const merge = findMergeAt(row, col, merges)
  if (!merge) return false
  return merge.row !== row || merge.col !== col
}

/**
 * Whether a rectangular selection can be merged.
 * Fails if:
 * - The selection is a single cell (1x1)
 * - The selection partially overlaps an existing merge
 */
export function canMerge(
  startRow: number,
  startCol: number,
  endRow: number,
  endCol: number,
  merges: CellMerge[],
): boolean {
  const rows = endRow - startRow + 1
  const cols = endCol - startCol + 1
  if (rows <= 1 && cols <= 1) return false

  for (const m of merges) {
    const mEndRow = m.row + m.rowSpan - 1
    const mEndCol = m.col + m.colSpan - 1

    // Check if merge overlaps with selection
    const overlaps =
      m.row <= endRow && mEndRow >= startRow && m.col <= endCol && mEndCol >= startCol

    if (!overlaps) continue

    // If the merge is fully contained in the selection, that's OK
    const fullyContained =
      m.row >= startRow && mEndRow <= endRow && m.col >= startCol && mEndCol <= endCol

    if (!fullyContained) return false
  }

  return true
}

// ---------------------------------------------------------------------------
// Merge shifting for row/column insert/remove
// ---------------------------------------------------------------------------

/**
 * Shift merge positions after inserting a row at `position`.
 * Rows at `position` and above shift down by one.
 * Merges that span across the insertion point grow by one row.
 */
export function shiftMergesForRowInsert(merges: CellMerge[], position: number): CellMerge[] {
  return merges.map(m => {
    if (m.row >= position) {
      // Merge starts at or after insertion → shift down
      return { ...m, row: m.row + 1 }
    }
    if (m.row + m.rowSpan > position) {
      // Merge spans across insertion point → grow
      return { ...m, rowSpan: m.rowSpan + 1 }
    }
    return m
  })
}

/**
 * Shift merge positions after removing a row at `position`.
 * Returns updated merges. Merges fully within the removed row are dropped.
 */
export function shiftMergesForRowRemove(merges: CellMerge[], position: number): CellMerge[] {
  const result: CellMerge[] = []
  for (const m of merges) {
    const mEnd = m.row + m.rowSpan - 1

    if (m.row > position) {
      // Merge starts after removal → shift up
      result.push({ ...m, row: m.row - 1 })
    } else if (mEnd < position) {
      // Merge ends before removal → unchanged
      result.push(m)
    } else if (m.row === position && m.rowSpan === 1) {
      // Single-row merge at removed position → drop
      continue
    } else {
      // Merge spans across removal → shrink
      const newRowSpan = m.rowSpan - 1
      if (newRowSpan > 0) {
        result.push({ ...m, rowSpan: newRowSpan })
      }
    }
  }
  return result
}

/**
 * Shift merge positions after inserting a column at `position`.
 */
export function shiftMergesForColInsert(merges: CellMerge[], position: number): CellMerge[] {
  return merges.map(m => {
    if (m.col >= position) {
      return { ...m, col: m.col + 1 }
    }
    if (m.col + m.colSpan > position) {
      return { ...m, colSpan: m.colSpan + 1 }
    }
    return m
  })
}

/**
 * Shift merge positions after removing a column at `position`.
 */
export function shiftMergesForColRemove(merges: CellMerge[], position: number): CellMerge[] {
  const result: CellMerge[] = []
  for (const m of merges) {
    const mEnd = m.col + m.colSpan - 1

    if (m.col > position) {
      result.push({ ...m, col: m.col - 1 })
    } else if (mEnd < position) {
      result.push(m)
    } else if (m.col === position && m.colSpan === 1) {
      continue
    } else {
      const newColSpan = m.colSpan - 1
      if (newColSpan > 0) {
        result.push({ ...m, colSpan: newColSpan })
      }
    }
  }
  return result
}

// ---------------------------------------------------------------------------
// Selection normalization
// ---------------------------------------------------------------------------

/** Normalize a cell selection so start <= end. */
export function normalizeSelection(sel: CellSelection): CellSelection {
  return {
    startRow: Math.min(sel.startRow, sel.endRow),
    startCol: Math.min(sel.startCol, sel.endCol),
    endRow: Math.max(sel.startRow, sel.endRow),
    endCol: Math.max(sel.startCol, sel.endCol),
  }
}

/**
 * Expand a selection to fully contain any merge that it partially overlaps.
 * This is needed so that merge operations work on complete merge regions.
 */
export function expandSelectionForMerges(sel: CellSelection, merges: CellMerge[]): CellSelection {
  let { startRow, startCol, endRow, endCol } = normalizeSelection(sel)
  let changed = true

  while (changed) {
    changed = false
    for (const m of merges) {
      const mEndRow = m.row + m.rowSpan - 1
      const mEndCol = m.col + m.colSpan - 1

      const overlaps =
        m.row <= endRow && mEndRow >= startRow && m.col <= endCol && mEndCol >= startCol

      if (overlaps) {
        if (m.row < startRow) { startRow = m.row; changed = true }
        if (m.col < startCol) { startCol = m.col; changed = true }
        if (mEndRow > endRow) { endRow = mEndRow; changed = true }
        if (mEndCol > endCol) { endCol = mEndCol; changed = true }
      }
    }
  }

  return { startRow, startCol, endRow, endCol }
}
