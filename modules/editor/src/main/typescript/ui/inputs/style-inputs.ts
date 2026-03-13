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
// Link state for box inputs
// ---------------------------------------------------------------------------

export interface BoxLinkState {
  all: boolean
  horizontal: boolean
  vertical: boolean
}

// ---------------------------------------------------------------------------
// Box input: for margin, padding, border-radius, etc.
// ---------------------------------------------------------------------------

export interface BoxInputConfig {
  id: string
  value: BoxValue
  defaults: BoxValue
  units: string[]
  linkState: BoxLinkState
  onChange: (value: BoxValue) => void
  onLinkStateChange: (state: BoxLinkState) => void
}

export function renderBoxInput(config: BoxInputConfig): unknown {
  const { id: _id, value, defaults, units, linkState, onChange, onLinkStateChange } = config
  const defaultUnit = units[0] ?? 'px'
  const { all, horizontal, vertical } = linkState

  const getDisplayValue = (sideValue: string | undefined): ParsedUnit => {
    const effective = sideValue ?? defaults.top ?? `0${defaultUnit}`
    return parseValueWithUnit(effective, defaultUnit)
  }

  const handleAllChange = (newValue: string) => {
    onChange({
      top: newValue,
      right: newValue,
      bottom: newValue,
      left: newValue,
    })
  }

  const handleHorizontalChange = (newValue: string) => {
    onChange({
      ...value,
      top: newValue,
      bottom: newValue,
    })
  }

  const handleVerticalChange = (newValue: string) => {
    onChange({
      ...value,
      right: newValue,
      left: newValue,
    })
  }

  const handleSideChange = (side: 'top' | 'right' | 'bottom' | 'left', newValue: string) => {
    onChange({
      ...value,
      [side]: newValue,
    })
  }

  const handleClearAll = () => {
    onChange({
      top: undefined,
      right: undefined,
      bottom: undefined,
      left: undefined,
    })
  }

  const handleUnitChange = (newUnit: string) => {
    const convertSide = (sideValue: string | undefined): string | undefined => {
      if (sideValue === undefined) return undefined
      const parsed = parseValueWithUnit(sideValue, defaultUnit)
      return formatValueWithUnit(parsed.value, newUnit)
    }

    onChange({
      top: convertSide(value.top),
      right: convertSide(value.right),
      bottom: convertSide(value.bottom),
      left: convertSide(value.left),
    })
  }

  const toggleAll = () => {
    const newAll = !all
    onLinkStateChange({
      all: newAll,
      horizontal: newAll ? false : horizontal,
      vertical: newAll ? false : vertical,
    })
  }

  const toggleHorizontal = () => {
    const newHorizontal = !horizontal
    onLinkStateChange({
      all: newHorizontal ? false : all,
      horizontal: newHorizontal,
      vertical,
    })
  }

  const toggleVertical = () => {
    const newVertical = !vertical
    onLinkStateChange({
      all: newVertical ? false : all,
      horizontal,
      vertical: newVertical,
    })
  }

  // Determine which inputs to show
  const showAllInput = all
  const showHorizontalInput = horizontal && !all
  const showVerticalInput = vertical && !all
  const showTopInput = !all && !horizontal
  const showRightInput = !all && !vertical
  const showBottomInput = !all && !horizontal
  const showLeftInput = !all && !vertical

  return html`
    <div class="style-box-input">
      <div class="style-box-links">
        <label class="style-box-link-label" title="Link all sides">
          <input
            type="checkbox"
            .checked=${all}
            @change=${toggleAll}
          />
          <span>All</span>
        </label>
        <label class="style-box-link-label" title="Link top and bottom">
          <input
            type="checkbox"
            .checked=${horizontal}
            @change=${toggleHorizontal}
          />
          <span>Horizontal</span>
        </label>
        <label class="style-box-link-label" title="Link right and left">
          <input
            type="checkbox"
            .checked=${vertical}
            @change=${toggleVertical}
          />
          <span>Vertical</span>
        </label>
        <button
          class="style-box-clear"
          title="Clear all to default"
          @click=${handleClearAll}
        >×</button>
      </div>
      <div class="style-box-sides">
        ${showAllInput ? html`
          <div class="style-box-group">
            <span class="style-box-label">All</span>
            <input
              type="number"
              class="ep-input style-box-number"
              .value=${String(getDisplayValue(value.top).value)}
              @change=${(e: Event) => {
                const num = parseFloat((e.target as HTMLInputElement).value) || 0
                handleAllChange(formatValueWithUnit(num, defaultUnit))
              }}
            />
          </div>
        ` : nothing}
        ${showHorizontalInput ? html`
          <div class="style-box-group">
            <span class="style-box-label">T/B</span>
            <input
              type="number"
              class="ep-input style-box-number"
              .value=${String(getDisplayValue(value.top).value)}
              @change=${(e: Event) => {
                const num = parseFloat((e.target as HTMLInputElement).value) || 0
                handleHorizontalChange(formatValueWithUnit(num, defaultUnit))
              }}
            />
          </div>
        ` : nothing}
        ${showVerticalInput ? html`
          <div class="style-box-group">
            <span class="style-box-label">R/L</span>
            <input
              type="number"
              class="ep-input style-box-number"
              .value=${String(getDisplayValue(value.left).value)}
              @change=${(e: Event) => {
                const num = parseFloat((e.target as HTMLInputElement).value) || 0
                handleVerticalChange(formatValueWithUnit(num, defaultUnit))
              }}
            />
          </div>
        ` : nothing}
        ${showTopInput ? html`
          <div class="style-box-side">
            <span class="style-box-label">T</span>
            <input
              type="number"
              class="ep-input style-box-number"
              .value=${String(getDisplayValue(value.top).value)}
              @change=${(e: Event) => {
                const num = parseFloat((e.target as HTMLInputElement).value) || 0
                handleSideChange('top', formatValueWithUnit(num, defaultUnit))
              }}
            />
          </div>
        ` : nothing}
        ${showRightInput ? html`
          <div class="style-box-side">
            <span class="style-box-label">R</span>
            <input
              type="number"
              class="ep-input style-box-number"
              .value=${String(getDisplayValue(value.right).value)}
              @change=${(e: Event) => {
                const num = parseFloat((e.target as HTMLInputElement).value) || 0
                handleSideChange('right', formatValueWithUnit(num, defaultUnit))
              }}
            />
          </div>
        ` : nothing}
        ${showBottomInput ? html`
          <div class="style-box-side">
            <span class="style-box-label">B</span>
            <input
              type="number"
              class="ep-input style-box-number"
              .value=${String(getDisplayValue(value.bottom).value)}
              @change=${(e: Event) => {
                const num = parseFloat((e.target as HTMLInputElement).value) || 0
                handleSideChange('bottom', formatValueWithUnit(num, defaultUnit))
              }}
            />
          </div>
        ` : nothing}
        ${showLeftInput ? html`
          <div class="style-box-side">
            <span class="style-box-label">L</span>
            <input
              type="number"
              class="ep-input style-box-number"
              .value=${String(getDisplayValue(value.left).value)}
              @change=${(e: Event) => {
                const num = parseFloat((e.target as HTMLInputElement).value) || 0
                handleSideChange('left', formatValueWithUnit(num, defaultUnit))
              }}
            />
          </div>
        ` : nothing}
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
  id: string,
  value: unknown,
  units: string[],
  onChange: (value: BoxValue) => void,
): unknown {
  return renderBoxInput({
    id,
    value: value as BoxValue,
    defaults: { top: '0px', right: '0px', bottom: '0px', left: '0px' },
    units,
    linkState: { all: false, horizontal: false, vertical: false },
    onChange,
    onLinkStateChange: () => { /* no-op for deprecated function */ },
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
