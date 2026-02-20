import { describe, it, expect } from 'vitest'
import {
  getInheritableKeys,
  resolveDocumentStyles,
  resolveNodeStyles,
  resolvePageSettings,
  resolvePresetStyles,
  DEFAULT_PAGE_SETTINGS,
} from './styles.js'
import { defaultStyleRegistry } from './style-registry.js'
import type { StyleRegistry } from '@epistola/template-model/generated/style-registry.js'

// ---------------------------------------------------------------------------
// getInheritableKeys
// ---------------------------------------------------------------------------

describe('getInheritableKeys', () => {
  it('extracts inheritable keys from the default registry', () => {
    const keys = getInheritableKeys(defaultStyleRegistry)

    expect(keys.has('fontFamily')).toBe(true)
    expect(keys.has('fontSize')).toBe(true)
    expect(keys.has('fontWeight')).toBe(true)
    expect(keys.has('color')).toBe(true)
    expect(keys.has('lineHeight')).toBe(true)
    expect(keys.has('letterSpacing')).toBe(true)
    expect(keys.has('textAlign')).toBe(true)
  })

  it('does not include non-inheritable keys', () => {
    const keys = getInheritableKeys(defaultStyleRegistry)

    // spacing, background, borders are not inheritable by default
    expect(keys.has('padding')).toBe(false)
    expect(keys.has('margin')).toBe(false)
    expect(keys.has('backgroundColor')).toBe(false)
    expect(keys.has('borderWidth')).toBe(false)
    expect(keys.has('borderColor')).toBe(false)
  })

  it('works with a custom minimal registry', () => {
    const registry: StyleRegistry = {
      groups: [
        {
          name: 'test',
          label: 'Test',
          properties: [
            { key: 'a', label: 'A', type: 'text', inheritable: true },
            { key: 'b', label: 'B', type: 'text', inheritable: false },
            { key: 'c', label: 'C', type: 'text' }, // inheritable defaults to false
          ],
        },
      ],
    }
    const keys = getInheritableKeys(registry)

    expect(keys.size).toBe(1)
    expect(keys.has('a')).toBe(true)
  })
})

// ---------------------------------------------------------------------------
// resolveDocumentStyles
// ---------------------------------------------------------------------------

describe('resolveDocumentStyles', () => {
  it('merges theme + overrides (override wins)', () => {
    const theme = { fontFamily: 'Arial', fontSize: '14px', color: '#000' }
    const overrides = { fontSize: '16px', lineHeight: '1.5' }

    const result = resolveDocumentStyles(theme, overrides)

    expect(result).toEqual({
      fontFamily: 'Arial',
      fontSize: '16px',
      color: '#000',
      lineHeight: '1.5',
    })
  })

  it('returns theme styles when no overrides', () => {
    const theme = { fontFamily: 'Georgia', color: 'red' }
    const result = resolveDocumentStyles(theme, undefined)

    expect(result).toEqual({ fontFamily: 'Georgia', color: 'red' })
  })

  it('returns overrides when no theme styles', () => {
    const overrides = { fontSize: '18px' }
    const result = resolveDocumentStyles(undefined, overrides)

    expect(result).toEqual({ fontSize: '18px' })
  })

  it('returns empty object when both are undefined', () => {
    const result = resolveDocumentStyles(undefined, undefined)
    expect(result).toEqual({})
  })
})

// ---------------------------------------------------------------------------
// resolveNodeStyles
// ---------------------------------------------------------------------------

describe('resolveNodeStyles', () => {
  const inheritableKeys = new Set(['fontFamily', 'fontSize', 'color', 'textAlign'])

  it('applies full cascade: defaults → inheritable → preset → inline', () => {
    const docStyles = { fontFamily: 'Arial', fontSize: '14px', color: '#000', backgroundColor: '#fff' }
    const preset = { fontSize: '18px', paddingTop: '10px' }
    const inline = { color: 'red' }
    const defaults = { marginBottom: '0.5em', color: 'black' }

    const result = resolveNodeStyles(docStyles, inheritableKeys, preset, inline, defaults)

    expect(result).toEqual({
      marginBottom: '0.5em',   // from defaults (not overridden)
      fontFamily: 'Arial',     // from doc (inheritable)
      fontSize: '18px',        // overridden by preset
      color: 'red',            // overridden by inline (defaults → doc → inline)
      paddingTop: '10px',      // from preset (non-inheritable, but preset adds it)
    })
  })

  it('only inherits inheritable keys from doc styles', () => {
    const docStyles = { fontFamily: 'Arial', backgroundColor: '#fff', margin: '10px' }

    const result = resolveNodeStyles(docStyles, inheritableKeys, undefined, undefined)

    // Only fontFamily is inheritable; backgroundColor and margin are not
    expect(result).toEqual({ fontFamily: 'Arial' })
    expect(result).not.toHaveProperty('backgroundColor')
    expect(result).not.toHaveProperty('margin')
  })

  it('inline overrides preset', () => {
    const docStyles = {}
    const preset = { fontFamily: 'Georgia', fontSize: '16px' }
    const inline = { fontFamily: 'Verdana' }

    const result = resolveNodeStyles(docStyles, inheritableKeys, preset, inline)

    expect(result.fontFamily).toBe('Verdana')
    expect(result.fontSize).toBe('16px')
  })

  it('handles all undefined gracefully', () => {
    const result = resolveNodeStyles({}, inheritableKeys, undefined, undefined)
    expect(result).toEqual({})
  })

  it('handles empty inheritable keys', () => {
    const docStyles = { fontFamily: 'Arial', color: '#000' }
    const result = resolveNodeStyles(docStyles, new Set(), undefined, undefined)
    expect(result).toEqual({})
  })

  it('applies defaultStyles as lowest priority layer', () => {
    const docStyles = {}
    const defaults = { marginBottom: '0.5em', fontSize: '12px' }

    const result = resolveNodeStyles(docStyles, inheritableKeys, undefined, undefined, defaults)

    expect(result).toEqual({ marginBottom: '0.5em', fontSize: '12px' })
  })

  it('defaultStyles are overridden by inheritable doc styles', () => {
    const docStyles = { fontSize: '16px' }
    const defaults = { marginBottom: '0.5em', fontSize: '12px' }

    const result = resolveNodeStyles(docStyles, inheritableKeys, undefined, undefined, defaults)

    expect(result.fontSize).toBe('16px') // doc overrides default
    expect(result.marginBottom).toBe('0.5em') // default preserved
  })

  it('defaultStyles are overridden by preset', () => {
    const preset = { marginBottom: '1em' }
    const defaults = { marginBottom: '0.5em' }

    const result = resolveNodeStyles({}, inheritableKeys, preset, undefined, defaults)

    expect(result.marginBottom).toBe('1em')
  })

  it('defaultStyles are overridden by inline', () => {
    const inline = { marginBottom: '2em' }
    const defaults = { marginBottom: '0.5em' }

    const result = resolveNodeStyles({}, inheritableKeys, undefined, inline, defaults)

    expect(result.marginBottom).toBe('2em')
  })

  it('works without defaultStyles (backward compatible)', () => {
    const docStyles = { fontFamily: 'Arial' }
    const result = resolveNodeStyles(docStyles, inheritableKeys, undefined, undefined)

    expect(result).toEqual({ fontFamily: 'Arial' })
  })
})

// ---------------------------------------------------------------------------
// resolvePageSettings
// ---------------------------------------------------------------------------

describe('resolvePageSettings', () => {
  it('defaults to A4/portrait/20mm', () => {
    const result = resolvePageSettings(undefined, undefined)

    expect(result).toEqual(DEFAULT_PAGE_SETTINGS)
    expect(result.format).toBe('A4')
    expect(result.orientation).toBe('portrait')
    expect(result.margins).toEqual({ top: 20, right: 20, bottom: 20, left: 20 })
  })

  it('uses theme settings when no overrides', () => {
    const theme = {
      format: 'Letter' as const,
      orientation: 'landscape' as const,
      margins: { top: 25, right: 25, bottom: 25, left: 25 },
    }

    const result = resolvePageSettings(theme, undefined)

    expect(result).toEqual(theme)
  })

  it('merges partial overrides', () => {
    const theme = {
      format: 'A4' as const,
      orientation: 'portrait' as const,
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    }
    const overrides = { orientation: 'landscape' as const }

    const result = resolvePageSettings(theme, overrides)

    expect(result.format).toBe('A4') // from theme
    expect(result.orientation).toBe('landscape') // overridden
    expect(result.margins).toEqual({ top: 20, right: 20, bottom: 20, left: 20 }) // from theme
  })

  it('includes backgroundColor when present', () => {
    const theme = {
      format: 'A4' as const,
      orientation: 'portrait' as const,
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
      backgroundColor: '#f5f5f5',
    }

    const result = resolvePageSettings(theme, undefined)

    expect(result.backgroundColor).toBe('#f5f5f5')
  })

  it('override backgroundColor wins over theme', () => {
    const theme = {
      format: 'A4' as const,
      orientation: 'portrait' as const,
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
      backgroundColor: '#f5f5f5',
    }
    const overrides = { backgroundColor: '#ffffff' }

    const result = resolvePageSettings(theme, overrides)

    expect(result.backgroundColor).toBe('#ffffff')
  })
})

// ---------------------------------------------------------------------------
// resolvePresetStyles
// ---------------------------------------------------------------------------

describe('resolvePresetStyles', () => {
  const presets = {
    heading: {
      label: 'Heading',
      styles: { fontFamily: 'Georgia', fontSize: '24px', fontWeight: '700' },
    },
    body: {
      label: 'Body',
      styles: { fontFamily: 'Arial', fontSize: '14px' },
    },
  }

  it('looks up preset by name', () => {
    const result = resolvePresetStyles(presets, 'heading')

    expect(result).toEqual({ fontFamily: 'Georgia', fontSize: '24px', fontWeight: '700' })
  })

  it('returns undefined for unknown preset name', () => {
    const result = resolvePresetStyles(presets, 'nonexistent')
    expect(result).toBeUndefined()
  })

  it('returns undefined when presetName is undefined', () => {
    const result = resolvePresetStyles(presets, undefined)
    expect(result).toBeUndefined()
  })

  it('returns undefined when presets is undefined', () => {
    const result = resolvePresetStyles(undefined, 'heading')
    expect(result).toBeUndefined()
  })

  it('returns undefined when both are undefined', () => {
    const result = resolvePresetStyles(undefined, undefined)
    expect(result).toBeUndefined()
  })
})
