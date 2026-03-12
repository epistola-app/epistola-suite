export interface ParsedUnit {
  value: number
  unit: string
}

export interface SpacingValue {
  top: string
  right: string
  bottom: string
  left: string
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

export function parseSpacingValue(raw: unknown, defaultUnit: string): SpacingValue {
  if (raw == null) {
    return { top: `0${defaultUnit}`, right: `0${defaultUnit}`, bottom: `0${defaultUnit}`, left: `0${defaultUnit}` }
  }

  if (typeof raw === 'object') {
    const obj = raw as Record<string, unknown>
    return {
      top: obj.top != null ? String(obj.top) : `0${defaultUnit}`,
      right: obj.right != null ? String(obj.right) : `0${defaultUnit}`,
      bottom: obj.bottom != null ? String(obj.bottom) : `0${defaultUnit}`,
      left: obj.left != null ? String(obj.left) : `0${defaultUnit}`,
    }
  }

  const str = String(raw).trim()
  if (str === '') {
    return { top: `0${defaultUnit}`, right: `0${defaultUnit}`, bottom: `0${defaultUnit}`, left: `0${defaultUnit}` }
  }

  const parts = str.split(/\s+/)
  if (parts.length === 1) return { top: parts[0], right: parts[0], bottom: parts[0], left: parts[0] }
  if (parts.length === 2) return { top: parts[0], right: parts[1], bottom: parts[0], left: parts[1] }
  if (parts.length === 3) return { top: parts[0], right: parts[1], bottom: parts[2], left: parts[1] }
  return { top: parts[0], right: parts[1], bottom: parts[2], left: parts[3] }
}
