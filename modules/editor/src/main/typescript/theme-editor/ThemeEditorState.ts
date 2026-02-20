/**
 * ThemeEditorState — Pure state management for the theme editor.
 *
 * EventTarget-based: fires 'change' events so Lit components react.
 * No Lit dependency — testable with plain unit tests.
 */

import type { BlockStylePreset, PageSettings } from '@epistola/template-model/generated/theme.js'
import { expandSpacingToStyles, type SpacingValue } from '../ui/inputs/style-inputs.js'

export interface ThemeData {
  id: string
  name: string
  description?: string
  documentStyles: Record<string, unknown>
  pageSettings?: PageSettings
  blockStylePresets: Record<string, BlockStylePreset>
}

export class ThemeEditorState extends EventTarget {
  private _original: ThemeData
  private _current: ThemeData

  constructor(initial: ThemeData) {
    super()
    this._original = structuredClone(initial)
    this._current = structuredClone(initial)
  }

  get theme(): Readonly<ThemeData> {
    return this._current
  }

  get isDirty(): boolean {
    return JSON.stringify(this._current) !== JSON.stringify(this._original)
  }

  // -----------------------------------------------------------------------
  // Basic info
  // -----------------------------------------------------------------------

  updateName(name: string): void {
    this._current.name = name
    this._fireChange()
  }

  updateDescription(description: string | undefined): void {
    this._current.description = description
    this._fireChange()
  }

  // -----------------------------------------------------------------------
  // Document styles
  // -----------------------------------------------------------------------

  updateDocumentStyle(key: string, value: unknown): void {
    // Spacing properties: expand compound value to individual keys
    if ((key === 'margin' || key === 'padding') && value != null && typeof value === 'object') {
      expandSpacingToStyles(key, value as SpacingValue, this._current.documentStyles)
    } else if (value === undefined || value === '' || value === null) {
      delete this._current.documentStyles[key]
    } else {
      this._current.documentStyles[key] = value
    }
    this._fireChange()
  }

  // -----------------------------------------------------------------------
  // Page settings
  // -----------------------------------------------------------------------

  updatePageSetting(key: string, value: unknown): void {
    if (!this._current.pageSettings) {
      this._current.pageSettings = {
        format: 'A4',
        orientation: 'portrait',
        margins: { top: 20, right: 20, bottom: 20, left: 20 },
      }
    }
    ;(this._current.pageSettings as unknown as Record<string, unknown>)[key] = value
    this._fireChange()
  }

  updateMargin(side: 'top' | 'right' | 'bottom' | 'left', value: number): void {
    if (!this._current.pageSettings) {
      this._current.pageSettings = {
        format: 'A4',
        orientation: 'portrait',
        margins: { top: 20, right: 20, bottom: 20, left: 20 },
      }
    }
    this._current.pageSettings.margins = {
      ...this._current.pageSettings.margins,
      [side]: value,
    }
    this._fireChange()
  }

  // -----------------------------------------------------------------------
  // Presets
  // -----------------------------------------------------------------------

  addPreset(name: string): void {
    if (this._current.blockStylePresets[name]) return
    this._current.blockStylePresets[name] = {
      label: name,
      styles: {},
    }
    this._fireChange()
  }

  removePreset(name: string): void {
    delete this._current.blockStylePresets[name]
    this._fireChange()
  }

  renamePreset(oldName: string, newName: string): void {
    if (oldName === newName) return
    if (this._current.blockStylePresets[newName]) return
    const preset = this._current.blockStylePresets[oldName]
    if (!preset) return
    delete this._current.blockStylePresets[oldName]
    this._current.blockStylePresets[newName] = preset
    this._fireChange()
  }

  updatePresetLabel(name: string, label: string): void {
    const preset = this._current.blockStylePresets[name]
    if (!preset) return
    preset.label = label
    this._fireChange()
  }

  updatePresetStyle(name: string, key: string, value: unknown): void {
    const preset = this._current.blockStylePresets[name]
    if (!preset) return
    const styles = preset.styles as Record<string, unknown>

    // Spacing properties: expand compound value to individual keys
    if ((key === 'margin' || key === 'padding') && value != null && typeof value === 'object') {
      expandSpacingToStyles(key, value as SpacingValue, styles)
    } else if (value === undefined || value === '' || value === null) {
      delete styles[key]
    } else {
      styles[key] = value
    }
    this._fireChange()
  }

  updatePresetApplicableTo(name: string, types: string[]): void {
    const preset = this._current.blockStylePresets[name]
    if (!preset) return
    preset.applicableTo = types.length > 0 ? types : undefined
    this._fireChange()
  }

  // -----------------------------------------------------------------------
  // Patch payload
  // -----------------------------------------------------------------------

  /**
   * Computes the minimal PATCH body — only changed fields.
   */
  computePatchPayload(): Record<string, unknown> {
    const patch: Record<string, unknown> = {}

    if (this._current.name !== this._original.name) {
      patch.name = this._current.name
    }

    if (this._current.description !== this._original.description) {
      if (this._current.description === undefined || this._current.description === '') {
        patch.clearDescription = true
      } else {
        patch.description = this._current.description
      }
    }

    if (JSON.stringify(this._current.documentStyles) !== JSON.stringify(this._original.documentStyles)) {
      patch.documentStyles = this._current.documentStyles
    }

    if (JSON.stringify(this._current.pageSettings) !== JSON.stringify(this._original.pageSettings)) {
      if (this._current.pageSettings) {
        patch.pageSettings = this._current.pageSettings
      } else {
        patch.clearPageSettings = true
      }
    }

    if (JSON.stringify(this._current.blockStylePresets) !== JSON.stringify(this._original.blockStylePresets)) {
      if (Object.keys(this._current.blockStylePresets).length === 0) {
        patch.clearBlockStylePresets = true
      } else {
        patch.blockStylePresets = this._current.blockStylePresets
      }
    }

    return patch
  }

  /**
   * Marks current state as saved (resets dirty tracking).
   */
  markSaved(): void {
    this._original = structuredClone(this._current)
  }

  // -----------------------------------------------------------------------
  // Internal
  // -----------------------------------------------------------------------

  private _fireChange(): void {
    this.dispatchEvent(new Event('change'))
  }
}
