/**
 * Reusable input template functions for the style inspector.
 *
 * These are Lit template functions (not separate web components)
 * to keep things simple and avoid component overhead.
 */

import { html, nothing } from 'lit'

// ---------------------------------------------------------------------------
// Value + unit parsing
// ---------------------------------------------------------------------------

export interface ParsedUnit {
  value: number
  unit: string
}

/** Parse a CSS value like "16px" into { value: 16, unit: 'px' }. */
export function parseValueWithUnit(raw: unknown, defaultUnit: string): ParsedUnit {
  if (raw == null || raw === '') return { value: 0, unit: defaultUnit }
  const str = String(raw)
  const match = str.match(/^(-?[\d.]+)\s*([a-z%]+)?$/i)
  if (!match) return { value: 0, unit: defaultUnit }
  return { value: parseFloat(match[1]), unit: match[2] || defaultUnit }
}

/** Format a value + unit back to a CSS string. */
export function formatValueWithUnit(value: number, unit: string): string {
  return `${value}${unit}`
}

// ---------------------------------------------------------------------------
// Unit input: number + unit dropdown
// ---------------------------------------------------------------------------

export function renderUnitInput(
  value: unknown,
  units: string[],
  onChange: (value: string) => void,
  baseUnit: number = DEFAULT_SPACING_UNIT,
): unknown {
  const defaultUnit = units[0] ?? 'pt'
  const parsed = parseValueWithUnit(value, defaultUnit)

  const handleNumberChange = (e: Event) => {
    const num = parseFloat((e.target as HTMLInputElement).value) || 0
    onChange(formatValueWithUnit(num, parsed.unit))
  }

  const handleUnitChange = (e: Event) => {
    const newUnit = (e.target as HTMLSelectElement).value
    const oldUnit = parsed.unit
    let newValue = parsed.value
    // Convert between sp and pt
    if (oldUnit === 'pt' && newUnit === 'sp') {
      newValue = parseFloat(nearestSpacingStep(parsed.value, baseUnit))
    } else if (oldUnit === 'sp' && newUnit === 'pt') {
      newValue = parsed.value * baseUnit
    }
    onChange(formatValueWithUnit(newValue, newUnit))
  }

  return html`
    <div class="style-unit-input">
      <input
        type="number"
        class="ep-input style-unit-number"
        step=${parsed.unit === 'sp' ? '0.5' : '1'}
        .value=${String(parsed.value)}
        @change=${handleNumberChange}
      />
      ${units.length > 1 ? html`
        <select
          class="ep-select style-unit-select"
          @change=${handleUnitChange}
        >
          ${units.map(u => html`
            <option .value=${u} ?selected=${u === parsed.unit}>${u}</option>
          `)}
        </select>
      ` : html`<span class="style-unit-label">${defaultUnit}</span>`}
    </div>
  `
}

// ---------------------------------------------------------------------------
// Color input: native color picker + text input
// ---------------------------------------------------------------------------

export function renderColorInput(
  value: unknown,
  onChange: (value: string) => void,
): unknown {
  const colorValue = value != null ? String(value) : ''
  // Ensure the color picker gets a valid hex value
  const pickerValue = colorValue.startsWith('#') ? colorValue : '#000000'

  return html`
    <div class="style-color-input">
      <input
        type="color"
        class="style-color-picker"
        .value=${pickerValue}
        @change=${(e: Event) => onChange((e.target as HTMLInputElement).value)}
      />
      <input
        type="text"
        class="ep-input style-color-text"
        .value=${colorValue}
        @change=${(e: Event) => onChange((e.target as HTMLInputElement).value)}
        placeholder="#000000"
      />
    </div>
  `
}

// ---------------------------------------------------------------------------
// Spacing scale: systematic spacing based on multiples of a base unit.
// Mirrors SpacingScale.kt on the backend.
// ---------------------------------------------------------------------------

export const SPACING_SCALE = [
  { token: '0', multiplier: 0 },
  { token: '0.5', multiplier: 0.5 },
  { token: '1', multiplier: 1 },
  { token: '1.5', multiplier: 1.5 },
  { token: '2', multiplier: 2 },
  { token: '3', multiplier: 3 },
  { token: '4', multiplier: 4 },
  { token: '5', multiplier: 5 },
  { token: '6', multiplier: 6 },
  { token: '8', multiplier: 8 },
  { token: '10', multiplier: 10 },
  { token: '12', multiplier: 12 },
  { token: '16', multiplier: 16 },
] as const

export const DEFAULT_SPACING_UNIT = 4 // pt

/** Find the nearest spacing scale step for a given pt value. */
export function nearestSpacingStep(ptValue: number, baseUnit: number): string {
  const multiplier = ptValue / baseUnit
  let bestToken = SPACING_SCALE[0].token as string
  let bestDist = Math.abs(multiplier - SPACING_SCALE[0].multiplier)
  for (const s of SPACING_SCALE) {
    const dist = Math.abs(multiplier - s.multiplier)
    if (dist < bestDist) {
      bestToken = s.token
      bestDist = dist
    }
  }
  return bestToken
}

/**
 * Detect the current unit from a set of spacing side values.
 * Returns 'sp' if any side uses an sp() token, otherwise parses the unit suffix.
 */
function detectSpacingUnit(parsed: SpacingValue, defaultUnit: string): string {
  for (const side of ['top', 'right', 'bottom', 'left'] as const) {
    const v = parsed[side]
    if (v && isSpacingToken(v)) return 'sp'
    if (v && v !== '0' && v !== '') {
      const p = parseValueWithUnit(v, defaultUnit)
      if (p.unit && p.unit !== defaultUnit) return p.unit
    }
  }
  return defaultUnit
}

/**
 * Convert a single side value between sp and pt.
 */
function convertSideValue(value: string, fromUnit: string, toUnit: string, baseUnit: number): string {
  if (fromUnit === toUnit) return value

  if (fromUnit === 'sp' && toUnit === 'pt') {
    const step = parseSpacingToken(value)
    const multiplier = step != null ? (parseFloat(step) || 0) : 0
    return formatValueWithUnit(multiplier * baseUnit, 'pt')
  }

  if (fromUnit === 'pt' && toUnit === 'sp') {
    const p = parseValueWithUnit(value, 'pt')
    return formatSpacingToken(nearestSpacingStep(p.value, baseUnit))
  }

  return value
}

/** Check if a CSS value is an sp token (e.g., "2sp", "0.5sp"). */
export function isSpacingToken(value: string): boolean {
  return /^[\d.]+sp$/.test(value)
}

/** Parse an sp token, returning the step name or null. */
export function parseSpacingToken(value: string): string | null {
  const match = value.match(/^([\d.]+)sp$/)
  return match ? match[1] : null
}

/** Format a spacing token: "2sp" */
export function formatSpacingToken(step: string): string {
  return `${step}sp`
}

// ---------------------------------------------------------------------------
// Spacing input: 4-value (top/right/bottom/left)
// ---------------------------------------------------------------------------

export interface SpacingValue {
  top: string
  right: string
  bottom: string
  left: string
}

/** Parse a spacing value — can be a string shorthand or an object. */
export function parseSpacingValue(raw: unknown, defaultUnit: string): SpacingValue {
  if (raw == null) {
    return { top: `0${defaultUnit}`, right: `0${defaultUnit}`, bottom: `0${defaultUnit}`, left: `0${defaultUnit}` }
  }

  if (typeof raw === 'object' && raw !== null) {
    const obj = raw as Record<string, unknown>
    return {
      top: obj.top != null ? String(obj.top) : `0${defaultUnit}`,
      right: obj.right != null ? String(obj.right) : `0${defaultUnit}`,
      bottom: obj.bottom != null ? String(obj.bottom) : `0${defaultUnit}`,
      left: obj.left != null ? String(obj.left) : `0${defaultUnit}`,
    }
  }

  // CSS shorthand string: "10px" or "10px 20px" or "10px 20px 30px 40px"
  const str = String(raw)
  const parts = str.split(/\s+/)
  if (parts.length === 1) return { top: parts[0], right: parts[0], bottom: parts[0], left: parts[0] }
  if (parts.length === 2) return { top: parts[0], right: parts[1], bottom: parts[0], left: parts[1] }
  if (parts.length === 3) return { top: parts[0], right: parts[1], bottom: parts[2], left: parts[1] }
  return { top: parts[0], right: parts[1], bottom: parts[2], left: parts[3] }
}

/**
 * Renders a spacing input with 4 side controls (T/R/B/L) + a shared unit selector.
 *
 * All units use number inputs — sp values are multipliers (e.g., 2 = 2sp = 8pt),
 * pt values are absolute points. Switching units converts values automatically.
 */
export function renderSpacingInput(
  value: unknown,
  units: string[],
  onChange: (value: SpacingValue) => void,
  baseUnit: number = DEFAULT_SPACING_UNIT,
): unknown {
  const firstAbsUnit = units.find(u => u !== 'sp') ?? 'pt'
  const parsed = parseSpacingValue(value, firstAbsUnit)
  const currentUnit = detectSpacingUnit(parsed, firstAbsUnit)
  const sides = ['top', 'right', 'bottom', 'left'] as const

  const handleSideChange = (side: string, newValue: string) => {
    onChange({ ...parsed, [side]: newValue })
  }

  /** Extract numeric value from a side, whether sp or pt. */
  const sideNumber = (sideValue: string): number => {
    if (currentUnit === 'sp') {
      return parseFloat(parseSpacingToken(sideValue) ?? '0') || 0
    }
    return parseValueWithUnit(sideValue, currentUnit).value
  }

  /** Format a number back with the current unit. */
  const formatSide = (num: number): string => {
    if (currentUnit === 'sp') return formatSpacingToken(String(num))
    return formatValueWithUnit(num, currentUnit)
  }

  return html`
    <div class="style-spacing-input">
      ${sides.map(side => html`
        <div class="style-spacing-side">
          <span class="style-spacing-label">${side[0].toUpperCase()}</span>
          <input
            type="number"
            class="ep-input style-spacing-number"
            step=${currentUnit === 'sp' ? '0.5' : '1'}
            min="0"
            .value=${String(sideNumber(parsed[side]))}
            @change=${(e: Event) => {
              const num = parseFloat((e.target as HTMLInputElement).value) || 0
              handleSideChange(side, formatSide(num))
            }}
          />
        </div>
      `)}
      ${units.length > 1 ? html`
        <div class="style-spacing-side">
          <span class="style-spacing-label">&nbsp;</span>
          <select
            class="ep-select style-spacing-unit"
            @change=${(e: Event) => {
              const newUnit = (e.target as HTMLSelectElement).value
              const result: SpacingValue = { top: '', right: '', bottom: '', left: '' }
              for (const side of sides) {
                result[side] = convertSideValue(parsed[side], currentUnit, newUnit, baseUnit)
              }
              onChange(result)
            }}
          >
            ${units.map(u => html`<option .value=${u} ?selected=${u === currentUnit}>${u}</option>`)}
          </select>
        </div>
      ` : nothing}
    </div>
  `
}

// ---------------------------------------------------------------------------
// Spacing: individual key expansion/reading
// ---------------------------------------------------------------------------

/**
 * Expand a compound SpacingValue into individual style keys.
 *
 * e.g. expandSpacingToStyles('margin', { top: '10px', right: '0px', bottom: '5px', left: '0px' }, styles)
 * → sets marginTop: '10px', marginRight: '0px', marginBottom: '5px', marginLeft: '0px'
 *   and deletes the compound 'margin' key.
 */
export function expandSpacingToStyles(
  prefix: string,
  value: SpacingValue,
  styles: Record<string, unknown>,
): void {
  const sides: Record<string, string> = {
    Top: value.top,
    Right: value.right,
    Bottom: value.bottom,
    Left: value.left,
  }
  for (const [suffix, sideValue] of Object.entries(sides)) {
    const key = `${prefix}${suffix}`
    if (sideValue && sideValue !== '0pt' && sideValue !== '0sp') {
      styles[key] = sideValue
    } else {
      delete styles[key]
    }
  }
  // Remove the compound key if it exists
  delete styles[prefix]
}

/**
 * Read individual style keys back into a compound SpacingValue.
 *
 * e.g. readSpacingFromStyles('margin', { marginTop: '10px', marginBottom: '5px' })
 * → { top: '10px', right: '0px', bottom: '5px', left: '0px' }
 *
 * Returns undefined if no individual keys are set.
 */
export function readSpacingFromStyles(
  prefix: string,
  styles: Record<string, unknown>,
  defaultUnit = 'px',
): SpacingValue | undefined {
  const top = styles[`${prefix}Top`]
  const right = styles[`${prefix}Right`]
  const bottom = styles[`${prefix}Bottom`]
  const left = styles[`${prefix}Left`]

  // Also check for legacy compound value
  const compound = styles[prefix]

  if (top == null && right == null && bottom == null && left == null && compound == null) {
    return undefined
  }

  // If a legacy compound object is present, read from it
  if (compound != null && typeof compound === 'object') {
    return parseSpacingValue(compound, defaultUnit)
  }

  const zero = `0${defaultUnit}`
  return {
    top: top != null ? String(top) : zero,
    right: right != null ? String(right) : zero,
    bottom: bottom != null ? String(bottom) : zero,
    left: left != null ? String(left) : zero,
  }
}

// ---------------------------------------------------------------------------
// Select input (for style properties)
// ---------------------------------------------------------------------------

export function renderSelectInput(
  value: unknown,
  options: { label: string; value: string }[],
  onChange: (value: string) => void,
): unknown {
  const currentValue = value != null ? String(value) : ''

  return html`
    <select
      class="ep-select"
      @change=${(e: Event) => onChange((e.target as HTMLSelectElement).value)}
    >
      <option value="" ?selected=${!currentValue}>—</option>
      ${options.map(opt => html`
        <option .value=${opt.value} ?selected=${currentValue === opt.value}>${opt.label}</option>
      `)}
    </select>
  `
}
