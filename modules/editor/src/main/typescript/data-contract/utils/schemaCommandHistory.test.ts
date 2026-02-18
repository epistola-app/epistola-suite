import { describe, expect, it } from 'vitest'
import type { VisualSchema } from '../types.js'
import { SchemaCommandHistory } from './schemaCommandHistory.js'

function makeSchema(): VisualSchema {
  return {
    fields: [
      { id: 'field:name', name: 'name', type: 'string', required: true },
      { id: 'field:age', name: 'age', type: 'integer', required: false },
    ],
  }
}

describe('SchemaCommandHistory', () => {
  it('starts with canUndo=false and canRedo=false', () => {
    const history = new SchemaCommandHistory()
    expect(history.canUndo).toBe(false)
    expect(history.canRedo).toBe(false)
  })

  it('execute applies a command and enables undo', () => {
    const history = new SchemaCommandHistory()
    const initial = makeSchema()

    const result = history.execute(
      { type: 'addField', parentFieldId: null, name: 'email' },
      initial,
    )

    expect(result.fields).toHaveLength(3)
    expect(result.fields[2].name).toBe('email')
    expect(history.canUndo).toBe(true)
    expect(history.canRedo).toBe(false)
  })

  it('undo restores previous state', () => {
    const history = new SchemaCommandHistory()
    const initial = makeSchema()

    const afterAdd = history.execute(
      { type: 'addField', parentFieldId: null, name: 'email' },
      initial,
    )
    expect(afterAdd.fields).toHaveLength(3)

    const undone = history.undo(afterAdd)
    expect(undone).not.toBeNull()
    expect(undone!.fields).toHaveLength(2)
    expect(history.canUndo).toBe(false)
    expect(history.canRedo).toBe(true)
  })

  it('redo restores undone state', () => {
    const history = new SchemaCommandHistory()
    const initial = makeSchema()

    const afterAdd = history.execute(
      { type: 'addField', parentFieldId: null, name: 'email' },
      initial,
    )
    const undone = history.undo(afterAdd)!
    const redone = history.redo(undone)

    expect(redone).not.toBeNull()
    expect(redone!.fields).toHaveLength(3)
    expect(redone!.fields[2].name).toBe('email')
    expect(history.canUndo).toBe(true)
    expect(history.canRedo).toBe(false)
  })

  it('redo stack is cleared after a new command', () => {
    const history = new SchemaCommandHistory()
    const initial = makeSchema()

    const afterFirst = history.execute(
      { type: 'addField', parentFieldId: null, name: 'email' },
      initial,
    )
    const undone = history.undo(afterFirst)!
    expect(history.canRedo).toBe(true)

    // New command should clear redo
    history.execute(
      { type: 'addField', parentFieldId: null, name: 'phone' },
      undone,
    )
    expect(history.canRedo).toBe(false)
  })

  it('multiple undo/redo cycle', () => {
    const history = new SchemaCommandHistory()
    const initial = makeSchema()

    const after1 = history.execute(
      { type: 'updateField', fieldId: 'field:name', updates: { name: 'firstName' } },
      initial,
    )
    const after2 = history.execute(
      { type: 'updateField', fieldId: 'field:age', updates: { required: true } },
      after1,
    )

    expect(after2.fields[0].name).toBe('firstName')
    expect(after2.fields[1].required).toBe(true)

    // Undo second command
    const undo1 = history.undo(after2)!
    expect(undo1.fields[0].name).toBe('firstName')
    expect(undo1.fields[1].required).toBe(false)

    // Undo first command
    const undo2 = history.undo(undo1)!
    expect(undo2.fields[0].name).toBe('name')
    expect(undo2.fields[1].required).toBe(false)

    // Redo both
    const redo1 = history.redo(undo2)!
    expect(redo1.fields[0].name).toBe('firstName')
    const redo2 = history.redo(redo1)!
    expect(redo2.fields[1].required).toBe(true)
  })

  it('undo returns null when stack is empty', () => {
    const history = new SchemaCommandHistory()
    const result = history.undo(makeSchema())
    expect(result).toBeNull()
  })

  it('redo returns null when stack is empty', () => {
    const history = new SchemaCommandHistory()
    const result = history.redo(makeSchema())
    expect(result).toBeNull()
  })

  it('clear resets both stacks', () => {
    const history = new SchemaCommandHistory()
    const initial = makeSchema()

    const after1 = history.execute(
      { type: 'addField', parentFieldId: null, name: 'email' },
      initial,
    )
    history.undo(after1)

    expect(history.canUndo).toBe(false)
    expect(history.canRedo).toBe(true)

    history.clear()
    expect(history.canUndo).toBe(false)
    expect(history.canRedo).toBe(false)
  })

  it('snapshots are independent (no shared references)', () => {
    const history = new SchemaCommandHistory()
    const initial = makeSchema()

    const afterAdd = history.execute(
      { type: 'addField', parentFieldId: null, name: 'email' },
      initial,
    )

    // Mutate the result (simulating what shouldn't happen but verifies independence)
    afterAdd.fields[0].name = 'MUTATED'

    const undone = history.undo(afterAdd)!
    // The undo snapshot should have the original name, not the mutation
    expect(undone.fields[0].name).toBe('name')
  })
})
