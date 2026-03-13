/**
 * Generic box/corner value interface for composite style properties.
 *
 * Used for spacing (margin/padding), border-radius, border-width, etc.
 * undefined = use default/inherit
 * string = explicit value
 */
export interface BoxValue {
  top: string | undefined
  right: string | undefined
  bottom: string | undefined
  left: string | undefined
}

/**
 * @deprecated Use BoxValue instead. Kept for backward compatibility.
 */
export type SpacingValue = BoxValue

export interface ParsedUnit {
  value: number
  unit: string
}

export function parseValueWithUnit(raw: unknown, defaultUnit: string): ParsedUnit {
  if (raw == null || raw === '') return { value: 0, unit: defaultUnit }
  const str = String(raw)
  const match = str.match(/^(-?[\d.]+)\s*([a-z%]+)?$/i)
  if (!match) return { value: 0, unit: defaultUnit }
  return { value: parseFloat(match[1]), unit: match[2] || defaultUnit }
}

export function formatValueWithUnit(value: number, unit: string): string {
  return `${value}${unit}`
}

/**
 * Check if a BoxValue has any explicitly defined sides.
 */
export function hasExplicitValues(value: BoxValue): boolean {
  return value.top !== undefined ||
    value.right !== undefined ||
    value.bottom !== undefined ||
    value.left !== undefined
}

/**
 * Check if all sides of a BoxValue are undefined (all using defaults).
 */
export function isAllDefault(value: BoxValue): boolean {
  return value.top === undefined &&
    value.right === undefined &&
    value.bottom === undefined &&
    value.left === undefined
}

/**
 * Parse a raw value into a BoxValue.
 *
 * For missing sides, returns undefined (meaning "use default").
 * For explicit values, returns the string value.
 *
 * LEGACY FALLBACK: This function handles legacy data formats for backward compatibility.
 * - Object format: { top: '10px', right: '5px' } - used in old stored data
 * - CSS shorthand strings: "10px", "10px 20px", etc. - used in legacy compound keys
 *
 * New code should work with BoxValue directly and not rely on this parsing.
 */
export function parseBoxValue(raw: unknown, _defaultUnit: string): BoxValue {
  if (raw == null) {
    return { top: undefined, right: undefined, bottom: undefined, left: undefined }
  }

  // Handle object format (legacy stored data like { top: '10px', bottom: '5px' })
  // Excludes arrays since typeof [] === 'object'
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    const obj = raw as Record<string, unknown>
    return {
      top: obj.top != null ? String(obj.top) : undefined,
      right: obj.right != null ? String(obj.right) : undefined,
      bottom: obj.bottom != null ? String(obj.bottom) : undefined,
      left: obj.left != null ? String(obj.left) : undefined,
    }
  }

  const str = String(raw).trim()
  if (str === '') {
    return { top: undefined, right: undefined, bottom: undefined, left: undefined }
  }

  // CSS shorthand syntax: "10px" or "10px 20px" or "10px 20px 30px" or "10px 20px 30px 40px"
  // LEGACY: Used for parsing old compound key values like margin: "10px 20px"
  const parts = str.split(/\s+/)
  if (parts.length === 1) {
    const val = parts[0]
    return { top: val, right: val, bottom: val, left: val }
  }
  if (parts.length === 2) {
    return { top: parts[0], right: parts[1], bottom: parts[0], left: parts[1] }
  }
  if (parts.length === 3) {
    return { top: parts[0], right: parts[1], bottom: parts[2], left: parts[1] }
  }
  return { top: parts[0], right: parts[1], bottom: parts[2], left: parts[3] }
}

/**
 * @deprecated Use parseBoxValue instead.
 */
export function parseSpacingValue(raw: unknown, _defaultUnit: string): BoxValue {
  return parseBoxValue(raw, _defaultUnit)
}

/**
 * Get the effective value for a side, falling back to default if undefined.
 */
export function getEffectiveValue(value: BoxValue, side: keyof BoxValue, defaultValue: string): string {
  const explicit = value[side]
  return explicit !== undefined ? explicit : defaultValue
}

/**
 * Resolve a BoxValue to all explicit values using defaults for undefined sides.
 */
export function resolveBoxValue(value: BoxValue, defaults: BoxValue): BoxValue {
  return {
    top: value.top ?? defaults.top ?? '0px',
    right: value.right ?? defaults.right ?? '0px',
    bottom: value.bottom ?? defaults.bottom ?? '0px',
    left: value.left ?? defaults.left ?? '0px',
  }
}
