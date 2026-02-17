import { describe, it, expect } from 'vitest'
import {
  cellSlotName,
  parseCellName,
  findMergeAt,
  isCellCovered,
  canMerge,
  shiftMergesForRowInsert,
  shiftMergesForRowRemove,
  shiftMergesForColInsert,
  shiftMergesForColRemove,
  normalizeSelection,
  expandSelectionForMerges,
  type CellMerge,
} from './table-utils.js'

// ---------------------------------------------------------------------------
// cellSlotName / parseCellName
// ---------------------------------------------------------------------------

describe('cellSlotName', () => {
  it('produces "cell-{row}-{col}" format', () => {
    expect(cellSlotName(0, 0)).toBe('cell-0-0')
    expect(cellSlotName(2, 3)).toBe('cell-2-3')
    expect(cellSlotName(10, 5)).toBe('cell-10-5')
  })
})

describe('parseCellName', () => {
  it('parses valid cell names', () => {
    expect(parseCellName('cell-0-0')).toEqual({ row: 0, col: 0 })
    expect(parseCellName('cell-2-3')).toEqual({ row: 2, col: 3 })
    expect(parseCellName('cell-10-5')).toEqual({ row: 10, col: 5 })
  })

  it('returns null for non-cell names', () => {
    expect(parseCellName('column-0')).toBeNull()
    expect(parseCellName('children')).toBeNull()
    expect(parseCellName('cell-a-b')).toBeNull()
    expect(parseCellName('cell-0')).toBeNull()
    expect(parseCellName('')).toBeNull()
  })

  it('round-trips with cellSlotName', () => {
    for (let r = 0; r < 5; r++) {
      for (let c = 0; c < 5; c++) {
        const name = cellSlotName(r, c)
        const parsed = parseCellName(name)
        expect(parsed).toEqual({ row: r, col: c })
      }
    }
  })
})

// ---------------------------------------------------------------------------
// findMergeAt / isCellCovered
// ---------------------------------------------------------------------------

describe('findMergeAt', () => {
  const merges: CellMerge[] = [
    { row: 0, col: 0, rowSpan: 2, colSpan: 2 }, // 2x2 merge at top-left
    { row: 3, col: 1, rowSpan: 1, colSpan: 3 }, // 1x3 merge at row 3
  ]

  it('finds merge at anchor cell', () => {
    expect(findMergeAt(0, 0, merges)).toBe(merges[0])
    expect(findMergeAt(3, 1, merges)).toBe(merges[1])
  })

  it('finds merge at covered cell', () => {
    expect(findMergeAt(0, 1, merges)).toBe(merges[0])
    expect(findMergeAt(1, 0, merges)).toBe(merges[0])
    expect(findMergeAt(1, 1, merges)).toBe(merges[0])
    expect(findMergeAt(3, 2, merges)).toBe(merges[1])
    expect(findMergeAt(3, 3, merges)).toBe(merges[1])
  })

  it('returns undefined for unmerged cells', () => {
    expect(findMergeAt(2, 0, merges)).toBeUndefined()
    expect(findMergeAt(0, 2, merges)).toBeUndefined()
    expect(findMergeAt(4, 4, merges)).toBeUndefined()
  })

  it('returns undefined for empty merges array', () => {
    expect(findMergeAt(0, 0, [])).toBeUndefined()
  })
})

describe('isCellCovered', () => {
  const merges: CellMerge[] = [
    { row: 0, col: 0, rowSpan: 2, colSpan: 2 },
  ]

  it('returns false for anchor cell', () => {
    expect(isCellCovered(0, 0, merges)).toBe(false)
  })

  it('returns true for non-anchor cells within merge', () => {
    expect(isCellCovered(0, 1, merges)).toBe(true)
    expect(isCellCovered(1, 0, merges)).toBe(true)
    expect(isCellCovered(1, 1, merges)).toBe(true)
  })

  it('returns false for cells outside merge', () => {
    expect(isCellCovered(2, 0, merges)).toBe(false)
    expect(isCellCovered(0, 2, merges)).toBe(false)
  })

  it('returns false when no merges', () => {
    expect(isCellCovered(0, 0, [])).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// canMerge
// ---------------------------------------------------------------------------

describe('canMerge', () => {
  it('allows merging multiple cells with no existing merges', () => {
    expect(canMerge(0, 0, 1, 1, [])).toBe(true)
    expect(canMerge(0, 0, 0, 2, [])).toBe(true)
    expect(canMerge(0, 0, 2, 0, [])).toBe(true)
  })

  it('rejects single-cell merge', () => {
    expect(canMerge(0, 0, 0, 0, [])).toBe(false)
    expect(canMerge(3, 2, 3, 2, [])).toBe(false)
  })

  it('allows merge that fully contains existing merge', () => {
    const merges: CellMerge[] = [
      { row: 1, col: 1, rowSpan: 2, colSpan: 2 },
    ]
    // Selection 0,0 to 3,3 fully contains the existing merge
    expect(canMerge(0, 0, 3, 3, merges)).toBe(true)
  })

  it('rejects merge that partially overlaps existing merge', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 2, colSpan: 2 },
    ]
    // Selection 1,0 to 2,1 — overlaps bottom half of existing merge
    expect(canMerge(1, 0, 2, 1, merges)).toBe(false)
  })

  it('allows merge that does not overlap existing merge', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 2, colSpan: 2 },
    ]
    // Selection in different area
    expect(canMerge(3, 0, 4, 1, merges)).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// shiftMergesForRowInsert / Remove
// ---------------------------------------------------------------------------

describe('shiftMergesForRowInsert', () => {
  it('shifts merges below the insertion point down', () => {
    const merges: CellMerge[] = [
      { row: 2, col: 0, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForRowInsert(merges, 1)
    expect(result).toEqual([{ row: 3, col: 0, rowSpan: 1, colSpan: 1 }])
  })

  it('shifts merges at the insertion point down', () => {
    const merges: CellMerge[] = [
      { row: 1, col: 0, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForRowInsert(merges, 1)
    expect(result).toEqual([{ row: 2, col: 0, rowSpan: 1, colSpan: 1 }])
  })

  it('grows merges that span across the insertion point', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 3, colSpan: 1 }, // spans rows 0-2
    ]
    const result = shiftMergesForRowInsert(merges, 1) // insert at row 1
    expect(result).toEqual([{ row: 0, col: 0, rowSpan: 4, colSpan: 1 }])
  })

  it('leaves merges above the insertion point unchanged', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForRowInsert(merges, 2)
    expect(result).toEqual([{ row: 0, col: 0, rowSpan: 1, colSpan: 1 }])
  })

  it('handles empty merges array', () => {
    expect(shiftMergesForRowInsert([], 0)).toEqual([])
  })
})

describe('shiftMergesForRowRemove', () => {
  it('shifts merges below the removal point up', () => {
    const merges: CellMerge[] = [
      { row: 3, col: 0, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForRowRemove(merges, 1)
    expect(result).toEqual([{ row: 2, col: 0, rowSpan: 1, colSpan: 1 }])
  })

  it('drops single-row merges at the removed position', () => {
    const merges: CellMerge[] = [
      { row: 1, col: 0, rowSpan: 1, colSpan: 2 },
    ]
    const result = shiftMergesForRowRemove(merges, 1)
    expect(result).toEqual([])
  })

  it('shrinks merges that span across the removal point', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 3, colSpan: 1 },
    ]
    const result = shiftMergesForRowRemove(merges, 1)
    expect(result).toEqual([{ row: 0, col: 0, rowSpan: 2, colSpan: 1 }])
  })

  it('leaves merges above the removal point unchanged', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForRowRemove(merges, 2)
    expect(result).toEqual([{ row: 0, col: 0, rowSpan: 1, colSpan: 1 }])
  })

  it('drops merges that shrink to zero rowSpan', () => {
    // A 2-row merge starting at row 0, remove row 0 → shrinks to 1 row
    // Then remove row 0 again → the remaining 1-row merge at row 0 gets dropped
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForRowRemove(merges, 0)
    expect(result).toEqual([])
  })
})

// ---------------------------------------------------------------------------
// shiftMergesForColInsert / Remove
// ---------------------------------------------------------------------------

describe('shiftMergesForColInsert', () => {
  it('shifts merges to the right of insertion point', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 2, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForColInsert(merges, 1)
    expect(result).toEqual([{ row: 0, col: 3, rowSpan: 1, colSpan: 1 }])
  })

  it('grows merges that span across the insertion point', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 1, colSpan: 3 },
    ]
    const result = shiftMergesForColInsert(merges, 1)
    expect(result).toEqual([{ row: 0, col: 0, rowSpan: 1, colSpan: 4 }])
  })

  it('leaves merges to the left of insertion point unchanged', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForColInsert(merges, 2)
    expect(result).toEqual([{ row: 0, col: 0, rowSpan: 1, colSpan: 1 }])
  })
})

describe('shiftMergesForColRemove', () => {
  it('shifts merges to the right of removal point left', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 3, rowSpan: 1, colSpan: 1 },
    ]
    const result = shiftMergesForColRemove(merges, 1)
    expect(result).toEqual([{ row: 0, col: 2, rowSpan: 1, colSpan: 1 }])
  })

  it('drops single-column merges at the removed position', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 1, rowSpan: 2, colSpan: 1 },
    ]
    const result = shiftMergesForColRemove(merges, 1)
    expect(result).toEqual([])
  })

  it('shrinks merges that span across the removal point', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 1, colSpan: 3 },
    ]
    const result = shiftMergesForColRemove(merges, 1)
    expect(result).toEqual([{ row: 0, col: 0, rowSpan: 1, colSpan: 2 }])
  })
})

// ---------------------------------------------------------------------------
// normalizeSelection / expandSelectionForMerges
// ---------------------------------------------------------------------------

describe('normalizeSelection', () => {
  it('normalizes when start > end', () => {
    expect(normalizeSelection({ startRow: 3, startCol: 2, endRow: 1, endCol: 0 }))
      .toEqual({ startRow: 1, startCol: 0, endRow: 3, endCol: 2 })
  })

  it('leaves already normalized selections unchanged', () => {
    const sel = { startRow: 0, startCol: 0, endRow: 2, endCol: 2 }
    expect(normalizeSelection(sel)).toEqual(sel)
  })
})

describe('expandSelectionForMerges', () => {
  it('expands selection to include merge that partially overlaps', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 2, colSpan: 2 },
    ]
    // Selection only covers part of the merge
    const result = expandSelectionForMerges(
      { startRow: 1, startCol: 1, endRow: 2, endCol: 2 },
      merges,
    )
    expect(result).toEqual({ startRow: 0, startCol: 0, endRow: 2, endCol: 2 })
  })

  it('does not expand when no merges overlap', () => {
    const merges: CellMerge[] = [
      { row: 0, col: 0, rowSpan: 2, colSpan: 2 },
    ]
    const sel = { startRow: 3, startCol: 3, endRow: 4, endCol: 4 }
    expect(expandSelectionForMerges(sel, merges)).toEqual(sel)
  })

  it('handles chain expansion (expanding to include one merge reveals another)', () => {
    // Merge A at (1,0) spans 2 rows, 1 col → covers (1,0) and (2,0)
    // Merge B at (2,0) spans 1 row, 2 cols → covers (2,0) and (2,1)
    // Selection at (1,0)-(1,0):
    //   → Merge A overlaps → expand to (1,0)-(2,0)
    //   → Merge B at (2,0) now overlaps → expand to (1,0)-(2,1)
    const merges: CellMerge[] = [
      { row: 1, col: 0, rowSpan: 2, colSpan: 1 },
      { row: 2, col: 0, rowSpan: 1, colSpan: 2 },
    ]
    const result = expandSelectionForMerges(
      { startRow: 1, startCol: 0, endRow: 1, endCol: 0 },
      merges,
    )
    expect(result).toEqual({ startRow: 1, startCol: 0, endRow: 2, endCol: 1 })
  })

  it('handles empty merges array', () => {
    const sel = { startRow: 0, startCol: 0, endRow: 1, endCol: 1 }
    expect(expandSelectionForMerges(sel, [])).toEqual(sel)
  })
})
