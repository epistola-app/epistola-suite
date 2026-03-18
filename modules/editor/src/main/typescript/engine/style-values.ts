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

// -----------------------------------------------------------------------------
// Border value types and helpers
// -----------------------------------------------------------------------------

/**
 * Border side value: width, style, and color together
 */
export interface BorderSideValue {
  width: string | undefined
  style: string | undefined
  color: string | undefined
}

/**
 * Complete border value for all four sides
 */
export interface BorderValue {
  top: BorderSideValue
  right: BorderSideValue
  bottom: BorderSideValue
  left: BorderSideValue
}

/**
 * Border radius value for corners
 */
export interface BorderRadiusValue {
  topLeft: string | undefined
  topRight: string | undefined
  bottomRight: string | undefined
  bottomLeft: string | undefined
}

/**
 * Check if a border side has all three values set (complete border definition)
 */
export function isBorderSideComplete(side: BorderSideValue): boolean {
  return side.width !== undefined && side.style !== undefined && side.color !== undefined
}

/**
 * Check if any border side has at least one value set
 */
export function hasBorderValues(border: BorderValue): boolean {
  return isBorderSidePartiallySet(border.top) ||
    isBorderSidePartiallySet(border.right) ||
    isBorderSidePartiallySet(border.bottom) ||
    isBorderSidePartiallySet(border.left)
}

function isBorderSidePartiallySet(side: BorderSideValue): boolean {
  return side.width !== undefined || side.style !== undefined || side.color !== undefined
}

/**
 * Read border values from individual style properties
 */
export function readBorderFromStyles(
  prefix: string,
  styles: Record<string, unknown>,
): BorderValue {
  const readSide = (side: string): BorderSideValue => ({
    width: styles[`${prefix}${side}Width`] != null ? String(styles[`${prefix}${side}Width`]) : undefined,
    style: styles[`${prefix}${side}Style`] != null ? String(styles[`${prefix}${side}Style`]) : undefined,
    color: styles[`${prefix}${side}Color`] != null ? String(styles[`${prefix}${side}Color`]) : undefined,
  })

  return {
    top: readSide('Top'),
    right: readSide('Right'),
    bottom: readSide('Bottom'),
    left: readSide('Left'),
  }
}

/**
 * Expand border values to individual style properties
 */
export function expandBorderToStyles(
  prefix: string,
  value: BorderValue,
  styles: Record<string, unknown>,
): void {
  const expandSide = (side: string, sideValue: BorderSideValue) => {
    if (sideValue.width !== undefined) {
      styles[`${prefix}${side}Width`] = sideValue.width
    }
    if (sideValue.style !== undefined) {
      styles[`${prefix}${side}Style`] = sideValue.style
    }
    if (sideValue.color !== undefined) {
      styles[`${prefix}${side}Color`] = sideValue.color
    }
  }

  expandSide('Top', value.top)
  expandSide('Right', value.right)
  expandSide('Bottom', value.bottom)
  expandSide('Left', value.left)
}

/**
 * Read border radius from individual style properties
 */
export function readBorderRadiusFromStyles(
  styles: Record<string, unknown>,
): BorderRadiusValue {
  return {
    topLeft: styles['borderTopLeftRadius'] != null ? String(styles['borderTopLeftRadius']) : undefined,
    topRight: styles['borderTopRightRadius'] != null ? String(styles['borderTopRightRadius']) : undefined,
    bottomRight: styles['borderBottomRightRadius'] != null ? String(styles['borderBottomRightRadius']) : undefined,
    bottomLeft: styles['borderBottomLeftRadius'] != null ? String(styles['borderBottomLeftRadius']) : undefined,
  }
}

/**
 * Expand border radius to individual style properties
 */
export function expandBorderRadiusToStyles(
  value: BorderRadiusValue,
  styles: Record<string, unknown>,
): void {
  if (value.topLeft !== undefined) {
    styles['borderTopLeftRadius'] = value.topLeft
  }
  if (value.topRight !== undefined) {
    styles['borderTopRightRadius'] = value.topRight
  }
  if (value.bottomRight !== undefined) {
    styles['borderBottomRightRadius'] = value.bottomRight
  }
  if (value.bottomLeft !== undefined) {
    styles['borderBottomLeftRadius'] = value.bottomLeft
  }
}
