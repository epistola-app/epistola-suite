import { describe, expect, it } from 'vitest'
import {
  applyStyleFieldValue,
  defaultInheritableStyleKeys,
  defaultStyleRegistry,
  readStyleFieldValue,
} from './style-registry.js'

describe('defaultStyleRegistry', () => {
  it('only exposes the admitted strict style fields', () => {
    const fieldKeys = defaultStyleRegistry.groups.flatMap(group =>
      group.properties.map(property => property.key),
    )

    expect(fieldKeys).toEqual([
      'fontSize',
      'fontFamily',
      'fontWeight',
      'fontStyle',
      'color',
      'textAlign',
      'lineHeight',
      'letterSpacing',
      'padding',
      'margin',
      'backgroundColor',
    ])
  })

  it('marks only the admitted typography fields as inheritable', () => {
    expect(Array.from(defaultInheritableStyleKeys)).toEqual([
      'fontSize',
      'fontFamily',
      'fontWeight',
      'fontStyle',
      'color',
      'textAlign',
      'lineHeight',
      'letterSpacing',
    ])
  })
})

describe('style field mapping', () => {
  it('writes scalar values to canonical property keys', () => {
    const styles: Record<string, unknown> = {}

    applyStyleFieldValue('color', '#333333', styles)

    expect(styles).toEqual({ color: '#333333' })
  })

  it('writes spacing values to canonical longhand keys and preserves zeros', () => {
    const styles: Record<string, unknown> = { margin: '4px' }

    applyStyleFieldValue('margin', {
      top: '0px',
      right: '12px',
      bottom: '8px',
      left: '0px',
    }, styles)

    expect(styles).toEqual({
      marginTop: '0px',
      marginRight: '12px',
      marginBottom: '8px',
      marginLeft: '0px',
    })
  })

  it('reads canonical longhand spacing back into a composite field value', () => {
    const value = readStyleFieldValue('padding', {
      paddingTop: '4px',
      paddingRight: '8px',
      paddingBottom: '4px',
      paddingLeft: '8px',
    })

    expect(value).toEqual({
      top: '4px',
      right: '8px',
      bottom: '4px',
      left: '8px',
    })
  })

  it('reads legacy shorthand spacing strings for backward compatibility', () => {
    const value = readStyleFieldValue('padding', { padding: '16px 12px' })

    expect(value).toEqual({
      top: '16px',
      right: '12px',
      bottom: '16px',
      left: '12px',
    })
  })
})
