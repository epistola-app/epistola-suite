import { describe, it, expect } from 'vitest'
import { extractFieldPaths } from './schema-paths.js'

describe('extractFieldPaths', () => {
  it('returns empty array for empty schema', () => {
    expect(extractFieldPaths({})).toEqual([])
  })

  it('returns empty array for schema without properties', () => {
    expect(extractFieldPaths({ type: 'object' })).toEqual([])
  })

  it('extracts flat properties', () => {
    const schema = {
      type: 'object',
      properties: {
        name: { type: 'string' },
        age: { type: 'number' },
        active: { type: 'boolean' },
      },
    }

    const paths = extractFieldPaths(schema)
    expect(paths).toEqual([
      { path: 'name', type: 'string' },
      { path: 'age', type: 'number' },
      { path: 'active', type: 'boolean' },
    ])
  })

  it('extracts nested object properties with dot notation', () => {
    const schema = {
      type: 'object',
      properties: {
        customer: {
          type: 'object',
          properties: {
            name: { type: 'string' },
            address: {
              type: 'object',
              properties: {
                city: { type: 'string' },
                zip: { type: 'string' },
              },
            },
          },
        },
      },
    }

    const paths = extractFieldPaths(schema)
    expect(paths).toEqual([
      { path: 'customer', type: 'object' },
      { path: 'customer.name', type: 'string' },
      { path: 'customer.address', type: 'object' },
      { path: 'customer.address.city', type: 'string' },
      { path: 'customer.address.zip', type: 'string' },
    ])
  })

  it('handles arrays with object items', () => {
    const schema = {
      type: 'object',
      properties: {
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              name: { type: 'string' },
              price: { type: 'number' },
            },
          },
        },
      },
    }

    const paths = extractFieldPaths(schema)
    expect(paths).toEqual([
      { path: 'items', type: 'array' },
      { path: 'items[].name', type: 'string' },
      { path: 'items[].price', type: 'number' },
    ])
  })

  it('handles arrays with scalar items (no recursion)', () => {
    const schema = {
      type: 'object',
      properties: {
        tags: {
          type: 'array',
          items: { type: 'string' },
        },
      },
    }

    const paths = extractFieldPaths(schema)
    expect(paths).toEqual([
      { path: 'tags', type: 'array' },
    ])
  })

  it('respects depth limit', () => {
    // Create a deeply nested schema (> 5 levels)
    const deep: Record<string, unknown> = {
      type: 'object',
      properties: {
        a: {
          type: 'object',
          properties: {
            b: {
              type: 'object',
              properties: {
                c: {
                  type: 'object',
                  properties: {
                    d: {
                      type: 'object',
                      properties: {
                        e: {
                          type: 'object',
                          properties: {
                            f: {
                              type: 'object',
                              properties: {
                                tooDeep: { type: 'string' },
                              },
                            },
                          },
                        },
                      },
                    },
                  },
                },
              },
            },
          },
        },
      },
    }

    const paths = extractFieldPaths(deep)
    const pathNames = paths.map(p => p.path)

    // Should include up to depth 5 but not beyond
    expect(pathNames).toContain('a')
    expect(pathNames).toContain('a.b')
    expect(pathNames).toContain('a.b.c')
    expect(pathNames).toContain('a.b.c.d')
    expect(pathNames).toContain('a.b.c.d.e')
    // f is at depth 6 - should be included as a leaf but not recursed into
    expect(pathNames).toContain('a.b.c.d.e.f')
    // tooDeep is at depth 7 - should NOT be included
    expect(pathNames).not.toContain('a.b.c.d.e.f.tooDeep')
  })

  it('handles missing type gracefully', () => {
    const schema = {
      type: 'object',
      properties: {
        mystery: {},
      },
    }

    const paths = extractFieldPaths(schema)
    expect(paths).toEqual([
      { path: 'mystery', type: 'unknown' },
    ])
  })

  it('skips null or non-object properties', () => {
    const schema = {
      type: 'object',
      properties: {
        valid: { type: 'string' },
        invalid: null,
      },
    }

    const paths = extractFieldPaths(schema)
    expect(paths).toEqual([
      { path: 'valid', type: 'string' },
    ])
  })
})
