import { describe, expect, it, vi } from 'vitest'
import { DataContractState } from './DataContractState.js'
import type { DataExample, JsonSchema, SaveCallbacks } from './types.js'

function createState(
  schema: JsonSchema | null = null,
  examples: DataExample[] = [],
  callbacks: SaveCallbacks = {},
) {
  return new DataContractState(schema, examples, callbacks)
}

const testSchema: JsonSchema = {
  type: 'object',
  properties: { name: { type: 'string' } },
}

const testExamples: DataExample[] = [
  { id: '1', name: 'Example 1', data: { name: 'John' } },
]

describe('DataContractState', () => {
  // -------------------------------------------------------------------------
  // Initial state
  // -------------------------------------------------------------------------

  describe('initial state', () => {
    it('starts clean (not dirty)', () => {
      const state = createState(testSchema, testExamples)

      expect(state.isDirty).toBe(false)
      expect(state.isSchemaDirty).toBe(false)
      expect(state.isExamplesDirty).toBe(false)
    })

    it('returns initial schema and examples', () => {
      const state = createState(testSchema, testExamples)

      expect(state.schema).toEqual(testSchema)
      expect(state.dataExamples).toEqual(testExamples)
    })

    it('deep-clones initial data (no shared references)', () => {
      const schema = structuredClone(testSchema)
      const examples = structuredClone(testExamples)
      const state = createState(schema, examples)

      // Mutating the original should not affect state
      schema.properties!.name.type = 'integer'
      examples[0].name = 'MUTATED'

      expect(state.schema!.properties!.name.type).toBe('string')
      expect(state.dataExamples[0].name).toBe('Example 1')
    })

    it('handles null schema', () => {
      const state = createState(null)

      expect(state.schema).toBeNull()
      expect(state.isDirty).toBe(false)
    })
  })

  // -------------------------------------------------------------------------
  // Schema mutations
  // -------------------------------------------------------------------------

  describe('schema mutations', () => {
    it('setDraftSchema marks schema dirty', () => {
      const state = createState(testSchema)
      const newSchema: JsonSchema = {
        type: 'object',
        properties: { age: { type: 'integer' } },
      }

      state.setDraftSchema(newSchema)

      expect(state.isSchemaDirty).toBe(true)
      expect(state.isDirty).toBe(true)
      expect(state.schema).toEqual(newSchema)
    })

    it('setting schema back to original clears dirty', () => {
      const state = createState(testSchema)

      state.setDraftSchema({ type: 'object', properties: { age: { type: 'integer' } } })
      expect(state.isSchemaDirty).toBe(true)

      state.setDraftSchema(structuredClone(testSchema))
      expect(state.isSchemaDirty).toBe(false)
    })

    it('fires change event on schema mutation', () => {
      const state = createState(testSchema)
      const listener = vi.fn()
      state.addEventListener('change', listener)

      state.setDraftSchema(null)

      expect(listener).toHaveBeenCalledTimes(1)
    })
  })

  // -------------------------------------------------------------------------
  // Example mutations
  // -------------------------------------------------------------------------

  describe('example mutations', () => {
    it('addDraftExample appends and marks dirty', () => {
      const state = createState(testSchema, testExamples)
      const newExample: DataExample = { id: '2', name: 'Example 2', data: { name: 'Jane' } }

      state.addDraftExample(newExample)

      expect(state.dataExamples).toHaveLength(2)
      expect(state.dataExamples[1]).toEqual(newExample)
      expect(state.isExamplesDirty).toBe(true)
    })

    it('updateDraftExample modifies specific example', () => {
      const state = createState(testSchema, testExamples)

      state.updateDraftExample('1', { name: 'Updated Name' })

      expect(state.dataExamples[0].name).toBe('Updated Name')
      expect(state.isExamplesDirty).toBe(true)
    })

    it('updateDraftExample does nothing for unknown id', () => {
      const state = createState(testSchema, testExamples)

      state.updateDraftExample('unknown', { name: 'Updated' })

      expect(state.dataExamples).toEqual(testExamples)
    })

    it('deleteDraftExample removes specific example', () => {
      const state = createState(testSchema, testExamples)

      state.deleteDraftExample('1')

      expect(state.dataExamples).toHaveLength(0)
      expect(state.isExamplesDirty).toBe(true)
    })

    it('setDraftExamples replaces all examples', () => {
      const state = createState(testSchema, testExamples)
      const newExamples: DataExample[] = [
        { id: '3', name: 'New', data: { name: 'X' } },
      ]

      state.setDraftExamples(newExamples)

      expect(state.dataExamples).toEqual(newExamples)
      expect(state.isExamplesDirty).toBe(true)
    })

    it('fires change event on example mutations', () => {
      const state = createState(testSchema, testExamples)
      const listener = vi.fn()
      state.addEventListener('change', listener)

      state.addDraftExample({ id: '2', name: 'Ex', data: {} })
      state.updateDraftExample('2', { name: 'Up' })
      state.deleteDraftExample('2')

      expect(listener).toHaveBeenCalledTimes(3)
    })
  })

  // -------------------------------------------------------------------------
  // Save schema
  // -------------------------------------------------------------------------

  describe('saveSchema', () => {
    it('calls onSaveSchema callback and commits on success', async () => {
      const onSaveSchema = vi.fn().mockResolvedValue({ success: true })
      const state = createState(testSchema, [], { onSaveSchema })

      const newSchema: JsonSchema = {
        type: 'object',
        properties: { age: { type: 'integer' } },
      }
      state.setDraftSchema(newSchema)
      expect(state.isSchemaDirty).toBe(true)

      const result = await state.saveSchema()

      expect(result.success).toBe(true)
      expect(onSaveSchema).toHaveBeenCalledWith(newSchema, false)
      expect(state.isSchemaDirty).toBe(false)
    })

    it('passes forceUpdate flag', async () => {
      const onSaveSchema = vi.fn().mockResolvedValue({ success: true })
      const state = createState(testSchema, [], { onSaveSchema })
      state.setDraftSchema(null)

      await state.saveSchema(true)

      expect(onSaveSchema).toHaveBeenCalledWith(null, true)
    })

    it('does not commit on failure', async () => {
      const onSaveSchema = vi.fn().mockResolvedValue({ success: false, error: 'Bad schema' })
      const state = createState(testSchema, [], { onSaveSchema })
      state.setDraftSchema(null)

      const result = await state.saveSchema()

      expect(result.success).toBe(false)
      expect(result.error).toBe('Bad schema')
      expect(state.isSchemaDirty).toBe(true)
    })

    it('handles exceptions', async () => {
      const onSaveSchema = vi.fn().mockRejectedValue(new Error('Network error'))
      const state = createState(testSchema, [], { onSaveSchema })
      state.setDraftSchema(null)

      const result = await state.saveSchema()

      expect(result.success).toBe(false)
      expect(result.error).toBe('Network error')
    })

    it('commits locally when no callback', async () => {
      const state = createState(testSchema)
      state.setDraftSchema(null)
      expect(state.isSchemaDirty).toBe(true)

      const result = await state.saveSchema()

      expect(result.success).toBe(true)
      expect(state.isSchemaDirty).toBe(false)
    })
  })

  // -------------------------------------------------------------------------
  // Save examples
  // -------------------------------------------------------------------------

  describe('saveExamples', () => {
    it('calls onSaveDataExamples and commits on success', async () => {
      const onSaveDataExamples = vi.fn().mockResolvedValue({ success: true })
      const state = createState(testSchema, testExamples, { onSaveDataExamples })

      state.addDraftExample({ id: '2', name: 'New', data: {} })
      const result = await state.saveExamples()

      expect(result.success).toBe(true)
      expect(onSaveDataExamples).toHaveBeenCalledWith(state.dataExamples)
      expect(state.isExamplesDirty).toBe(false)
    })

    it('can save specific examples list', async () => {
      const onSaveDataExamples = vi.fn().mockResolvedValue({ success: true })
      const state = createState(testSchema, testExamples, { onSaveDataExamples })

      const customExamples = [{ id: '99', name: 'Custom', data: { x: 1 } }]
      await state.saveExamples(customExamples)

      expect(onSaveDataExamples).toHaveBeenCalledWith(customExamples)
    })

    it('handles exceptions', async () => {
      const onSaveDataExamples = vi.fn().mockRejectedValue(new Error('Fail'))
      const state = createState(testSchema, testExamples, { onSaveDataExamples })
      state.addDraftExample({ id: '2', name: 'New', data: {} })

      const result = await state.saveExamples()

      expect(result.success).toBe(false)
      expect(result.error).toBe('Fail')
    })
  })

  // -------------------------------------------------------------------------
  // Save single example
  // -------------------------------------------------------------------------

  describe('saveSingleExample', () => {
    it('calls onUpdateDataExample and commits on success', async () => {
      const returnedExample = { id: '1', name: 'Updated', data: { name: 'Jane' } }
      const onUpdateDataExample = vi.fn().mockResolvedValue({
        success: true,
        example: returnedExample,
      })
      const state = createState(testSchema, testExamples, { onUpdateDataExample })

      const result = await state.saveSingleExample('1', { name: 'Updated', data: { name: 'Jane' } })

      expect(result.success).toBe(true)
      expect(state.dataExamples[0]).toEqual(returnedExample)
    })

    it('handles exceptions', async () => {
      const onUpdateDataExample = vi.fn().mockRejectedValue(new Error('Oops'))
      const state = createState(testSchema, testExamples, { onUpdateDataExample })

      const result = await state.saveSingleExample('1', { name: 'X' })

      expect(result.success).toBe(false)
      expect(result.errors?._[0].message).toBe('Oops')
    })
  })

  // -------------------------------------------------------------------------
  // Delete single example
  // -------------------------------------------------------------------------

  describe('deleteSingleExample', () => {
    it('calls onDeleteDataExample and removes on success', async () => {
      const onDeleteDataExample = vi.fn().mockResolvedValue({ success: true })
      const state = createState(testSchema, testExamples, { onDeleteDataExample })

      const result = await state.deleteSingleExample('1')

      expect(result.success).toBe(true)
      expect(state.dataExamples).toHaveLength(0)
    })

    it('does not remove on failure', async () => {
      const onDeleteDataExample = vi.fn().mockResolvedValue({ success: false })
      const state = createState(testSchema, testExamples, { onDeleteDataExample })

      await state.deleteSingleExample('1')

      expect(state.dataExamples).toHaveLength(1)
    })

    it('handles exceptions', async () => {
      const onDeleteDataExample = vi.fn().mockRejectedValue(new Error('Fail'))
      const state = createState(testSchema, testExamples, { onDeleteDataExample })

      const result = await state.deleteSingleExample('1')

      expect(result.success).toBe(false)
      expect(state.dataExamples).toHaveLength(1)
    })
  })

  // -------------------------------------------------------------------------
  // Discard
  // -------------------------------------------------------------------------

  describe('discardDraft', () => {
    it('resets schema and examples to committed state', () => {
      const state = createState(testSchema, testExamples)

      state.setDraftSchema(null)
      state.addDraftExample({ id: '2', name: 'New', data: {} })
      expect(state.isDirty).toBe(true)

      state.discardDraft()

      expect(state.isDirty).toBe(false)
      expect(state.schema).toEqual(testSchema)
      expect(state.dataExamples).toEqual(testExamples)
    })

    it('fires change event', () => {
      const state = createState(testSchema, testExamples)
      state.setDraftSchema(null)

      const listener = vi.fn()
      state.addEventListener('change', listener)
      state.discardDraft()

      expect(listener).toHaveBeenCalledTimes(1)
    })
  })
})
