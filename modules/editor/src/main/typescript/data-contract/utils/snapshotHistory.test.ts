import { describe, expect, it } from 'vitest'
import { SnapshotHistory } from './snapshotHistory.js'

interface TestState {
  value: number
  label: string
}

function makeState(value: number, label = 'test'): TestState {
  return { value, label }
}

describe('SnapshotHistory', () => {
  it('starts with canUndo=false and canRedo=false', () => {
    const history = new SnapshotHistory<TestState>()
    expect(history.canUndo).toBe(false)
    expect(history.canRedo).toBe(false)
  })

  it('push enables undo and clears redo', () => {
    const history = new SnapshotHistory<TestState>()
    history.push(makeState(1))
    expect(history.canUndo).toBe(true)
    expect(history.canRedo).toBe(false)
  })

  it('undo restores the pushed state', () => {
    const history = new SnapshotHistory<TestState>()
    const before = makeState(1, 'before')
    history.push(before)

    const current = makeState(2, 'after')
    const restored = history.undo(current)

    expect(restored).toEqual({ value: 1, label: 'before' })
    expect(history.canUndo).toBe(false)
    expect(history.canRedo).toBe(true)
  })

  it('redo restores the undone state', () => {
    const history = new SnapshotHistory<TestState>()
    const before = makeState(1)
    history.push(before)

    const after = makeState(2)
    const undone = history.undo(after)!
    const redone = history.redo(undone)

    expect(redone).toEqual({ value: 2, label: 'test' })
    expect(history.canUndo).toBe(true)
    expect(history.canRedo).toBe(false)
  })

  it('redo stack is cleared after a new push', () => {
    const history = new SnapshotHistory<TestState>()
    history.push(makeState(1))

    const after = makeState(2)
    history.undo(after) // creates redo entry
    expect(history.canRedo).toBe(true)

    history.push(makeState(3)) // new branch clears redo
    expect(history.canRedo).toBe(false)
  })

  it('multiple undo/redo cycle', () => {
    const history = new SnapshotHistory<TestState>()

    // Push state 1, then state 2
    history.push(makeState(1))
    history.push(makeState(2))

    const current = makeState(3)

    // Undo twice
    const undo1 = history.undo(current)!
    expect(undo1).toEqual(makeState(2))
    const undo2 = history.undo(undo1)!
    expect(undo2).toEqual(makeState(1))
    expect(history.canUndo).toBe(false)

    // Redo twice
    const redo1 = history.redo(undo2)!
    expect(redo1).toEqual(makeState(2))
    const redo2 = history.redo(redo1)!
    expect(redo2).toEqual(makeState(3))
    expect(history.canRedo).toBe(false)
  })

  it('undo returns null when stack is empty', () => {
    const history = new SnapshotHistory<TestState>()
    expect(history.undo(makeState(1))).toBeNull()
  })

  it('redo returns null when stack is empty', () => {
    const history = new SnapshotHistory<TestState>()
    expect(history.redo(makeState(1))).toBeNull()
  })

  it('clear resets both stacks', () => {
    const history = new SnapshotHistory<TestState>()
    history.push(makeState(1))
    const after = makeState(2)
    history.undo(after)

    expect(history.canRedo).toBe(true)

    history.clear()
    expect(history.canUndo).toBe(false)
    expect(history.canRedo).toBe(false)
  })

  it('snapshots are independent (no shared references)', () => {
    const history = new SnapshotHistory<TestState>()
    const original = makeState(1, 'original')
    history.push(original)

    // Mutate the original after pushing
    original.label = 'MUTATED'

    const restored = history.undo(makeState(2))!
    expect(restored.label).toBe('original')
  })
})
