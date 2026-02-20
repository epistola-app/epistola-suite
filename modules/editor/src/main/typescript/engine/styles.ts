/**
 * Style resolution — pure functions for the style cascade.
 *
 * The cascade order (lowest → highest priority):
 *   1. Theme document styles
 *   2. Template document style overrides
 *   3. Theme block style preset (when node has stylePreset)
 *   4. Node inline styles (highest priority)
 *
 * Only properties marked `inheritable` in the style registry cascade
 * from resolved document styles into nodes.
 */

import type { StyleRegistry } from '@epistola/template-model/generated/style-registry.js'
import type { BlockStylePreset } from '@epistola/template-model/generated/theme.js'
import type { PageSettings } from '../types/index.js'

// ---------------------------------------------------------------------------
// Default page settings
// ---------------------------------------------------------------------------

export const DEFAULT_PAGE_SETTINGS: PageSettings = {
  format: 'A4',
  orientation: 'portrait',
  margins: { top: 20, right: 20, bottom: 20, left: 20 },
}

// ---------------------------------------------------------------------------
// Registry helpers
// ---------------------------------------------------------------------------

/** Get all inheritable property keys from the style registry. */
export function getInheritableKeys(registry: StyleRegistry): Set<string> {
  const keys = new Set<string>()
  for (const group of registry.groups) {
    for (const prop of group.properties) {
      if (prop.inheritable) {
        keys.add(prop.key)
      }
    }
  }
  return keys
}

// ---------------------------------------------------------------------------
// Document styles
// ---------------------------------------------------------------------------

/** Merge theme doc styles with template doc style overrides. */
export function resolveDocumentStyles(
  themeDocStyles: Record<string, unknown> | undefined,
  templateOverrides: Record<string, unknown> | undefined,
): Record<string, unknown> {
  return {
    ...themeDocStyles,
    ...templateOverrides,
  }
}

// ---------------------------------------------------------------------------
// Node styles
// ---------------------------------------------------------------------------

/**
 * Resolve a single node's final styles.
 *
 * Cascade (lowest → highest priority):
 *   1. Component default styles
 *   2. Inheritable document styles
 *   3. Theme block style preset
 *   4. Node inline styles
 *
 * Only inheritable keys from doc styles are applied; non-inheritable
 * doc styles (like backgroundColor) stay at the document level.
 */
export function resolveNodeStyles(
  resolvedDocStyles: Record<string, unknown>,
  inheritableKeys: Set<string>,
  presetStyles: Record<string, unknown> | undefined,
  inlineStyles: Record<string, unknown> | undefined,
  defaultStyles?: Record<string, unknown>,
): Record<string, unknown> {
  // Start with component defaults (lowest priority)
  const result: Record<string, unknown> = defaultStyles ? { ...defaultStyles } : {}

  // Overlay inheritable doc styles
  for (const key of inheritableKeys) {
    if (key in resolvedDocStyles) {
      result[key] = resolvedDocStyles[key]
    }
  }

  // Overlay preset styles
  if (presetStyles) {
    Object.assign(result, presetStyles)
  }

  // Overlay inline styles
  if (inlineStyles) {
    Object.assign(result, inlineStyles)
  }

  return result
}

// ---------------------------------------------------------------------------
// Page settings
// ---------------------------------------------------------------------------

/** Merge theme page settings with template page setting overrides. */
export function resolvePageSettings(
  themeSettings: PageSettings | undefined,
  templateOverrides: Partial<PageSettings> | undefined,
): PageSettings {
  const base = themeSettings ?? DEFAULT_PAGE_SETTINGS

  if (!templateOverrides) return { ...base }

  return {
    format: templateOverrides.format ?? base.format,
    orientation: templateOverrides.orientation ?? base.orientation,
    margins: templateOverrides.margins ?? base.margins,
    backgroundColor: templateOverrides.backgroundColor ?? base.backgroundColor,
  }
}

// ---------------------------------------------------------------------------
// Preset resolution
// ---------------------------------------------------------------------------

/** Resolve the preset for a node from theme blockStylePresets. */
export function resolvePresetStyles(
  presets: Record<string, BlockStylePreset> | undefined,
  presetName: string | undefined,
): Record<string, unknown> | undefined {
  if (!presets || !presetName) return undefined
  const preset = presets[presetName]
  return preset?.styles
}
