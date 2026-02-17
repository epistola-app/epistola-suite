import { describe, expect, it } from 'vitest'
import {
  applyFieldUpdate,
  createEmptyField,
  FIELD_TYPE_LABELS,
  generateSchemaFromData,
  getSchemaFieldPaths,
  jsonSchemaToVisualSchema,
  visualSchemaToJsonSchema,
} from './schemaUtils.js'
import type { JsonSchema, SchemaField, VisualSchema } from '../types.js'

describe('visualSchemaToJsonSchema', () => {
  it('converts empty visual schema', () => {
    const visual: VisualSchema = { fields: [] }
    const result = visualSchemaToJsonSchema(visual)

    expect(result.$schema).toBe('http://json-schema.org/draft-07/schema#')
    expect(result.type).toBe('object')
    expect(result.properties).toEqual({})
    expect(result.required).toBeUndefined()
  })

  it('converts primitive fields', () => {
    const visual: VisualSchema = {
      fields: [
        { id: '1', name: 'name', type: 'string', required: true },
        { id: '2', name: 'age', type: 'integer', required: false },
        { id: '3', name: 'score', type: 'number', required: true, description: 'User score' },
        { id: '4', name: 'active', type: 'boolean', required: false },
      ],
    }
    const result = visualSchemaToJsonSchema(visual)

    expect(result.properties?.name).toEqual({ type: 'string' })
    expect(result.properties?.age).toEqual({ type: 'integer' })
    expect(result.properties?.score).toEqual({ type: 'number', description: 'User score' })
    expect(result.properties?.active).toEqual({ type: 'boolean' })
    expect(result.required).toEqual(['name', 'score'])
  })

  it('converts array field with primitive items', () => {
    const visual: VisualSchema = {
      fields: [{ id: '1', name: 'tags', type: 'array', arrayItemType: 'string', required: false }],
    }
    const result = visualSchemaToJsonSchema(visual)

    expect(result.properties?.tags).toEqual({
      type: 'array',
      items: { type: 'string' },
    })
  })

  it('converts array field with object items', () => {
    const visual: VisualSchema = {
      fields: [
        {
          id: '1',
          name: 'items',
          type: 'array',
          arrayItemType: 'object',
          required: true,
          nestedFields: [
            { id: 'n1', name: 'name', type: 'string', required: true },
            { id: 'n2', name: 'price', type: 'number', required: false },
          ],
        },
      ],
    }
    const result = visualSchemaToJsonSchema(visual)

    expect(result.properties?.items?.type).toBe('array')
    expect(result.properties?.items?.items?.type).toBe('object')
    expect(result.properties?.items?.items?.properties?.name).toEqual({ type: 'string' })
    expect(result.properties?.items?.items?.properties?.price).toEqual({ type: 'number' })
    expect(result.properties?.items?.items?.required).toEqual(['name'])
    expect(result.required).toEqual(['items'])
  })

  it('converts object field with nested fields', () => {
    const visual: VisualSchema = {
      fields: [
        {
          id: '1',
          name: 'address',
          type: 'object',
          required: false,
          nestedFields: [
            { id: 'n1', name: 'street', type: 'string', required: true },
            { id: 'n2', name: 'city', type: 'string', required: true },
          ],
        },
      ],
    }
    const result = visualSchemaToJsonSchema(visual)

    expect(result.properties?.address?.type).toBe('object')
    expect(result.properties?.address?.properties?.street).toEqual({ type: 'string' })
    expect(result.properties?.address?.properties?.city).toEqual({ type: 'string' })
    expect(result.properties?.address?.required).toEqual(['street', 'city'])
  })
})

describe('jsonSchemaToVisualSchema', () => {
  it('converts null/undefined to empty schema', () => {
    expect(jsonSchemaToVisualSchema(null)).toEqual({ fields: [] })
    expect(jsonSchemaToVisualSchema(undefined as unknown as JsonSchema)).toEqual({ fields: [] })
  })

  it('converts non-object schema to empty', () => {
    const schema = { type: 'array' } as unknown as JsonSchema
    expect(jsonSchemaToVisualSchema(schema)).toEqual({ fields: [] })
  })

  it('converts primitive properties', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        age: { type: 'integer', description: 'User age' },
      },
      required: ['name'],
    }
    const result = jsonSchemaToVisualSchema(schema)

    expect(result.fields).toHaveLength(2)
    expect(result.fields.find((f) => f.name === 'name')).toMatchObject({
      name: 'name',
      type: 'string',
      required: true,
    })
    expect(result.fields.find((f) => f.name === 'age')).toMatchObject({
      name: 'age',
      type: 'integer',
      required: false,
      description: 'User age',
    })
  })

  it('converts array property', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        tags: { type: 'array', items: { type: 'string' } },
      },
    }
    const result = jsonSchemaToVisualSchema(schema)
    const tagsField = result.fields[0]

    expect(tagsField.type).toBe('array')
    if (tagsField.type === 'array') {
      expect(tagsField.arrayItemType).toBe('string')
    }
  })

  it('converts array of objects', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              id: { type: 'integer' },
            },
            required: ['id'],
          },
        },
      },
    }
    const result = jsonSchemaToVisualSchema(schema)
    const itemsField = result.fields[0]

    expect(itemsField.type).toBe('array')
    if (itemsField.type === 'array') {
      expect(itemsField.arrayItemType).toBe('object')
      expect(itemsField.nestedFields).toHaveLength(1)
      expect(itemsField.nestedFields?.[0].name).toBe('id')
      expect(itemsField.nestedFields?.[0].required).toBe(true)
    }
  })

  it('handles union types (picks first)', () => {
    const schema: JsonSchema = {
      type: 'object',
      properties: {
        // Our schema only supports standard types, not "null"
        value: { type: ['string', 'integer'] },
      },
    }
    const result = jsonSchemaToVisualSchema(schema)

    expect(result.fields[0].type).toBe('string')
  })
})

describe('generateSchemaFromData', () => {
  it('generates schema from simple object', () => {
    const data = { name: 'John', age: 30, active: true }
    const result = generateSchemaFromData(data)

    expect(result.fields).toHaveLength(3)
    expect(result.fields.find((f) => f.name === 'name')?.type).toBe('string')
    expect(result.fields.find((f) => f.name === 'age')?.type).toBe('integer')
    expect(result.fields.find((f) => f.name === 'active')?.type).toBe('boolean')
    // All fields should default to optional
    expect(result.fields.every((f) => f.required === false)).toBe(true)
  })

  it('infers number vs integer correctly', () => {
    const data = { wholeNumber: 42, decimal: 3.14 }
    const result = generateSchemaFromData(data)

    expect(result.fields.find((f) => f.name === 'wholeNumber')?.type).toBe('integer')
    expect(result.fields.find((f) => f.name === 'decimal')?.type).toBe('number')
  })

  it('generates schema for arrays', () => {
    const data = { tags: ['a', 'b'], counts: [1, 2, 3] }
    const result = generateSchemaFromData(data)

    const tagsField = result.fields.find((f) => f.name === 'tags')
    const countsField = result.fields.find((f) => f.name === 'counts')

    expect(tagsField?.type).toBe('array')
    expect(countsField?.type).toBe('array')
    if (tagsField?.type === 'array') expect(tagsField.arrayItemType).toBe('string')
    if (countsField?.type === 'array') expect(countsField.arrayItemType).toBe('integer')
  })

  it('generates schema for array of objects', () => {
    const data = {
      items: [
        { name: 'Item 1', price: 100 },
        { name: 'Item 2', price: 200 },
      ],
    }
    const result = generateSchemaFromData(data)
    const itemsField = result.fields[0]

    expect(itemsField.type).toBe('array')
    if (itemsField.type === 'array') {
      expect(itemsField.arrayItemType).toBe('object')
      expect(itemsField.nestedFields).toHaveLength(2)
    }
  })

  it('generates schema for nested objects', () => {
    const data = {
      user: {
        name: 'John',
        address: { city: 'NYC' },
      },
    }
    const result = generateSchemaFromData(data)
    const userField = result.fields[0]

    expect(userField.type).toBe('object')
    if (userField.type === 'object') {
      expect(userField.nestedFields).toHaveLength(2)
    }
  })

  it('handles null values as string', () => {
    const data = { nullable: null }
    const result = generateSchemaFromData(data)

    expect(result.fields[0].type).toBe('string')
  })

  it('handles empty arrays', () => {
    const data = { empty: [] }
    const result = generateSchemaFromData(data)
    const emptyField = result.fields[0]

    expect(emptyField.type).toBe('array')
    if (emptyField.type === 'array') {
      expect(emptyField.arrayItemType).toBe('string') // Default for empty
    }
  })
})

describe('createEmptyField', () => {
  it('creates field with default values', () => {
    const field = createEmptyField()

    expect(field.id).toBeDefined()
    expect(field.name).toBe('newField')
    expect(field.type).toBe('string')
    expect(field.required).toBe(false)
  })

  it('creates field with custom name', () => {
    const field = createEmptyField('customField')

    expect(field.name).toBe('customField')
  })

  it('generates unique IDs', () => {
    const field1 = createEmptyField()
    const field2 = createEmptyField()

    expect(field1.id).not.toBe(field2.id)
  })
})

describe('applyFieldUpdate', () => {
  it('updates primitive field name', () => {
    const field: SchemaField = { id: '1', name: 'old', type: 'string', required: false }
    const result = applyFieldUpdate(field, { name: 'new' })

    expect(result.name).toBe('new')
    expect(result.type).toBe('string')
    expect(result.id).toBe('1')
  })

  it('updates required flag', () => {
    const field: SchemaField = { id: '1', name: 'field', type: 'string', required: false }
    const result = applyFieldUpdate(field, { required: true })

    expect(result.required).toBe(true)
  })

  it('changes primitive to array type', () => {
    const field: SchemaField = { id: '1', name: 'field', type: 'string', required: false }
    const result = applyFieldUpdate(field, { type: 'array', arrayItemType: 'number' })

    expect(result.type).toBe('array')
    if (result.type === 'array') {
      expect(result.arrayItemType).toBe('number')
    }
  })

  it('changes primitive to object type', () => {
    const field: SchemaField = { id: '1', name: 'field', type: 'string', required: false }
    const result = applyFieldUpdate(field, { type: 'object' })

    expect(result.type).toBe('object')
  })

  it('changes array to primitive type', () => {
    const field: SchemaField = {
      id: '1',
      name: 'field',
      type: 'array',
      arrayItemType: 'string',
      required: false,
    }
    const result = applyFieldUpdate(field, { type: 'integer' })

    expect(result.type).toBe('integer')
    expect('arrayItemType' in result).toBe(false)
  })

  it('updates array item type', () => {
    const field: SchemaField = {
      id: '1',
      name: 'field',
      type: 'array',
      arrayItemType: 'string',
      required: false,
    }
    const result = applyFieldUpdate(field, { arrayItemType: 'object' })

    expect(result.type).toBe('array')
    if (result.type === 'array') {
      expect(result.arrayItemType).toBe('object')
    }
  })

  it('updates nested fields', () => {
    const field: SchemaField = {
      id: '1',
      name: 'obj',
      type: 'object',
      required: false,
      nestedFields: [],
    }
    const newNested: SchemaField[] = [{ id: 'n1', name: 'child', type: 'string', required: true }]
    const result = applyFieldUpdate(field, { nestedFields: newNested })

    expect(result.type).toBe('object')
    if (result.type === 'object') {
      expect(result.nestedFields).toHaveLength(1)
      expect(result.nestedFields?.[0].name).toBe('child')
    }
  })

  it('preserves existing nested fields when updating other properties', () => {
    const nested: SchemaField[] = [{ id: 'n1', name: 'child', type: 'string', required: true }]
    const field: SchemaField = {
      id: '1',
      name: 'obj',
      type: 'object',
      required: false,
      nestedFields: nested,
    }
    const result = applyFieldUpdate(field, { name: 'newName' })

    expect(result.type).toBe('object')
    if (result.type === 'object') {
      expect(result.nestedFields).toEqual(nested)
    }
  })

  it('defaults arrayItemType to string when changing to array', () => {
    const field: SchemaField = { id: '1', name: 'field', type: 'string', required: false }
    const result = applyFieldUpdate(field, { type: 'array' })

    expect(result.type).toBe('array')
    if (result.type === 'array') {
      expect(result.arrayItemType).toBe('string')
    }
  })
})

describe('getSchemaFieldPaths', () => {
  it('returns empty set for empty schema', () => {
    const schema: VisualSchema = { fields: [] }
    const paths = getSchemaFieldPaths(schema)

    expect(paths.size).toBe(0)
  })

  it('returns paths for primitive fields', () => {
    const schema: VisualSchema = {
      fields: [
        { id: '1', name: 'name', type: 'string', required: true },
        { id: '2', name: 'age', type: 'integer', required: false },
      ],
    }
    const paths = getSchemaFieldPaths(schema)

    expect(paths.has('name')).toBe(true)
    expect(paths.has('age')).toBe(true)
    expect(paths.size).toBe(2)
  })

  it('returns paths for nested object fields', () => {
    const schema: VisualSchema = {
      fields: [
        {
          id: '1',
          name: 'address',
          type: 'object',
          required: false,
          nestedFields: [
            { id: 'n1', name: 'street', type: 'string', required: true },
            { id: 'n2', name: 'city', type: 'string', required: true },
          ],
        },
      ],
    }
    const paths = getSchemaFieldPaths(schema)

    expect(paths.has('address')).toBe(true)
    expect(paths.has('address.street')).toBe(true)
    expect(paths.has('address.city')).toBe(true)
  })

  it('returns paths for array fields with bracket notation', () => {
    const schema: VisualSchema = {
      fields: [
        {
          id: '1',
          name: 'items',
          type: 'array',
          arrayItemType: 'object',
          required: false,
          nestedFields: [
            { id: 'n1', name: 'name', type: 'string', required: true },
            { id: 'n2', name: 'price', type: 'number', required: true },
          ],
        },
      ],
    }
    const paths = getSchemaFieldPaths(schema)

    expect(paths.has('items')).toBe(true)
    expect(paths.has('items[]')).toBe(true)
    expect(paths.has('items[].name')).toBe(true)
    expect(paths.has('items[].price')).toBe(true)
  })
})

describe('FIELD_TYPE_LABELS', () => {
  it('has labels for all types', () => {
    expect(FIELD_TYPE_LABELS.string).toBe('Text')
    expect(FIELD_TYPE_LABELS.number).toBe('Number')
    expect(FIELD_TYPE_LABELS.integer).toBe('Integer')
    expect(FIELD_TYPE_LABELS.boolean).toBe('Yes/No')
    expect(FIELD_TYPE_LABELS.array).toBe('List')
    expect(FIELD_TYPE_LABELS.object).toBe('Object')
  })
})
