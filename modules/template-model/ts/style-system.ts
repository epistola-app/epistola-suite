import styleSystemData from '../data/style-system/default-style-system.json'
import type { StyleSystem } from '../generated/style-system.js'

export const defaultStyleSystem = styleSystemData as StyleSystem

/**
 * Typography scale with font size multipliers for text elements.
 * Applied as: baseFontSize × multiplier = effectiveFontSize
 */
export const typographyScale = defaultStyleSystem.typographyScale

/**
 * Element types supported by the typography scale system.
 */
export type TypographyElementType = 'paragraph' | 'heading1' | 'heading2' | 'heading3'

/**
 * Calculate the effective font size for a given text element.
 *
 * If an explicit font size is provided, it takes precedence.
 * Otherwise, calculates from: baseFontSize × typographyScale multiplier.
 *
 * @param elementType - The type of text element (paragraph, heading1, heading2, heading3)
 * @param baseFontSize - The base font size (e.g., "12pt", "16px")
 * @param explicitFontSize - Optional explicit font size override (e.g., "24pt")
 * @returns The effective font size as a string (e.g., "24pt")
 */
export function calculateFontSize(
  elementType: TypographyElementType,
  baseFontSize: string,
  explicitFontSize?: string | null
): string {
  // If explicit font size is set, use it
  if (explicitFontSize) {
    return explicitFontSize
  }

  // Parse base font size value and unit
  const match = baseFontSize.match(/^([\d.]+)([a-z%]+)$/i)
  if (!match) {
    // Fallback: return base font size as-is if we can't parse it
    return baseFontSize
  }

  const baseValue = parseFloat(match[1])
  const unit = match[2]

  // Get multiplier from typography scale
  const multiplier = typographyScale[elementType]?.fontSizeMultiplier ?? 1.0

  // Calculate effective size
  const effectiveValue = baseValue * multiplier

  return `${effectiveValue}${unit}`
}

/**
 * Get the font size multiplier for a given text element type.
 *
 * @param elementType - The type of text element
 * @returns The multiplier (e.g., 2.0 for heading1)
 */
export function getFontSizeMultiplier(elementType: TypographyElementType): number {
  return typographyScale[elementType]?.fontSizeMultiplier ?? 1.0
}
