/**
 * Reusable input template functions for the style inspector.
 *
 * These are Lit template functions (not separate web components)
 * to keep things simple and avoid component overhead.
 */

import { html, nothing } from 'lit';

// ---------------------------------------------------------------------------
// Value + unit parsing
// ---------------------------------------------------------------------------

export interface ParsedUnit {
  value: number;
  unit: string;
}

/** Parse a CSS value like "16px" into { value: 16, unit: 'px' }. */
export function parseValueWithUnit(raw: unknown, defaultUnit: string): ParsedUnit {
  if (raw == null || raw === '') return { value: 0, unit: defaultUnit };
  const str = String(raw);
  const match = str.match(/^([\d.]+)\s*([a-z%]+)?$/i);
  if (!match) return { value: 0, unit: defaultUnit };
  return { value: parseFloat(match[1]), unit: match[2] || defaultUnit };
}

/** Format a value + unit back to a CSS string. */
export function formatValueWithUnit(value: number, unit: string): string {
  return `${value}${unit}`;
}

// ---------------------------------------------------------------------------
// Unit input: number + unit dropdown
// ---------------------------------------------------------------------------

export function renderUnitInput(
  value: unknown,
  units: string[],
  onChange: (value: string) => void,
  baseUnit: number = DEFAULT_SPACING_UNIT,
  inputId?: string,
  readOnly = false,
): unknown {
  const defaultUnit = units[0] ?? 'pt';
  const parsed = parseValueWithUnit(value, defaultUnit);
  // Whether the caller actually has a stored value. nil → empty input + placeholder;
  // an explicit '0pt' / '0sp' is a stored override and renders as "0".
  const isSet = value != null && value !== '';

  const handleNumberChange = (e: Event) => {
    const raw = (e.target as HTMLInputElement).value;
    if (raw === '') {
      onChange(''); // signal nil → caller deletes the key
      return;
    }
    const num = Math.max(0, parseFloat(raw) || 0);
    onChange(formatValueWithUnit(num, parsed.unit));
  };

  const handleUnitChange = (e: Event) => {
    const newUnit = (e.target as HTMLSelectElement).value;
    if (!isSet) {
      // No stored value: just propagate the unit choice without inventing a 0.
      onChange('');
      return;
    }
    const oldUnit = parsed.unit;
    let newValue = parsed.value;
    if (oldUnit === 'pt' && newUnit === 'sp') {
      newValue = parseFloat(nearestSpacingStep(parsed.value, baseUnit));
    } else if (oldUnit === 'sp' && newUnit === 'pt') {
      newValue = parsed.value * baseUnit;
    }
    onChange(formatValueWithUnit(newValue, newUnit));
  };

  return html`
    <div class="style-unit-input">
      <input
        type="number"
        class="ep-input style-unit-number"
        id=${inputId ?? nothing}
        step=${parsed.unit === 'sp' ? '0.5' : '1'}
        min="0"
        placeholder="—"
        .value=${isSet ? String(parsed.value) : ''}
        ?disabled=${readOnly}
        @change=${handleNumberChange}
      />
      ${units.length > 1
        ? html`
            <select
              class="ep-select style-unit-select"
              ?disabled=${readOnly}
              @change=${handleUnitChange}
            >
              ${units.map(
                (u) => html` <option .value=${u} ?selected=${u === parsed.unit}>${u}</option> `,
              )}
            </select>
          `
        : html`<span class="style-unit-label">${defaultUnit}</span>`}
    </div>
  `;
}

// ---------------------------------------------------------------------------
// Color input: native color picker + text input
// ---------------------------------------------------------------------------

export function renderColorInput(
  value: unknown,
  onChange: (value: string) => void,
  inputId?: string,
  readOnly = false,
): unknown {
  const colorValue = value != null ? String(value) : '';
  // Ensure the color picker gets a valid hex value
  const pickerValue = colorValue.startsWith('#') ? colorValue : '#000000';

  return html`
    <div class="style-color-input">
      <input
        type="color"
        class="style-color-picker"
        .value=${pickerValue}
        ?disabled=${readOnly}
        @change=${(e: Event) => onChange((e.target as HTMLInputElement).value)}
      />
      <input
        type="text"
        class="ep-input style-color-text"
        id=${inputId ?? nothing}
        .value=${colorValue}
        ?disabled=${readOnly}
        @change=${(e: Event) => onChange((e.target as HTMLInputElement).value)}
        placeholder="#000000"
      />
    </div>
  `;
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
] as const;

export const DEFAULT_SPACING_UNIT = 4; // pt

/** Find the nearest spacing scale step for a given pt value. */
export function nearestSpacingStep(ptValue: number, baseUnit: number): string {
  const multiplier = ptValue / baseUnit;
  let bestToken = SPACING_SCALE[0].token as string;
  let bestDist = Math.abs(multiplier - SPACING_SCALE[0].multiplier);
  for (const s of SPACING_SCALE) {
    const dist = Math.abs(multiplier - s.multiplier);
    if (dist < bestDist) {
      bestToken = s.token;
      bestDist = dist;
    }
  }
  return bestToken;
}

/**
 * Detect the current unit from a set of spacing side values.
 * Returns 'sp' if any side uses an sp() token, otherwise parses the unit suffix.
 */
function detectSpacingUnit(parsed: SpacingValue, defaultUnit: string): string {
  for (const side of ['top', 'right', 'bottom', 'left'] as const) {
    const v = parsed[side];
    if (v && isSpacingToken(v)) return 'sp';
    if (v && v !== '0' && v !== '') {
      const p = parseValueWithUnit(v, defaultUnit);
      if (p.unit && p.unit !== defaultUnit) return p.unit;
    }
  }
  return defaultUnit;
}

/** Convert any supported unit to absolute pt. Returns null if unit is unknown. */
function toPt(value: string, fromUnit: string, baseUnit: number): number | null {
  if (fromUnit === 'sp') {
    const step = parseSpacingToken(value);
    const multiplier = step != null ? parseFloat(step) || 0 : 0;
    return multiplier * baseUnit;
  }
  if (fromUnit === 'pt') return parseValueWithUnit(value, 'pt').value;
  return null;
}

/**
 * Convert a single side value between supported units (sp, pt, px).
 * Used both for explicit unit-switch and for migrating legacy values
 * to a unit that's actually offered in the dropdown.
 *
 * Passes through `undefined` (= nil) and unknown source units unchanged.
 */
export function convertSideValue(
  value: string | undefined,
  fromUnit: string,
  toUnit: string,
  baseUnit: number,
): string | undefined {
  if (value === undefined) return undefined;
  if (fromUnit === toUnit) return value;

  const pt = toPt(value, fromUnit, baseUnit);
  if (pt == null) return value;

  if (toUnit === 'pt') return formatValueWithUnit(pt, 'pt');
  if (toUnit === 'sp') return formatSpacingToken(nearestSpacingStep(pt, baseUnit));

  return value;
}

/** Check if a CSS value is an sp token (e.g., "2sp", "0.5sp"). */
export function isSpacingToken(value: string): boolean {
  return /^[\d.]+sp$/.test(value);
}

/** Parse an sp token, returning the step name or null. */
export function parseSpacingToken(value: string): string | null {
  const match = value.match(/^([\d.]+)sp$/);
  return match ? match[1] : null;
}

/** Format a spacing token: "2sp" */
export function formatSpacingToken(step: string): string {
  return `${step}sp`;
}

// ---------------------------------------------------------------------------
// Spacing input: 4-value (top/right/bottom/left)
// ---------------------------------------------------------------------------

/**
 * Per-side spacing value. `undefined` means nil (no value set — falls back
 * through the cascade to component defaults / preset). An explicit `'0pt'`
 * or `'0sp'` is a stored override that forces 0, beating the cascade.
 */
export interface SpacingValue {
  top: string | undefined;
  right: string | undefined;
  bottom: string | undefined;
  left: string | undefined;
}

/** Parse a spacing value — can be a string shorthand or an object. */
export function parseSpacingValue(raw: unknown): SpacingValue {
  if (raw == null) {
    return { top: undefined, right: undefined, bottom: undefined, left: undefined };
  }

  if (typeof raw === 'object' && raw !== null) {
    const obj = raw as Record<string, unknown>;
    return {
      top: obj.top != null ? String(obj.top) : undefined,
      right: obj.right != null ? String(obj.right) : undefined,
      bottom: obj.bottom != null ? String(obj.bottom) : undefined,
      left: obj.left != null ? String(obj.left) : undefined,
    };
  }

  // CSS shorthand string: "10px" or "10px 20px" or "10px 20px 30px 40px"
  const str = String(raw);
  const parts = str.split(/\s+/);
  if (parts.length === 1)
    return { top: parts[0], right: parts[0], bottom: parts[0], left: parts[0] };
  if (parts.length === 2)
    return { top: parts[0], right: parts[1], bottom: parts[0], left: parts[1] };
  if (parts.length === 3)
    return { top: parts[0], right: parts[1], bottom: parts[2], left: parts[1] };
  return { top: parts[0], right: parts[1], bottom: parts[2], left: parts[3] };
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
  inputId?: string,
  readOnly = false,
): unknown {
  const firstAbsUnit = units.find((u) => u !== 'sp') ?? 'pt';
  const parsed = parseSpacingValue(value);
  const sides = ['top', 'right', 'bottom', 'left'] as const;

  // Clamp the detected unit to one that's offered in the dropdown.
  // Stored values that use an unsupported unit (legacy data, hand-edited
  // JSON) display their numeric portion in the fallback unit and are
  // re-saved with the fallback unit on the next edit.
  const detected = detectSpacingUnit(parsed, firstAbsUnit);
  const currentUnit = units.includes(detected) ? detected : firstAbsUnit;
  const topInputId = inputId ?? undefined;

  const handleSideChange = (side: string, newValue: string | undefined) => {
    onChange({ ...parsed, [side]: newValue });
  };

  /**
   * Numeric value for the input. Returns `undefined` when the side is nil
   * so the input renders as empty (placeholder visible). An explicit '0pt'
   * or '0sp' returns 0 — distinct from nil.
   */
  const sideNumber = (sideValue: string | undefined): number | undefined => {
    if (sideValue === undefined) return undefined;
    if (currentUnit === 'sp') {
      return parseFloat(parseSpacingToken(sideValue) ?? '0') || 0;
    }
    return parseValueWithUnit(sideValue, currentUnit).value;
  };

  /** Format a number back with the current unit. */
  const formatSide = (num: number): string => {
    if (currentUnit === 'sp') return formatSpacingToken(String(num));
    return formatValueWithUnit(num, currentUnit);
  };

  return html`
    <div class="style-spacing-input">
      ${sides.map((side) => {
        const n = sideNumber(parsed[side]);
        return html`
          <div class="style-spacing-side">
            <span class="style-spacing-label">${side[0].toUpperCase()}</span>
            <input
              type="number"
              class="ep-input style-spacing-number"
              id=${side === 'top' && topInputId ? topInputId : nothing}
              step=${currentUnit === 'sp' ? '0.5' : '1'}
              min="0"
              placeholder="—"
              .value=${n === undefined ? '' : String(n)}
              ?disabled=${readOnly}
              @change=${(e: Event) => {
                const raw = (e.target as HTMLInputElement).value;
                if (raw === '') {
                  handleSideChange(side, undefined);
                } else {
                  const num = Math.max(0, parseFloat(raw) || 0);
                  handleSideChange(side, formatSide(num));
                }
              }}
            />
          </div>
        `;
      })}
      ${units.length > 1
        ? html`
            <div class="style-spacing-side">
              <span class="style-spacing-label">&nbsp;</span>
              <select
                class="ep-select style-spacing-unit"
                ?disabled=${readOnly}
                @change=${(e: Event) => {
                  const newUnit = (e.target as HTMLSelectElement).value;
                  const result: SpacingValue = {
                    top: undefined,
                    right: undefined,
                    bottom: undefined,
                    left: undefined,
                  };
                  for (const side of sides) {
                    result[side] = convertSideValue(parsed[side], currentUnit, newUnit, baseUnit);
                  }
                  onChange(result);
                }}
              >
                ${units.map(
                  (u) => html`<option .value=${u} ?selected=${u === currentUnit}>${u}</option>`,
                )}
              </select>
            </div>
          `
        : nothing}
    </div>
  `;
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
  const sides: Record<string, string | undefined> = {
    Top: value.top,
    Right: value.right,
    Bottom: value.bottom,
    Left: value.left,
  };
  for (const [suffix, sideValue] of Object.entries(sides)) {
    const key = `${prefix}${suffix}`;
    // undefined or empty string → nil → delete key (cascade applies)
    // any other value (including '0pt' / '0sp') → store as explicit override
    if (sideValue !== undefined && sideValue !== '') {
      styles[key] = sideValue;
    } else {
      delete styles[key];
    }
  }
  // Remove the compound key if it exists
  delete styles[prefix];
}

/**
 * Read individual style keys back into a compound SpacingValue.
 *
 * Missing sides become `undefined` so the inspector can render them as
 * "nil" (empty input + placeholder), distinct from an explicit '0pt'
 * stored value.
 *
 * e.g. readSpacingFromStyles('margin', { marginTop: '10pt', marginBottom: '0pt' })
 * → { top: '10pt', right: undefined, bottom: '0pt', left: undefined }
 *
 * Returns undefined if no individual keys are set.
 */
export function readSpacingFromStyles(
  prefix: string,
  styles: Record<string, unknown>,
): SpacingValue | undefined {
  const top = styles[`${prefix}Top`];
  const right = styles[`${prefix}Right`];
  const bottom = styles[`${prefix}Bottom`];
  const left = styles[`${prefix}Left`];

  // Also check for legacy compound value
  const compound = styles[prefix];

  if (top == null && right == null && bottom == null && left == null && compound == null) {
    return undefined;
  }

  // If a legacy compound object is present, read from it
  if (compound != null && typeof compound === 'object') {
    return parseSpacingValue(compound);
  }

  return {
    top: top != null ? String(top) : undefined,
    right: right != null ? String(right) : undefined,
    bottom: bottom != null ? String(bottom) : undefined,
    left: left != null ? String(left) : undefined,
  };
}

// ---------------------------------------------------------------------------
// Border input: per-side border editing (width + style + color per side)
// ---------------------------------------------------------------------------

export interface BorderSideValue {
  width: string;
  style: string;
  color: string;
}

export interface BorderValue {
  top: BorderSideValue;
  right: BorderSideValue;
  bottom: BorderSideValue;
  left: BorderSideValue;
}

const EMPTY_SIDE: BorderSideValue = { width: '', style: 'none', color: '' };

/** Check if all four border sides have equal values. */
export function areBorderSidesEqual(border: BorderValue): boolean {
  const { top, right, bottom, left } = border;
  return (
    top.width === right.width &&
    top.width === bottom.width &&
    top.width === left.width &&
    top.style === right.style &&
    top.style === bottom.style &&
    top.style === left.style &&
    top.color === right.color &&
    top.color === bottom.color &&
    top.color === left.color
  );
}

/** Parse a border shorthand like "2pt solid #000" into parts. */
export function parseBorderShorthand(raw: unknown): BorderSideValue {
  if (raw == null || raw === '') return { ...EMPTY_SIDE };
  const str = String(raw).trim();
  const parts = str.split(/\s+/);
  return {
    width: parts[0] ?? '',
    style: parts[1] ?? 'solid',
    color: parts[2] ?? '',
  };
}

/** Format border side parts back to a shorthand string. */
function formatBorderShorthand(side: BorderSideValue): string {
  if (!side.width || side.style === 'none') return '';
  return `${side.width} ${side.style} ${side.color || '#000000'}`.trim();
}

/**
 * Read per-side border values from individual style keys.
 * Keys: borderTop, borderRight, borderBottom, borderLeft (shorthand strings like "2pt solid #000")
 */
export function readBorderFromStyles(styles: Record<string, unknown>): BorderValue | undefined {
  const top = styles['borderTop'];
  const right = styles['borderRight'];
  const bottom = styles['borderBottom'];
  const left = styles['borderLeft'];

  if (top == null && right == null && bottom == null && left == null) return undefined;

  return {
    top: parseBorderShorthand(top),
    right: parseBorderShorthand(right),
    bottom: parseBorderShorthand(bottom),
    left: parseBorderShorthand(left),
  };
}

/**
 * Expand a BorderValue into individual style keys.
 * Writes: borderTop, borderRight, borderBottom, borderLeft as shorthand strings like "2pt solid #000".
 */
export function expandBorderToStyles(value: BorderValue, styles: Record<string, unknown>): void {
  const sides = { Top: value.top, Right: value.right, Bottom: value.bottom, Left: value.left };
  for (const [suffix, side] of Object.entries(sides)) {
    const shorthand = formatBorderShorthand(side);
    const key = `border${suffix}`;
    if (shorthand) {
      styles[key] = shorthand;
    } else {
      delete styles[key];
    }
  }
}

// ---------------------------------------------------------------------------
// Compound style types: generic read/write for compound properties
// ---------------------------------------------------------------------------

/**
 * Registry of compound style types that expand to/from individual style keys.
 * Used by the inspector and theme editor to generically handle compound properties
 * without hardcoding type-specific logic.
 */
export const COMPOUND_STYLE_TYPES: Record<
  string,
  {
    read: (key: string, styles: Record<string, unknown>) => unknown;
    write: (key: string, value: unknown, styles: Record<string, unknown>) => void;
  }
> = {
  spacing: {
    read: (key, styles) => readSpacingFromStyles(key, styles),
    write: (key, value, styles) => expandSpacingToStyles(key, value as SpacingValue, styles),
  },
  border: {
    read: (_key, styles) => readBorderFromStyles(styles),
    write: (_key, value, styles) => expandBorderToStyles(value as BorderValue, styles),
  },
};

// ---------------------------------------------------------------------------
// Select input (for style properties)
// ---------------------------------------------------------------------------

export function renderSelectInput(
  value: unknown,
  options: { label: string; value: string }[],
  onChange: (value: string) => void,
  selectId?: string,
  readOnly = false,
): unknown {
  const currentValue = value != null ? String(value) : '';

  return html`
    <select
      class="ep-select"
      id=${selectId ?? nothing}
      ?disabled=${readOnly}
      @change=${(e: Event) => onChange((e.target as HTMLSelectElement).value)}
    >
      <option value="" ?selected=${!currentValue}>—</option>
      ${options.map(
        (opt) => html`
          <option .value=${opt.value} ?selected=${currentValue === opt.value}>${opt.label}</option>
        `,
      )}
    </select>
  `;
}
