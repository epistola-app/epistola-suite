import { describe, it, expect } from 'vitest'
import {
  evaluateExpression,
  formatResolvedValue,
  tryEvaluateExpression,
  formatForPreview,
  isValidExpression,
  validateArrayResult,
  validateBooleanResult,
} from './resolve-expression.js'

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

// ---------------------------------------------------------------------------
// tryEvaluateExpression
// ---------------------------------------------------------------------------

describe('tryEvaluateExpression', () => {
  const data = {
    customer: { name: 'John Doe', age: 30 },
    items: [
      { name: 'Widget', price: 10 },
      { name: 'Gadget', price: 25 },
    ],
  }

  it('returns ok with value for a valid path', async () => {
    const result = await tryEvaluateExpression('customer.name', data)
    expect(result).toEqual({ ok: true, value: 'John Doe' })
  })

  it('returns ok with undefined for a missing path', async () => {
    const result = await tryEvaluateExpression('customer.nonexistent', data)
    expect(result).toEqual({ ok: true, value: undefined })
  })

  it('returns ok with number for aggregation', async () => {
    const result = await tryEvaluateExpression('$sum(items.price)', data)
    expect(result).toEqual({ ok: true, value: 35 })
  })

  it('returns error for syntax error', async () => {
    const result = await tryEvaluateExpression('{{broken', data)
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toBeTruthy()
    }
  })

  it('returns error for empty expression', async () => {
    const result = await tryEvaluateExpression('', data)
    expect(result).toEqual({ ok: false, error: 'Expression is empty' })
  })

  it('returns error for whitespace-only expression', async () => {
    const result = await tryEvaluateExpression('   ', data)
    expect(result).toEqual({ ok: false, error: 'Expression is empty' })
  })

  it('returns ok with boolean value', async () => {
    const result = await tryEvaluateExpression('customer.age > 18', data)
    expect(result).toEqual({ ok: true, value: true })
  })
})

// ---------------------------------------------------------------------------
// formatForPreview
// ---------------------------------------------------------------------------

describe('formatForPreview', () => {
  it('formats a string', () => {
    expect(formatForPreview('Hello')).toBe('Hello')
  })

  it('formats an empty string as "(empty string)"', () => {
    expect(formatForPreview('')).toBe('(empty string)')
  })

  it('formats undefined as "undefined"', () => {
    expect(formatForPreview(undefined)).toBe('undefined')
  })

  it('formats null as "null"', () => {
    expect(formatForPreview(null)).toBe('null')
  })

  it('formats a number', () => {
    expect(formatForPreview(42)).toBe('42')
  })

  it('formats a boolean', () => {
    expect(formatForPreview(true)).toBe('true')
    expect(formatForPreview(false)).toBe('false')
  })

  it('formats an object as JSON', () => {
    expect(formatForPreview({ a: 1, b: 2 })).toBe('{"a":1,"b":2}')
  })

  it('formats an array as JSON', () => {
    expect(formatForPreview([1, 2, 3])).toBe('[1,2,3]')
  })

  it('truncates long JSON with ellipsis', () => {
    const longArray = Array.from({ length: 100 }, (_, i) => `item-${i}`)
    const result = formatForPreview(longArray)
    expect(result.length).toBeLessThanOrEqual(121) // 120 + ellipsis char
    expect(result.endsWith('…')).toBe(true)
  })

  it('does not truncate short JSON', () => {
    const result = formatForPreview({ x: 1 })
    expect(result).toBe('{"x":1}')
    expect(result.endsWith('…')).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// isValidExpression
// ---------------------------------------------------------------------------

describe('isValidExpression', () => {
  it('returns true for a simple path', () => {
    expect(isValidExpression('customer.name')).toBe(true)
  })

  it('returns true for a function call', () => {
    expect(isValidExpression('$sum(items.price)')).toBe(true)
  })

  it('returns true for string concatenation', () => {
    expect(isValidExpression('first & " " & last')).toBe(true)
  })

  it('returns true for a conditional', () => {
    expect(isValidExpression('active ? "Yes" : "No"')).toBe(true)
  })

  it('returns false for invalid syntax', () => {
    expect(isValidExpression('{{broken')).toBe(false)
  })

  it('returns false for empty expression', () => {
    expect(isValidExpression('')).toBe(false)
  })

  it('returns false for whitespace-only expression', () => {
    expect(isValidExpression('   ')).toBe(false)
  })
})

// ---------------------------------------------------------------------------
// validateArrayResult
// ---------------------------------------------------------------------------

describe('validateArrayResult', () => {
  it('returns null for an array', () => {
    expect(validateArrayResult([1, 2, 3])).toBeNull()
  })

  it('returns null for an empty array', () => {
    expect(validateArrayResult([])).toBeNull()
  })

  it('returns null for undefined (missing path)', () => {
    expect(validateArrayResult(undefined)).toBeNull()
  })

  it('returns error for a string', () => {
    const result = validateArrayResult('hello')
    expect(result).toContain('must evaluate to an array')
    expect(result).toContain('string')
  })

  it('returns error for a number', () => {
    const result = validateArrayResult(42)
    expect(result).toContain('must evaluate to an array')
    expect(result).toContain('number')
  })

  it('returns error for a boolean', () => {
    const result = validateArrayResult(true)
    expect(result).toContain('must evaluate to an array')
    expect(result).toContain('boolean')
  })

  it('returns error for null', () => {
    const result = validateArrayResult(null)
    expect(result).toContain('must evaluate to an array')
    expect(result).toContain('null')
  })

  it('returns error for an object (single-result filter)', () => {
    const result = validateArrayResult({ name: 'item' })
    expect(result).toContain('must evaluate to an array')
    expect(result).toContain('object')
  })
})

// ---------------------------------------------------------------------------
// validateBooleanResult
// ---------------------------------------------------------------------------

describe('validateBooleanResult', () => {
  it('returns null for true', () => {
    expect(validateBooleanResult(true)).toBeNull()
  })

  it('returns null for false', () => {
    expect(validateBooleanResult(false)).toBeNull()
  })

  it('returns null for undefined (missing path)', () => {
    expect(validateBooleanResult(undefined)).toBeNull()
  })

  it('returns error for a string', () => {
    const result = validateBooleanResult('yes')
    expect(result).toContain('must evaluate to a boolean')
    expect(result).toContain('string')
  })

  it('returns error for a number', () => {
    const result = validateBooleanResult(1)
    expect(result).toContain('must evaluate to a boolean')
    expect(result).toContain('number')
  })

  it('returns error for null', () => {
    const result = validateBooleanResult(null)
    expect(result).toContain('must evaluate to a boolean')
    expect(result).toContain('null')
  })

  it('returns error for an array', () => {
    const result = validateBooleanResult([1, 2])
    expect(result).toContain('must evaluate to a boolean')
    expect(result).toContain('object')
  })

  it('returns error for an object', () => {
    const result = validateBooleanResult({ active: true })
    expect(result).toContain('must evaluate to a boolean')
    expect(result).toContain('object')
  })
})
