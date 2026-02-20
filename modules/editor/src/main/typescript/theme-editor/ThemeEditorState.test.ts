import { describe, it, expect, vi } from 'vitest'
import { ThemeEditorState, type ThemeData } from './ThemeEditorState.js'

function createTestTheme(overrides?: Partial<ThemeData>): ThemeData {
  return {
    id: 'test-theme',
    name: 'Test Theme',
    description: 'A test theme',
    documentStyles: { fontSize: '12pt', color: '#333' },
    pageSettings: {
      format: 'A4',
      orientation: 'portrait',
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    blockStylePresets: {
      heading: {
        label: 'Heading',
        styles: { fontSize: '24pt', fontWeight: 'bold' },
        applicableTo: ['text'],
      },
    },
    ...overrides,
  }
}

describe('ThemeEditorState', () => {
  describe('initial state', () => {
    it('is not dirty initially', () => {
      const state = new ThemeEditorState(createTestTheme())
      expect(state.isDirty).toBe(false)
    })

    it('returns the theme data', () => {
      const data = createTestTheme()
      const state = new ThemeEditorState(data)
      expect(state.theme.name).toBe('Test Theme')
      expect(state.theme.id).toBe('test-theme')
    })

    it('deep-clones initial data so mutations do not affect original', () => {
      const data = createTestTheme()
      const state = new ThemeEditorState(data)
      state.updateName('Changed')
      expect(data.name).toBe('Test Theme')
    })
  })

  describe('basic info', () => {
    it('updateName marks dirty and fires change', () => {
      const state = new ThemeEditorState(createTestTheme())
      const handler = vi.fn()
      state.addEventListener('change', handler)

      state.updateName('New Name')
      expect(state.theme.name).toBe('New Name')
      expect(state.isDirty).toBe(true)
      expect(handler).toHaveBeenCalledTimes(1)
    })

    it('updateDescription to undefined clears it', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateDescription(undefined)
      expect(state.theme.description).toBeUndefined()
      expect(state.isDirty).toBe(true)
    })
  })

  describe('document styles', () => {
    it('updateDocumentStyle sets a value', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateDocumentStyle('fontFamily', 'Arial')
      expect(state.theme.documentStyles.fontFamily).toBe('Arial')
      expect(state.isDirty).toBe(true)
    })

    it('updateDocumentStyle removes a value when empty', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateDocumentStyle('color', undefined)
      expect('color' in state.theme.documentStyles).toBe(false)
    })

    it('updateDocumentStyle removes a value when empty string', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateDocumentStyle('color', '')
      expect('color' in state.theme.documentStyles).toBe(false)
    })

    it('updateDocumentStyle expands spacing to individual keys', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateDocumentStyle('margin', {
        top: '0px', right: '0px', bottom: '10px', left: '0px',
      })
      expect(state.theme.documentStyles.marginBottom).toBe('10px')
      expect(state.theme.documentStyles.margin).toBeUndefined()
    })
  })

  describe('page settings', () => {
    it('updatePageSetting creates page settings if missing', () => {
      const state = new ThemeEditorState(createTestTheme({ pageSettings: undefined }))
      state.updatePageSetting('format', 'Letter')
      expect(state.theme.pageSettings?.format).toBe('Letter')
    })

    it('updatePageSetting updates existing setting', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePageSetting('orientation', 'landscape')
      expect(state.theme.pageSettings?.orientation).toBe('landscape')
    })

    it('updateMargin updates a specific margin', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateMargin('top', 30)
      expect(state.theme.pageSettings?.margins.top).toBe(30)
      expect(state.theme.pageSettings?.margins.right).toBe(20)
    })

    it('updateMargin creates page settings if missing', () => {
      const state = new ThemeEditorState(createTestTheme({ pageSettings: undefined }))
      state.updateMargin('left', 15)
      expect(state.theme.pageSettings?.margins.left).toBe(15)
    })
  })

  describe('presets', () => {
    it('addPreset adds a new preset', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.addPreset('quote')
      expect(state.theme.blockStylePresets.quote).toEqual({
        label: 'quote',
        styles: {},
      })
    })

    it('addPreset does not overwrite existing preset', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.addPreset('heading')
      expect(state.theme.blockStylePresets.heading.label).toBe('Heading')
    })

    it('removePreset removes a preset', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.removePreset('heading')
      expect(state.theme.blockStylePresets.heading).toBeUndefined()
    })

    it('renamePreset moves preset to new key', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.renamePreset('heading', 'title')
      expect(state.theme.blockStylePresets.heading).toBeUndefined()
      expect(state.theme.blockStylePresets.title).toBeDefined()
      expect(state.theme.blockStylePresets.title.label).toBe('Heading')
    })

    it('renamePreset does nothing if target already exists', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.addPreset('title')
      state.renamePreset('heading', 'title')
      // heading should still exist
      expect(state.theme.blockStylePresets.heading).toBeDefined()
    })

    it('updatePresetLabel updates the label', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePresetLabel('heading', 'Main Heading')
      expect(state.theme.blockStylePresets.heading.label).toBe('Main Heading')
    })

    it('updatePresetStyle sets a style value', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePresetStyle('heading', 'color', '#000')
      expect(state.theme.blockStylePresets.heading.styles.color).toBe('#000')
    })

    it('updatePresetStyle removes a style value when empty', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePresetStyle('heading', 'fontSize', undefined)
      expect('fontSize' in state.theme.blockStylePresets.heading.styles).toBe(false)
    })

    it('updatePresetStyle expands spacing to individual keys', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePresetStyle('heading', 'margin', {
        top: '10px', right: '0px', bottom: '16px', left: '0px',
      })
      const styles = state.theme.blockStylePresets.heading.styles as Record<string, unknown>
      expect(styles.marginTop).toBe('10px')
      expect(styles.marginBottom).toBe('16px')
      expect(styles.marginRight).toBeUndefined() // zero values removed
      expect(styles.marginLeft).toBeUndefined()
      expect(styles.margin).toBeUndefined() // compound key removed
    })

    it('updatePresetStyle expands padding to individual keys', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePresetStyle('heading', 'padding', {
        top: '8px', right: '12px', bottom: '8px', left: '12px',
      })
      const styles = state.theme.blockStylePresets.heading.styles as Record<string, unknown>
      expect(styles.paddingTop).toBe('8px')
      expect(styles.paddingRight).toBe('12px')
      expect(styles.paddingBottom).toBe('8px')
      expect(styles.paddingLeft).toBe('12px')
      expect(styles.padding).toBeUndefined()
    })

    it('updatePresetApplicableTo sets node types', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePresetApplicableTo('heading', ['text', 'container'])
      expect(state.theme.blockStylePresets.heading.applicableTo).toEqual(['text', 'container'])
    })

    it('updatePresetApplicableTo clears when empty', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePresetApplicableTo('heading', [])
      expect(state.theme.blockStylePresets.heading.applicableTo).toBeUndefined()
    })
  })

  describe('computePatchPayload', () => {
    it('returns empty object when nothing changed', () => {
      const state = new ThemeEditorState(createTestTheme())
      expect(state.computePatchPayload()).toEqual({})
    })

    it('includes name when changed', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateName('New Name')
      expect(state.computePatchPayload()).toEqual({ name: 'New Name' })
    })

    it('includes clearDescription when description removed', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateDescription(undefined)
      expect(state.computePatchPayload()).toEqual({ clearDescription: true })
    })

    it('includes documentStyles when changed', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateDocumentStyle('fontSize', '14pt')
      const patch = state.computePatchPayload()
      expect(patch.documentStyles).toEqual({ fontSize: '14pt', color: '#333' })
    })

    it('includes pageSettings when changed', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateMargin('top', 30)
      const patch = state.computePatchPayload()
      expect(patch.pageSettings).toBeDefined()
    })

    it('includes clearBlockStylePresets when all removed', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.removePreset('heading')
      expect(state.computePatchPayload()).toEqual({ clearBlockStylePresets: true })
    })

    it('includes blockStylePresets when changed', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updatePresetLabel('heading', 'Title')
      const patch = state.computePatchPayload()
      expect(patch.blockStylePresets).toBeDefined()
    })

    it('does not include unchanged fields', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateName('Changed')
      const patch = state.computePatchPayload()
      expect(patch.documentStyles).toBeUndefined()
      expect(patch.pageSettings).toBeUndefined()
      expect(patch.blockStylePresets).toBeUndefined()
    })
  })

  describe('markSaved', () => {
    it('resets dirty state', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateName('Changed')
      expect(state.isDirty).toBe(true)
      state.markSaved()
      expect(state.isDirty).toBe(false)
    })

    it('subsequent changes are dirty relative to saved state', () => {
      const state = new ThemeEditorState(createTestTheme())
      state.updateName('Changed')
      state.markSaved()
      state.updateName('Changed Again')
      expect(state.isDirty).toBe(true)
      expect(state.computePatchPayload()).toEqual({ name: 'Changed Again' })
    })
  })
})
