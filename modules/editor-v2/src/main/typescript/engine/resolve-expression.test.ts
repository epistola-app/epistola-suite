import { describe, it, expect } from 'vitest'
import { evaluateExpression, formatResolvedValue } from './resolve-expression.js'

// ---------------------------------------------------------------------------
// evaluateExpression
// ---------------------------------------------------------------------------

describe('evaluateExpression', () => {
  const data = {
    customer: {
      name: 'John Doe',
      age: 30,
      active: true,
      address: {
        city: 'Amsterdam',
        zip: '1012AB',
      },
    },
    items: [
      { name: 'Widget', price: 10 },
      { name: 'Gadget', price: 25 },
      { name: 'Gizmo', price: 15 },
    ],
    first: 'Jane',
    last: 'Smith',
    tags: ['a', 'b', 'c'],
  }

  it('resolves a simple path', async () => {
    expect(await evaluateExpression('customer.name', data)).toBe('John Doe')
  })

  it('resolves a nested path', async () => {
    expect(await evaluateExpression('customer.address.city', data)).toBe('Amsterdam')
  })

  it('resolves array access', async () => {
    expect(await evaluateExpression('items[0].name', data)).toBe('Widget')
  })

  it('resolves array mapping', async () => {
    const result = await evaluateExpression('items.name', data)
    // JSONata returns an array-like object; spread to compare as plain array
    expect([...(result as string[])]).toEqual(['Widget', 'Gadget', 'Gizmo'])
  })

  it('resolves string concatenation', async () => {
    expect(await evaluateExpression('first & " " & last', data)).toBe('Jane Smith')
  })

  it('resolves aggregation ($sum)', async () => {
    expect(await evaluateExpression('$sum(items.price)', data)).toBe(50)
  })

  it('resolves conditionals', async () => {
    expect(await evaluateExpression('customer.active ? "Yes" : "No"', data)).toBe('Yes')
  })

  it('returns undefined for missing paths', async () => {
    expect(await evaluateExpression('customer.nonexistent', data)).toBeUndefined()
  })

  it('returns undefined for invalid syntax', async () => {
    expect(await evaluateExpression('{{invalid}}', data)).toBeUndefined()
  })

  it('returns undefined for empty expression', async () => {
    expect(await evaluateExpression('', data)).toBeUndefined()
  })

  it('returns undefined for whitespace-only expression', async () => {
    expect(await evaluateExpression('   ', data)).toBeUndefined()
  })

  it('resolves number values', async () => {
    expect(await evaluateExpression('customer.age', data)).toBe(30)
  })

  it('resolves boolean values', async () => {
    expect(await evaluateExpression('customer.active', data)).toBe(true)
  })

  it('resolves array count', async () => {
    expect(await evaluateExpression('$count(items)', data)).toBe(3)
  })
})

// ---------------------------------------------------------------------------
// formatResolvedValue
// ---------------------------------------------------------------------------

describe('formatResolvedValue', () => {
  it('formats a string', () => {
    expect(formatResolvedValue('Hello')).toBe('Hello')
  })

  it('formats a number', () => {
    expect(formatResolvedValue(42)).toBe('42')
  })

  it('formats zero', () => {
    expect(formatResolvedValue(0)).toBe('0')
  })

  it('formats a boolean true', () => {
    expect(formatResolvedValue(true)).toBe('true')
  })

  it('formats a boolean false', () => {
    expect(formatResolvedValue(false)).toBe('false')
  })

  it('returns undefined for null', () => {
    expect(formatResolvedValue(null)).toBeUndefined()
  })

  it('returns undefined for undefined', () => {
    expect(formatResolvedValue(undefined)).toBeUndefined()
  })

  it('returns undefined for an object', () => {
    expect(formatResolvedValue({ a: 1 })).toBeUndefined()
  })

  it('returns undefined for an array', () => {
    expect(formatResolvedValue([1, 2, 3])).toBeUndefined()
  })

  it('returns undefined for an empty string', () => {
    expect(formatResolvedValue('')).toBeUndefined()
  })

  it('formats a float', () => {
    expect(formatResolvedValue(3.14)).toBe('3.14')
  })
})
