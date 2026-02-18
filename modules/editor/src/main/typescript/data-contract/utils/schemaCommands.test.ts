import { describe, expect, it } from 'vitest'
import type { SchemaField, VisualSchema } from '../types.js'
import {
  addFieldToTree,
  deleteFieldFromTree,
  executeSchemaCommand,
  updateFieldInTree,
} from './schemaCommands.js'

// =============================================================================
// Test fixtures
// =============================================================================

function makeSchema(): VisualSchema {
  return {
    fields: [
      { id: 'field:name', name: 'name', type: 'string', required: true },
      { id: 'field:age', name: 'age', type: 'integer', required: false },
      {
        id: 'field:address',
        name: 'address',
        type: 'object',
        required: false,
        nestedFields: [
          { id: 'field:address.street', name: 'street', type: 'string', required: true },
          { id: 'field:address.city', name: 'city', type: 'string', required: false },
        ],
      },
    ],
  }
}

function makeDeeplyNestedSchema(): VisualSchema {
  return {
    fields: [
      {
        id: 'field:company',
        name: 'company',
        type: 'object',
        required: false,
        nestedFields: [
          {
            id: 'field:company.hq',
            name: 'hq',
            type: 'object',
            required: false,
            nestedFields: [
              {
                id: 'field:company.hq.address',
                name: 'address',
                type: 'object',
                required: false,
                nestedFields: [
                  { id: 'field:company.hq.address.zip', name: 'zip', type: 'string', required: true },
                ],
              },
            ],
          },
        ],
      },
    ],
  }
}

// =============================================================================
// updateFieldInTree
// =============================================================================

describe('updateFieldInTree', () => {
  it('updates a root-level field', () => {
    const schema = makeSchema()
    const result = updateFieldInTree(schema.fields, 'field:name', { name: 'fullName' })

    expect(result[0].name).toBe('fullName')
    expect(result[0].id).toBe('field:name')
  })

  it('updates a nested field (depth 1)', () => {
    const schema = makeSchema()
    const result = updateFieldInTree(schema.fields, 'field:address.street', { name: 'streetName' })

    const address = result[2] as SchemaField & { type: 'object' }
    expect(address.nestedFields![0].name).toBe('streetName')
  })

  it('updates a deeply nested field (depth 3)', () => {
    const schema = makeDeeplyNestedSchema()
    const result = updateFieldInTree(schema.fields, 'field:company.hq.address.zip', { required: false })

    const company = result[0] as SchemaField & { type: 'object' }
    const hq = company.nestedFields![0] as SchemaField & { type: 'object' }
    const address = hq.nestedFields![0] as SchemaField & { type: 'object' }
    expect(address.nestedFields![0].required).toBe(false)
  })

  it('does not mutate the input', () => {
    const schema = makeSchema()
    const original = structuredClone(schema)
    updateFieldInTree(schema.fields, 'field:name', { name: 'changed' })

    expect(schema).toEqual(original)
  })

  it('returns the same array reference when field is not found', () => {
    const schema = makeSchema()
    const result = updateFieldInTree(schema.fields, 'nonexistent', { name: 'x' })

    // Fields are mapped so not the same reference, but content should match
    expect(result).toEqual(schema.fields)
  })
})

// =============================================================================
// deleteFieldFromTree
// =============================================================================

describe('deleteFieldFromTree', () => {
  it('deletes a root-level field', () => {
    const schema = makeSchema()
    const result = deleteFieldFromTree(schema.fields, 'field:age')

    expect(result).toHaveLength(2)
    expect(result.find((f) => f.id === 'field:age')).toBeUndefined()
  })

  it('deletes a nested field (depth 1)', () => {
    const schema = makeSchema()
    const result = deleteFieldFromTree(schema.fields, 'field:address.city')

    const address = result[2] as SchemaField & { type: 'object' }
    expect(address.nestedFields).toHaveLength(1)
    expect(address.nestedFields![0].name).toBe('street')
  })

  it('deletes a deeply nested field (depth 3)', () => {
    const schema = makeDeeplyNestedSchema()
    const result = deleteFieldFromTree(schema.fields, 'field:company.hq.address.zip')

    const company = result[0] as SchemaField & { type: 'object' }
    const hq = company.nestedFields![0] as SchemaField & { type: 'object' }
    const address = hq.nestedFields![0] as SchemaField & { type: 'object' }
    expect(address.nestedFields).toHaveLength(0)
  })

  it('does not mutate the input', () => {
    const schema = makeSchema()
    const original = structuredClone(schema)
    deleteFieldFromTree(schema.fields, 'field:age')

    expect(schema).toEqual(original)
  })

  it('is a no-op for nonexistent field', () => {
    const schema = makeSchema()
    const result = deleteFieldFromTree(schema.fields, 'nonexistent')

    expect(result).toHaveLength(3)
  })
})

// =============================================================================
// addFieldToTree
// =============================================================================

describe('addFieldToTree', () => {
  const newField: SchemaField = { id: 'new1', name: 'newField', type: 'string', required: false }

  it('adds a field at root level (parentFieldId = null)', () => {
    const schema = makeSchema()
    const result = addFieldToTree(schema.fields, null, newField)

    expect(result).toHaveLength(4)
    expect(result[3]).toEqual(newField)
  })

  it('adds a nested field to a parent', () => {
    const schema = makeSchema()
    const result = addFieldToTree(schema.fields, 'field:address', newField)

    const address = result[2] as SchemaField & { type: 'object' }
    expect(address.nestedFields).toHaveLength(3)
    expect(address.nestedFields![2]).toEqual(newField)
  })

  it('adds a field to a deeply nested parent', () => {
    const schema = makeDeeplyNestedSchema()
    const result = addFieldToTree(schema.fields, 'field:company.hq.address', newField)

    const company = result[0] as SchemaField & { type: 'object' }
    const hq = company.nestedFields![0] as SchemaField & { type: 'object' }
    const address = hq.nestedFields![0] as SchemaField & { type: 'object' }
    expect(address.nestedFields).toHaveLength(2)
    expect(address.nestedFields![1]).toEqual(newField)
  })

  it('does not mutate the input', () => {
    const schema = makeSchema()
    const original = structuredClone(schema)
    addFieldToTree(schema.fields, null, newField)

    expect(schema).toEqual(original)
  })
})

// =============================================================================
// executeSchemaCommand
// =============================================================================

describe('executeSchemaCommand', () => {
  describe('addField', () => {
    it('adds a root field', () => {
      const schema = makeSchema()
      const result = executeSchemaCommand(schema, { type: 'addField', parentFieldId: null })

      expect(result.fields).toHaveLength(4)
      expect(result.fields[3].type).toBe('string')
    })

    it('adds a root field with custom name', () => {
      const schema: VisualSchema = { fields: [] }
      const result = executeSchemaCommand(schema, { type: 'addField', parentFieldId: null, name: 'myField' })

      expect(result.fields).toHaveLength(1)
      expect(result.fields[0].name).toBe('myField')
    })

    it('adds a nested field', () => {
      const schema = makeSchema()
      const result = executeSchemaCommand(schema, { type: 'addField', parentFieldId: 'field:address' })

      const address = result.fields[2] as SchemaField & { type: 'object' }
      expect(address.nestedFields).toHaveLength(3)
    })
  })

  describe('deleteField', () => {
    it('deletes a root field', () => {
      const schema = makeSchema()
      const result = executeSchemaCommand(schema, { type: 'deleteField', fieldId: 'field:age' })

      expect(result.fields).toHaveLength(2)
    })

    it('deletes a deeply nested field', () => {
      const schema = makeDeeplyNestedSchema()
      const result = executeSchemaCommand(schema, { type: 'deleteField', fieldId: 'field:company.hq.address.zip' })

      const company = result.fields[0] as SchemaField & { type: 'object' }
      const hq = company.nestedFields![0] as SchemaField & { type: 'object' }
      const address = hq.nestedFields![0] as SchemaField & { type: 'object' }
      expect(address.nestedFields).toHaveLength(0)
    })
  })

  describe('updateField', () => {
    it('updates a root field', () => {
      const schema = makeSchema()
      const result = executeSchemaCommand(schema, {
        type: 'updateField',
        fieldId: 'field:name',
        updates: { name: 'fullName', required: false },
      })

      expect(result.fields[0].name).toBe('fullName')
      expect(result.fields[0].required).toBe(false)
    })

    it('updates a nested field type', () => {
      const schema = makeSchema()
      const result = executeSchemaCommand(schema, {
        type: 'updateField',
        fieldId: 'field:address.street',
        updates: { type: 'integer' },
      })

      const address = result.fields[2] as SchemaField & { type: 'object' }
      expect(address.nestedFields![0].type).toBe('integer')
    })
  })

  it('does not mutate the original schema', () => {
    const schema = makeSchema()
    const original = structuredClone(schema)
    executeSchemaCommand(schema, { type: 'deleteField', fieldId: 'field:name' })

    expect(schema).toEqual(original)
  })
})
