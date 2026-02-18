import { describe, expect, it } from 'vitest'
import { validationPathToFormPath, buildFieldErrorMap, hasChildErrors } from './ExampleForm.js'
import type { SchemaValidationError } from '../utils/schemaValidation.js'

describe('validationPathToFormPath', () => {
  it('strips leading $. prefix', () => {
    expect(validationPathToFormPath('$.name')).toBe('name')
  })

  it('converts bracket notation to dot notation', () => {
    expect(validationPathToFormPath('$.items[0]')).toBe('items.0')
  })

  it('handles nested paths with arrays', () => {
    expect(validationPathToFormPath('$.users[0].email')).toBe('users.0.email')
  })

  it('handles deeply nested paths', () => {
    expect(validationPathToFormPath('$.a.b[1].c[2].d')).toBe('a.b.1.c.2.d')
  })

  it('handles simple root-level field', () => {
    expect(validationPathToFormPath('$.firstName')).toBe('firstName')
  })

  it('handles path without $. prefix gracefully', () => {
    expect(validationPathToFormPath('name')).toBe('name')
  })
})

describe('buildFieldErrorMap', () => {
  it('returns empty map for empty errors', () => {
    const map = buildFieldErrorMap([])
    expect(map.size).toBe(0)
  })

  it('maps validation paths to form paths', () => {
    const errors: SchemaValidationError[] = [
      { path: '$.name', message: 'is required' },
      { path: '$.age', message: 'must be integer' },
    ]
    const map = buildFieldErrorMap(errors)
    expect(map.get('name')).toBe('is required')
    expect(map.get('age')).toBe('must be integer')
  })

  it('handles array paths', () => {
    const errors: SchemaValidationError[] = [
      { path: '$.items[0]', message: 'must be string' },
    ]
    const map = buildFieldErrorMap(errors)
    expect(map.get('items.0')).toBe('must be string')
  })

  it('keeps first error per path (deduplicates)', () => {
    const errors: SchemaValidationError[] = [
      { path: '$.name', message: 'first error' },
      { path: '$.name', message: 'second error' },
    ]
    const map = buildFieldErrorMap(errors)
    expect(map.get('name')).toBe('first error')
  })

  it('handles nested object paths', () => {
    const errors: SchemaValidationError[] = [
      { path: '$.address.street', message: 'is required' },
      { path: '$.users[0].email', message: 'must be string' },
    ]
    const map = buildFieldErrorMap(errors)
    expect(map.get('address.street')).toBe('is required')
    expect(map.get('users.0.email')).toBe('must be string')
  })
})

describe('hasChildErrors', () => {
  it('returns false for empty errors map', () => {
    expect(hasChildErrors('address', new Map())).toBe(false)
  })

  it('returns true when path itself has an error', () => {
    const errors = new Map([['address', 'is required']])
    expect(hasChildErrors('address', errors)).toBe(true)
  })

  it('returns true when a child path has an error', () => {
    const errors = new Map([['address.street', 'is required']])
    expect(hasChildErrors('address', errors)).toBe(true)
  })

  it('returns true for deeply nested child errors', () => {
    const errors = new Map([['users.0.address.zip', 'must be string']])
    expect(hasChildErrors('users', errors)).toBe(true)
    expect(hasChildErrors('users.0', errors)).toBe(true)
    expect(hasChildErrors('users.0.address', errors)).toBe(true)
  })

  it('returns false when no matching paths', () => {
    const errors = new Map([['name', 'is required']])
    expect(hasChildErrors('address', errors)).toBe(false)
  })

  it('does not match partial path prefixes', () => {
    const errors = new Map([['addressExtra.street', 'is required']])
    expect(hasChildErrors('address', errors)).toBe(false)
  })
})
