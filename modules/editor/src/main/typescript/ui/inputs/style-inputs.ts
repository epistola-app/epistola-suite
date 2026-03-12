/**
 * Reusable input template functions for the style inspector.
 *
 * These are Lit template functions (not separate web components)
 * to keep things simple and avoid component overhead.
 */

import { html, nothing } from 'lit'
import {
  formatValueWithUnit,
  parseSpacingValue,
  parseValueWithUnit,
  type SpacingValue,
} from '../../engine/style-values.js'

// ---------------------------------------------------------------------------
// Value + unit parsing
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Unit input: number + unit dropdown
// ---------------------------------------------------------------------------

export function renderUnitInput(
  value: unknown,
  units: string[],
  onChange: (value: string) => void,
): unknown {
  const defaultUnit = units[0] ?? 'px'
  const parsed = parseValueWithUnit(value, defaultUnit)

  const handleNumberChange = (e: Event) => {
    const num = parseFloat((e.target as HTMLInputElement).value) || 0
    onChange(formatValueWithUnit(num, parsed.unit))
  }

  const handleUnitChange = (e: Event) => {
    const newUnit = (e.target as HTMLSelectElement).value
    onChange(formatValueWithUnit(parsed.value, newUnit))
  }

  return html`
    <div class="style-unit-input">
      <input
        type="number"
        class="ep-input style-unit-number"
        .value=${String(parsed.value)}
        @change=${handleNumberChange}
      />
      <select
        class="ep-select style-unit-select"
        @change=${handleUnitChange}
      >
        ${units.map(u => html`
          <option .value=${u} ?selected=${u === parsed.unit}>${u}</option>
        `)}
      </select>
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

export function renderSpacingInput(
  value: unknown,
  units: string[],
  onChange: (value: SpacingValue) => void,
): unknown {
  const defaultUnit = units[0] ?? 'px'
  const parsed = parseSpacingValue(value, defaultUnit)
  const sides = ['top', 'right', 'bottom', 'left'] as const

  const handleSideChange = (side: string, newValue: string) => {
    onChange({ ...parsed, [side]: newValue })
  }

  return html`
    <div class="style-spacing-input">
      ${sides.map(side => {
        const sideParsed = parseValueWithUnit(parsed[side], defaultUnit)
        return html`
          <div class="style-spacing-side">
            <span class="style-spacing-label">${side[0].toUpperCase()}</span>
            <input
              type="number"
              class="ep-input style-spacing-number"
              .value=${String(sideParsed.value)}
              @change=${(e: Event) => {
                const num = parseFloat((e.target as HTMLInputElement).value) || 0
                handleSideChange(side, formatValueWithUnit(num, sideParsed.unit))
              }}
            />
          </div>
        `
      })}
      ${units.length > 1 ? html`
        <select
          class="ep-select style-spacing-unit"
          @change=${(e: Event) => {
            const newUnit = (e.target as HTMLSelectElement).value
            const result: SpacingValue = { top: '', right: '', bottom: '', left: '' }
            for (const side of sides) {
              const p = parseValueWithUnit(parsed[side], defaultUnit)
              result[side] = formatValueWithUnit(p.value, newUnit)
            }
            onChange(result)
          }}
        >
          ${units.map(u => html`<option .value=${u}>${u}</option>`)}
        </select>
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
    styles[`${prefix}${suffix}`] = sideValue
  }
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

  if (compound != null) {
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
