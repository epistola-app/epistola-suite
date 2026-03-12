import { defaultStyleSystem } from '@epistola/template-model/style-system'
import type { StyleSystem } from '@epistola/template-model/generated/style-system.js'
import type {
  StyleProperty,
  StyleRegistry,
} from '@epistola/template-model/generated/style-registry.js'
import { parseSpacingValue } from './style-values.js'

type StyleFieldDefinition = StyleSystem['editorGroups'][number]['fields'][number]

const canonicalPropertiesByKey = new Map(
  defaultStyleSystem.canonicalProperties.map(property => [property.key, property]),
)

const styleFieldsByKey = new Map<string, StyleFieldDefinition>()
for (const group of defaultStyleSystem.editorGroups) {
  for (const field of group.fields) {
    styleFieldsByKey.set(field.key, field)
  }
}

function getMappedPropertyKeys(field: StyleFieldDefinition): string[] {
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

function isFieldInheritable(field: StyleFieldDefinition): boolean {
  const propertyKeys = getMappedPropertyKeys(field)
  return propertyKeys.length > 0 && propertyKeys.every(
    propertyKey => canonicalPropertiesByKey.get(propertyKey)?.inheritable === true,
  )
}

function toLegacyStyleProperty(field: StyleFieldDefinition): StyleProperty {
  return {
    key: field.key,
    label: field.label,
    type: field.control,
    options: field.options?.map(option => ({
      label: option.label,
      value: option.value,
    })),
    units: field.units,
    inheritable: isFieldInheritable(field),
  }
}

export const defaultStyleRegistry: StyleRegistry = {
  groups: defaultStyleSystem.editorGroups.map(group => ({
    name: group.name,
    label: group.label,
    properties: group.fields.map(toLegacyStyleProperty),
  })),
}

export const defaultInheritableStyleKeys = new Set(
  defaultStyleSystem.canonicalProperties
    .filter(property => property.inheritable)
    .map(property => property.key),
)

export function getStyleFieldDefinition(fieldKey: string): StyleFieldDefinition | undefined {
  return styleFieldsByKey.get(fieldKey)
}

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

  return styles[field.propertyKey ?? field.key]
}

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

  const propertyKey = field.propertyKey ?? field.key
  if (value === undefined || value === '' || value === null) {
    delete styles[propertyKey]
  } else {
    styles[propertyKey] = value
  }
}
