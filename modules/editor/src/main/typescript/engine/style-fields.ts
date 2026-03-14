import { defaultStyleSystem } from '@epistola/template-model/style-system'
import type { StyleField } from '@epistola/template-model/generated/style-system.js'
import {
  expandBorderRadiusToStyles,
  expandBorderToStyles,
  parseSpacingValue,
  readBorderFromStyles,
  readBorderRadiusFromStyles,
} from './style-values.js'

export type { StyleField }

const canonicalPropertiesByKey = new Map(
  defaultStyleSystem.canonicalProperties.map(property => [property.key, property]),
)

const styleFieldsByKey = new Map<string, StyleField>()
for (const group of defaultStyleSystem.editorGroups) {
  for (const field of group.fields) {
    styleFieldsByKey.set(field.key, field)
  }
}

function getMappedPropertyKeys(field: StyleField): string[] {
  if (field.propertyKey) return [field.propertyKey]
  if (field.spacingProperties) {
    return [
      field.spacingProperties.top,
      field.spacingProperties.right,
      field.spacingProperties.bottom,
      field.spacingProperties.left,
    ]
  }
  return []
}

function isFieldInheritable(field: StyleField): boolean {
  const propertyKeys = getMappedPropertyKeys(field)
  return propertyKeys.length > 0 && propertyKeys.every(
    propertyKey => canonicalPropertiesByKey.get(propertyKey)?.inheritable === true,
  )
}

/**
 * All editor field groups from the style system.
 * UI components iterate these groups to render style controls.
 */
export const defaultStyleFieldGroups = defaultStyleSystem.editorGroups

/**
 * Set of inheritable canonical property keys.
 * Used by the style cascade to determine which document styles apply to nodes.
 */
export const defaultInheritableStyleKeys = new Set(
  defaultStyleSystem.canonicalProperties
    .filter(property => property.inheritable)
    .map(property => property.key),
)

/**
 * Look up a style field definition by its key.
 */
export function getStyleFieldDefinition(fieldKey: string): StyleField | undefined {
  return styleFieldsByKey.get(fieldKey)
}

/**
 * Check if a style field is inheritable (cascades from document to nodes).
 */
export function isStyleFieldInheritable(fieldKey: string): boolean {
  const field = getStyleFieldDefinition(fieldKey)
  return field ? isFieldInheritable(field) : false
}

/**
 * Read the current value of a style field from a styles record.
 * Handles composite fields (spacing, border, borderRadius) by reading
 * their constituent canonical properties.
 */
export function readStyleFieldValue(
  fieldKey: string,
  styles: Record<string, unknown>,
): unknown {
  const field = getStyleFieldDefinition(fieldKey)
  if (!field) return styles[fieldKey]

  if (field.spacingProperties) {
    const { top, right, bottom, left } = field.spacingProperties
    const compound = styles[field.key]
    if (!(top in styles) && !(right in styles) && !(bottom in styles) && !(left in styles) && compound == null) {
      return undefined
    }

    if (compound != null) {
      return parseSpacingValue(compound, field.units?.[0] ?? 'px')
    }

    const zero = `0${field.units?.[0] ?? 'px'}`
    return {
      top: String(styles[top] ?? zero),
      right: String(styles[right] ?? zero),
      bottom: String(styles[bottom] ?? zero),
      left: String(styles[left] ?? zero),
    }
  }

  if (field.borderProperties) {
    return readBorderFromStyles('border', styles)
  }

  if (field.borderRadiusProperties) {
    return readBorderRadiusFromStyles(styles)
  }

  return styles[field.propertyKey ?? field.key]
}

/**
 * Apply a style field value to a styles record.
 * Handles composite fields by expanding them to their constituent canonical properties.
 */
export function applyStyleFieldValue(
  fieldKey: string,
  value: unknown,
  styles: Record<string, unknown>,
): void {
  const field = getStyleFieldDefinition(fieldKey)
  if (!field) {
    if (value === undefined || value === '' || value === null) {
      delete styles[fieldKey]
    } else {
      styles[fieldKey] = value
    }
    return
  }

  if (field.spacingProperties) {
    if (value === undefined || value === '' || value === null) {
      delete styles[field.spacingProperties.top]
      delete styles[field.spacingProperties.right]
      delete styles[field.spacingProperties.bottom]
      delete styles[field.spacingProperties.left]
      delete styles[field.key]
      return
    }

    const spacing = parseSpacingValue(value, field.units?.[0] ?? 'px')
    styles[field.spacingProperties.top] = spacing.top
    styles[field.spacingProperties.right] = spacing.right
    styles[field.spacingProperties.bottom] = spacing.bottom
    styles[field.spacingProperties.left] = spacing.left
    delete styles[field.key]
    return
  }

  if (field.borderProperties) {
    // Check if value is empty/cleared (all sides have undefined width/style/color)
    const isEmptyValue = (val: unknown): boolean => {
      if (val === undefined || val === '' || val === null) return true
      const bv = val as Record<string, { width?: string; style?: string; color?: string }>
      return ['top', 'right', 'bottom', 'left'].every(side => {
        const s = bv[side]
        return !s || (s.width === undefined && s.style === undefined && s.color === undefined)
      })
    }

    if (isEmptyValue(value)) {
      // Clear all border properties
      for (const side of ['top', 'right', 'bottom', 'left'] as const) {
        const mapping = field.borderProperties![side]
        delete styles[mapping.width]
        delete styles[mapping.style]
        delete styles[mapping.color]
      }
      return
    }
    // Value should be a BorderValue
    const borderValue = value as import('./style-values.js').BorderValue
    expandBorderToStyles('border', borderValue, styles)
    return
  }

  if (field.borderRadiusProperties) {
    // Check if value is empty/cleared (all corners are undefined)
    const isEmptyValue = (val: unknown): boolean => {
      if (val === undefined || val === '' || val === null) return true
      const rv = val as Record<string, string | undefined>
      return ['topLeft', 'topRight', 'bottomRight', 'bottomLeft'].every(
        corner => rv[corner] === undefined
      )
    }

    if (isEmptyValue(value)) {
      // Clear all radius properties
      delete styles[field.borderRadiusProperties.topLeft]
      delete styles[field.borderRadiusProperties.topRight]
      delete styles[field.borderRadiusProperties.bottomRight]
      delete styles[field.borderRadiusProperties.bottomLeft]
      return
    }
    // Value should be a BorderRadiusValue
    const radiusValue = value as import('./style-values.js').BorderRadiusValue
    expandBorderRadiusToStyles(radiusValue, styles)
    return
  }

  const propertyKey = field.propertyKey ?? field.key
  if (value === undefined || value === '' || value === null) {
    delete styles[propertyKey]
  } else {
    styles[propertyKey] = value
  }
}
