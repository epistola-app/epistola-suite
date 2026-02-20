import { describe, it, expect } from 'vitest'
import { expandSpacingToStyles, readSpacingFromStyles, type SpacingValue } from './style-inputs.js'

// ---------------------------------------------------------------------------
// expandSpacingToStyles
// ---------------------------------------------------------------------------

describe('expandSpacingToStyles', () => {
  it('writes individual margin keys from a SpacingValue', () => {
    const styles: Record<string, unknown> = {}
    const spacing: SpacingValue = { top: '10px', right: '5px', bottom: '8px', left: '5px' }

    expandSpacingToStyles('margin', spacing, styles)

    expect(styles).toEqual({
      marginTop: '10px',
      marginRight: '5px',
      marginBottom: '8px',
      marginLeft: '5px',
    })
  })

  it('writes individual padding keys from a SpacingValue', () => {
    const styles: Record<string, unknown> = {}
    const spacing: SpacingValue = { top: '1em', right: '2em', bottom: '1em', left: '2em' }

    expandSpacingToStyles('padding', spacing, styles)

    expect(styles).toEqual({
      paddingTop: '1em',
      paddingRight: '2em',
      paddingBottom: '1em',
      paddingLeft: '2em',
    })
  })

  it('removes the compound key if it existed', () => {
    const styles: Record<string, unknown> = { margin: { top: '10px' } }
    const spacing: SpacingValue = { top: '10px', right: '0px', bottom: '0px', left: '0px' }

    expandSpacingToStyles('margin', spacing, styles)

    expect(styles.margin).toBeUndefined()
    expect(styles.marginTop).toBe('10px')
  })

  it('deletes zero-value keys instead of storing them', () => {
    const styles: Record<string, unknown> = { marginTop: '10px', marginRight: '5px' }
    const spacing: SpacingValue = { top: '10px', right: '0px', bottom: '0px', left: '0px' }

    expandSpacingToStyles('margin', spacing, styles)

    expect(styles.marginTop).toBe('10px')
    expect(styles.marginRight).toBeUndefined()
    expect(styles.marginBottom).toBeUndefined()
    expect(styles.marginLeft).toBeUndefined()
  })

  it('treats 0em and 0rem as zero values', () => {
    const styles: Record<string, unknown> = {}
    const spacing: SpacingValue = { top: '0em', right: '0rem', bottom: '5px', left: '0px' }

    expandSpacingToStyles('margin', spacing, styles)

    expect(styles.marginTop).toBeUndefined()
    expect(styles.marginRight).toBeUndefined()
    expect(styles.marginBottom).toBe('5px')
    expect(styles.marginLeft).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// readSpacingFromStyles
// ---------------------------------------------------------------------------

describe('readSpacingFromStyles', () => {
  it('reads individual margin keys into a SpacingValue', () => {
    const styles = { marginTop: '10px', marginRight: '5px', marginBottom: '8px', marginLeft: '5px' }

    const result = readSpacingFromStyles('margin', styles)

    expect(result).toEqual({ top: '10px', right: '5px', bottom: '8px', left: '5px' })
  })

  it('defaults missing sides to 0px', () => {
    const styles = { marginBottom: '12px' }

    const result = readSpacingFromStyles('margin', styles)

    expect(result).toEqual({ top: '0px', right: '0px', bottom: '12px', left: '0px' })
  })

  it('returns undefined when no individual keys are set', () => {
    const styles = { fontSize: '14px', color: '#333' }

    const result = readSpacingFromStyles('margin', styles)

    expect(result).toBeUndefined()
  })

  it('uses the provided default unit', () => {
    const styles = { paddingTop: '2em' }

    const result = readSpacingFromStyles('padding', styles, 'em')

    expect(result).toEqual({ top: '2em', right: '0em', bottom: '0em', left: '0em' })
  })

  it('reads padding keys', () => {
    const styles = { paddingTop: '5px', paddingBottom: '10px' }

    const result = readSpacingFromStyles('padding', styles)

    expect(result).toEqual({ top: '5px', right: '0px', bottom: '10px', left: '0px' })
  })

  it('handles legacy compound object as fallback', () => {
    const styles = { margin: { top: '10px', right: '5px', bottom: '10px', left: '5px' } }

    const result = readSpacingFromStyles('margin', styles)

    expect(result).toEqual({ top: '10px', right: '5px', bottom: '10px', left: '5px' })
  })
})
