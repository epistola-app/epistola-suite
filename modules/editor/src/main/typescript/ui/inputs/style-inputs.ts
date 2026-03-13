/**
 * Reusable input template functions for the style inspector.
 *
 * These are Lit template functions (not separate web components)
 * to keep things simple and avoid component overhead.
 */

import { html, nothing } from 'lit'
import {
  formatValueWithUnit,
  parseValueWithUnit,
  parseBoxValue,
  type BoxValue,
  type ParsedUnit,
  getEffectiveValue,
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

// ---------------------------------------------------------------------------
// Link mode type for box inputs
// ---------------------------------------------------------------------------

export type LinkMode = 'all' | 'horizontal' | 'vertical' | 'none'

// ---------------------------------------------------------------------------
// Box input: for margin, padding, border-radius, etc.
// ---------------------------------------------------------------------------

export interface BoxInputConfig {
  value: BoxValue
  defaults: BoxValue
  units: string[]
  linkMode: LinkMode
  onChange: (value: BoxValue) => void
  onLinkModeChange: (mode: LinkMode) => void
}

export function renderBoxInput(config: BoxInputConfig): unknown {
  const { value, defaults, units, linkMode, onChange, onLinkModeChange } = config
  const defaultUnit = units[0] ?? 'px'
  const sides = ['top', 'right', 'bottom', 'left'] as const

  const getDisplayValue = (side: typeof sides[number]): ParsedUnit => {
    const effective = getEffectiveValue(value, side, defaults[side] ?? `0${defaultUnit}`)
    return parseValueWithUnit(effective, defaultUnit)
  }

  const isExplicit = (side: typeof sides[number]): boolean => {
    return value[side] !== undefined
  }

  const handleSideChange = (side: typeof sides[number], newValue: string) => {
    const newBoxValue: BoxValue = { ...value, [side]: newValue }

    // Apply linking logic
    if (linkMode === 'all') {
      newBoxValue.right = newValue
      newBoxValue.bottom = newValue
      newBoxValue.left = newValue
    } else if (linkMode === 'horizontal' && (side === 'left' || side === 'right')) {
      newBoxValue.right = side === 'left' ? newValue : newValue
      newBoxValue.left = side === 'right' ? newValue : newValue
    } else if (linkMode === 'vertical' && (side === 'top' || side === 'bottom')) {
      newBoxValue.top = side === 'bottom' ? newValue : newValue
      newBoxValue.bottom = side === 'top' ? newValue : newValue
    }

    onChange(newBoxValue)
  }

  const handleClear = (side: typeof sides[number]) => {
    const newBoxValue: BoxValue = { ...value, [side]: undefined }
    onChange(newBoxValue)
  }

  const handleUnitChange = (newUnit: string) => {
    const newBoxValue: BoxValue = { top: undefined, right: undefined, bottom: undefined, left: undefined }
    for (const side of sides) {
      const current = getDisplayValue(side)
      newBoxValue[side] = formatValueWithUnit(current.value, newUnit)
    }
    onChange(newBoxValue)
  }

  const setLinkMode = (mode: LinkMode) => {
    onLinkModeChange(mode)

    // If switching to a link mode, sync values appropriately
    if (mode === 'all' && value.top !== undefined) {
      const newValue = value.top
      onChange({
        top: newValue,
        right: newValue,
        bottom: newValue,
        left: newValue,
      })
    } else if (mode === 'horizontal' && (value.left !== undefined || value.right !== undefined)) {
      const newValue = value.left ?? value.right!
      onChange({
        ...value,
        left: newValue,
        right: newValue,
      })
    } else if (mode === 'vertical' && (value.top !== undefined || value.bottom !== undefined)) {
      const newValue = value.top ?? value.bottom!
      onChange({
        ...value,
        top: newValue,
        bottom: newValue,
      })
    }
  }

  return html`
    <div class="style-box-input">
      <div class="style-box-links">
        <label class="style-box-link-label" title="Link all sides">
          <input
            type="radio"
            name="link-mode"
            .checked=${linkMode === 'all'}
            @change=${() => setLinkMode('all')}
          />
          <span>All</span>
        </label>
        <label class="style-box-link-label" title="Link horizontal (left/right)">
          <input
            type="radio"
            name="link-mode"
            .checked=${linkMode === 'horizontal'}
            @change=${() => setLinkMode('horizontal')}
          />
          <span>Horizontal</span>
        </label>
        <label class="style-box-link-label" title="Link vertical (top/bottom)">
          <input
            type="radio"
            name="link-mode"
            .checked=${linkMode === 'vertical'}
            @change=${() => setLinkMode('vertical')}
          />
          <span>Vertical</span>
        </label>
        <label class="style-box-link-label" title="No linking">
          <input
            type="radio"
            name="link-mode"
            .checked=${linkMode === 'none'}
            @change=${() => setLinkMode('none')}
          />
          <span>None</span>
        </label>
      </div>
      <div class="style-box-sides">
        ${sides.map(side => {
    const displayValue = getDisplayValue(side)
    const explicit = isExplicit(side)
    return html`
            <div class="style-box-side ${explicit ? 'is-explicit' : ''}">
              <span class="style-box-label">${side[0].toUpperCase()}</span>
              <input
                type="number"
                class="ep-input style-box-number"
                .value=${String(displayValue.value)}
                @change=${(e: Event) => {
    const num = parseFloat((e.target as HTMLInputElement).value) || 0
    handleSideChange(side, formatValueWithUnit(num, displayValue.unit))
  }}
              />
              ${explicit ? html`
                <button
                  class="style-box-clear"
                  title="Clear to default"
                  @click=${() => handleClear(side)}
                >×</button>
              ` : nothing}
            </div>
          `
  })}
      </div>
      ${units.length > 1 ? html`
        <select
          class="ep-select style-box-unit"
          @change=${(e: Event) => handleUnitChange((e.target as HTMLSelectElement).value)}
        >
          ${units.map(u => html`<option .value=${u}>${u}</option>`)}
        </select>
      ` : nothing}
    </div>
  `
}

/**
 * @deprecated Use renderBoxInput instead.
 */
export function renderSpacingInput(
  value: unknown,
  units: string[],
  onChange: (value: BoxValue) => void,
): unknown {
  return renderBoxInput({
    value: value as BoxValue,
    defaults: { top: '0px', right: '0px', bottom: '0px', left: '0px' },
    units,
    linkMode: 'none',
    onChange,
    onLinkModeChange: () => { /* no-op for deprecated function */ },
  })
}

// ---------------------------------------------------------------------------
// Box: individual key expansion/reading
// ---------------------------------------------------------------------------

/**
 * Mapping from field key prefixes to canonical property keys.
 */
export interface BoxPropertyMapping {
  top: string
  right: string
  bottom: string
  left: string
}

/**
 * Expand a BoxValue into individual style keys.
 *
 * Only writes explicitly defined sides (not undefined).
 * This allows unset sides to inherit from defaults.
 *
 * e.g. expandBoxToStyles('margin', { top: '10px', right: undefined, bottom: undefined, left: undefined }, styles)
 * → sets marginTop: '10px' only
 *   and deletes the compound 'margin' key.
 */
export function expandBoxToStyles(
  prefix: string,
  value: BoxValue,
  styles: Record<string, unknown>,
): void {
  const mapping: Record<string, string | undefined> = {
    Top: value.top,
    Right: value.right,
    Bottom: value.bottom,
    Left: value.left,
  }

  for (const [suffix, sideValue] of Object.entries(mapping)) {
    if (sideValue !== undefined) {
      styles[`${prefix}${suffix}`] = sideValue
    }
  }

  delete styles[prefix]
}

/**
 * @deprecated Use expandBoxToStyles instead.
 */
export function expandSpacingToStyles(
  prefix: string,
  value: BoxValue,
  styles: Record<string, unknown>,
): void {
  expandBoxToStyles(prefix, value, styles)
}

/**
 * Read individual style keys back into a BoxValue.
 *
 * Returns undefined for sides that are not explicitly set.
 * This allows the UI to distinguish between "use default" and "explicitly set".
 *
 * e.g. readBoxFromStyles('margin', { marginTop: '10px' })
 * → { top: '10px', right: undefined, bottom: undefined, left: undefined }
 *
 * LEGACY FALLBACK: Also checks for legacy compound key (e.g., 'margin') for backward
 * compatibility with old stored data. Legacy values are parsed and expanded to all
 * sides since the old format didn't distinguish between explicit and default values.
 */
export function readBoxFromStyles(
  prefix: string,
  styles: Record<string, unknown>,
): BoxValue | undefined {
  const top = styles[`${prefix}Top`]
  const right = styles[`${prefix}Right`]
  const bottom = styles[`${prefix}Bottom`]
  const left = styles[`${prefix}Left`]

  // LEGACY: Check for old compound key format (e.g., 'margin' instead of 'marginTop', etc.)
  // Used for backward compatibility with templates created before the composite spacing refactor
  const compound = styles[prefix]

  const hasIndividual = top != null || right != null || bottom != null || left != null
  const hasCompound = compound != null

  if (!hasIndividual && !hasCompound) {
    return undefined
  }

  if (hasCompound) {
    // LEGACY: Parse old compound format and expand to all sides
    return parseBoxValue(compound, 'px')
  }

  return {
    top: top != null ? String(top) : undefined,
    right: right != null ? String(right) : undefined,
    bottom: bottom != null ? String(bottom) : undefined,
    left: left != null ? String(left) : undefined,
  }
}

/**
 * @deprecated Use readBoxFromStyles instead.
 */
export function readSpacingFromStyles(
  prefix: string,
  styles: Record<string, unknown>,
  _defaultUnit = 'px',
): BoxValue | undefined {
  return readBoxFromStyles(prefix, styles)
}

/**
 * Extract default values for a box property from component default styles.
 */
export function extractBoxDefaults(
  defaultStyles: Record<string, unknown> | undefined,
  mapping: BoxPropertyMapping,
): BoxValue {
  const defaults = defaultStyles || {}
  return {
    top: defaults[mapping.top] != null ? String(defaults[mapping.top]) : undefined,
    right: defaults[mapping.right] != null ? String(defaults[mapping.right]) : undefined,
    bottom: defaults[mapping.bottom] != null ? String(defaults[mapping.bottom]) : undefined,
    left: defaults[mapping.left] != null ? String(defaults[mapping.left]) : undefined,
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
