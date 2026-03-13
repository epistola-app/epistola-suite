import { describe, it, expect } from 'vitest'
import type { BoxValue } from '../../engine/style-values.js'
import { expandBoxToStyles, readBoxFromStyles, extractBoxDefaults } from './style-inputs.js'

// ---------------------------------------------------------------------------
// expandBoxToStyles
// ---------------------------------------------------------------------------

describe('expandBoxToStyles', () => {
  it('writes individual margin keys from a BoxValue', () => {
    const styles: Record<string, unknown> = {}
    const box: BoxValue = { top: '10px', right: '5px', bottom: '8px', left: '5px' }

    expandBoxToStyles('margin', box, styles)

    expect(styles).toEqual({
      marginTop: '10px',
      marginRight: '5px',
      marginBottom: '8px',
      marginLeft: '5px',
    })
  })

  it('writes individual padding keys from a BoxValue', () => {
    const styles: Record<string, unknown> = {}
    const box: BoxValue = { top: '1em', right: '2em', bottom: '1em', left: '2em' }

    expandBoxToStyles('padding', box, styles)

    expect(styles).toEqual({
      paddingTop: '1em',
      paddingRight: '2em',
      paddingBottom: '1em',
      paddingLeft: '2em',
    })
  })

  it('removes the compound key if it existed', () => {
    const styles: Record<string, unknown> = { margin: { top: '10px' } }
    const box: BoxValue = { top: '10px', right: '0px', bottom: '0px', left: '0px' }

    expandBoxToStyles('margin', box, styles)

    expect(styles.margin).toBeUndefined()
    expect(styles.marginTop).toBe('10px')
  })

  it('only writes explicitly defined sides (not undefined)', () => {
    const styles: Record<string, unknown> = {}
    const box: BoxValue = { top: '10px', right: undefined, bottom: undefined, left: undefined }

    expandBoxToStyles('margin', box, styles)

    expect(styles.marginTop).toBe('10px')
    expect(styles.marginRight).toBeUndefined()
    expect(styles.marginBottom).toBeUndefined()
    expect(styles.marginLeft).toBeUndefined()
  })

  it('preserves zero-value keys so explicit zero can override defaults', () => {
    const styles: Record<string, unknown> = { marginTop: '10px', marginRight: '5px' }
    const box: BoxValue = { top: '10px', right: '0px', bottom: '0px', left: '0px' }

    expandBoxToStyles('margin', box, styles)

    expect(styles.marginTop).toBe('10px')
    expect(styles.marginRight).toBe('0px')
    expect(styles.marginBottom).toBe('0px')
    expect(styles.marginLeft).toBe('0px'
    )
  })

  it('preserves 0em and 0rem values', () => {
    const styles: Record<string, unknown> = {}
    const box: BoxValue = { top: '0em', right: '0rem', bottom: '5px', left: '0px' }

    expandBoxToStyles('margin', box, styles)

    expect(styles.marginTop).toBe('0em')
    expect(styles.marginRight).toBe('0rem')
    expect(styles.marginBottom).toBe('5px')
    expect(styles.marginLeft).toBe('0px')
  })
})

// ---------------------------------------------------------------------------
// readBoxFromStyles
// ---------------------------------------------------------------------------

describe('readBoxFromStyles', () => {
  it('reads individual margin keys into a BoxValue', () => {
    const styles = { marginTop: '10px', marginRight: '5px', marginBottom: '8px', marginLeft: '5px' }

    const result = readBoxFromStyles('margin', styles)

    expect(result).toEqual({ top: '10px', right: '5px', bottom: '8px', left: '5px' })
  })

  it('returns undefined for missing sides (to inherit defaults)', () => {
    const styles = { marginBottom: '12px' }

    const result = readBoxFromStyles('margin', styles)

    expect(result).toEqual({ top: undefined, right: undefined, bottom: '12px', left: undefined })
  })

  it('returns undefined when no individual keys are set', () => {
    const styles = { fontSize: '14px', color: '#333' }

    const result = readBoxFromStyles('margin', styles)

    expect(result).toBeUndefined()
  })

  it('reads padding keys', () => {
    const styles = { paddingTop: '5px', paddingBottom: '10px' }

    const result = readBoxFromStyles('padding', styles)

    expect(result).toEqual({ top: '5px', right: undefined, bottom: '10px', left: undefined })
  })

  it('handles legacy compound object as fallback', () => {
    const styles = { margin: { top: '10px', right: '5px', bottom: '10px', left: '5px' } }

    const result = readBoxFromStyles('margin', styles)

    expect(result).toEqual({ top: '10px', right: '5px', bottom: '10px', left: '5px' })
  })

  it('handles legacy shorthand string as fallback', () => {
    const styles = { padding: '12px 8px' }

    const result = readBoxFromStyles('padding', styles)

    expect(result).toEqual({ top: '12px', right: '8px', bottom: '12px', left: '8px' })
  })
})

// ---------------------------------------------------------------------------
// extractBoxDefaults
// ---------------------------------------------------------------------------

describe('extractBoxDefaults', () => {
  it('extracts margin defaults from component default styles', () => {
    const defaultStyles = { marginTop: '0.5em', marginBottom: '0.5em' }
    const mapping = { top: 'marginTop', right: 'marginRight', bottom: 'marginBottom', left: 'marginLeft' }

    const result = extractBoxDefaults(defaultStyles, mapping)

    expect(result).toEqual({
      top: '0.5em',
      right: undefined,
      bottom: '0.5em',
      left: undefined,
    })
  })

  it('extracts padding defaults from component default styles', () => {
    const defaultStyles = { paddingTop: '10px', paddingRight: '10px', paddingBottom: '10px', paddingLeft: '10px' }
    const mapping = { top: 'paddingTop', right: 'paddingRight', bottom: 'paddingBottom', left: 'paddingLeft' }

    const result = extractBoxDefaults(defaultStyles, mapping)

    expect(result).toEqual({
      top: '10px',
      right: '10px',
      bottom: '10px',
      left: '10px',
    })
  })

  it('returns undefined for sides with no defaults', () => {
    const defaultStyles = {}
    const mapping = { top: 'marginTop', right: 'marginRight', bottom: 'marginBottom', left: 'marginLeft' }

    const result = extractBoxDefaults(defaultStyles, mapping)

    expect(result).toEqual({
      top: undefined,
      right: undefined,
      bottom: undefined,
      left: undefined,
    })
  })

  it('handles undefined default styles', () => {
    const mapping = { top: 'marginTop', right: 'marginRight', bottom: 'marginBottom', left: 'marginLeft' }

    const result = extractBoxDefaults(undefined, mapping)

    expect(result).toEqual({
      top: undefined,
      right: undefined,
      bottom: undefined,
      left: undefined,
    })
  })
})

// ---------------------------------------------------------------------------
// Backward compatibility tests
// ---------------------------------------------------------------------------

describe('backward compatibility', () => {
  it('expandSpacingToStyles still works (deprecated)', async () => {
    // Import the deprecated function
    const { expandSpacingToStyles } = await import('./style-inputs.js')
    const styles: Record<string, unknown> = {}
    const box: BoxValue = { top: '10px', right: '5px', bottom: '8px', left: '5px' }

    expandSpacingToStyles('margin', box, styles)

    expect(styles.marginTop).toBe('10px')
    expect(styles.marginRight).toBe('5px')
  })

  it('readSpacingFromStyles still works (deprecated)', async () => {
    const { readSpacingFromStyles } = await import('./style-inputs.js')
    const styles = { marginTop: '10px', marginBottom: '12px' }

    const result = readSpacingFromStyles('margin', styles, 'px')

    expect(result?.top).toBe('10px')
    expect(result?.bottom).toBe('12px')
    expect(result?.right).toBeUndefined()
    expect(result?.left).toBeUndefined()
  })
})
