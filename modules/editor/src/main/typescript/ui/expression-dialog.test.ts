import { describe, it, expect } from 'vitest'
import { parseFormatDateExpression, wrapFormatDate } from './expression-dialog.js'

describe('parseFormatDateExpression', () => {
  it('parses a simple field path', () => {
    expect(parseFormatDateExpression("$formatDate(invoiceDate, 'dd-MM-yyyy')"))
      .toEqual({ fieldPath: 'invoiceDate', pattern: 'dd-MM-yyyy' })
  })

  it('parses a dotted field path', () => {
    expect(parseFormatDateExpression("$formatDate(customer.birthDate, 'dd-MM-yyyy')"))
      .toEqual({ fieldPath: 'customer.birthDate', pattern: 'dd-MM-yyyy' })
  })

  it('parses with spaces around arguments', () => {
    expect(parseFormatDateExpression("$formatDate( invoiceDate , 'dd-MM-yyyy' )"))
      .toEqual({ fieldPath: 'invoiceDate', pattern: 'dd-MM-yyyy' })
  })

  it('parses d MMMM yyyy pattern', () => {
    expect(parseFormatDateExpression("$formatDate(date, 'd MMMM yyyy')"))
      .toEqual({ fieldPath: 'date', pattern: 'd MMMM yyyy' })
  })

  it('returns null for a bare field path', () => {
    expect(parseFormatDateExpression('invoiceDate')).toBeNull()
  })

  it('returns null for a different function call', () => {
    expect(parseFormatDateExpression("$uppercase(name)")).toBeNull()
  })

  it('returns null for empty string', () => {
    expect(parseFormatDateExpression('')).toBeNull()
  })
})

describe('wrapFormatDate', () => {
  it('wraps a simple field', () => {
    expect(wrapFormatDate('invoiceDate', 'dd-MM-yyyy'))
      .toBe("$formatDate(invoiceDate, 'dd-MM-yyyy')")
  })

  it('wraps a dotted field path', () => {
    expect(wrapFormatDate('customer.birthDate', 'dd/MM/yyyy'))
      .toBe("$formatDate(customer.birthDate, 'dd/MM/yyyy')")
  })
})
