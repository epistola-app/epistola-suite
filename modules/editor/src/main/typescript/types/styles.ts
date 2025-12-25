import type { CSSProperties } from 'react';

// Style categories for UI organization
export type StyleCategory =
  | 'spacing'
  | 'typography'
  | 'background'
  | 'borders'
  | 'effects'
  | 'layout';

// Unit types for numeric inputs
export type CSSUnit = 'px' | 'em' | 'rem' | '%' | 'pt';

// Unit presets for different CSS properties
export const UNIT_PRESETS = {
  spacing: ['px', 'em', 'rem'] as const,
  size: ['px', '%', 'em', 'rem'] as const,
  fontSize: ['px', 'em', 'rem', 'pt'] as const,
  lineHeight: ['px', 'em', '%'] as const,
  borderWidth: ['px', 'em'] as const,
  borderRadius: ['px', 'em', 'rem', '%'] as const,
} as const;

export type UnitPresetKey = keyof typeof UNIT_PRESETS;

// Font family options
export const FONT_FAMILIES = [
  { label: 'System Default', value: 'system-ui, -apple-system, sans-serif' },
  { label: 'Arial', value: 'Arial, sans-serif' },
  { label: 'Georgia', value: 'Georgia, serif' },
  { label: 'Times New Roman', value: '"Times New Roman", serif' },
  { label: 'Courier New', value: '"Courier New", monospace' },
  { label: 'Verdana', value: 'Verdana, sans-serif' },
] as const;

// Font weight options
export const FONT_WEIGHTS = [
  { label: 'Normal', value: '400' },
  { label: 'Medium', value: '500' },
  { label: 'Semi Bold', value: '600' },
  { label: 'Bold', value: '700' },
] as const;

// Border style options
export const BORDER_STYLES = [
  { label: 'None', value: 'none' },
  { label: 'Solid', value: 'solid' },
  { label: 'Dashed', value: 'dashed' },
  { label: 'Dotted', value: 'dotted' },
  { label: 'Double', value: 'double' },
] as const;

// Display options
export const DISPLAY_OPTIONS = [
  { label: 'Block', value: 'block' },
  { label: 'Flex', value: 'flex' },
  { label: 'None', value: 'none' },
] as const;

// Flex direction options
export const FLEX_DIRECTIONS = [
  { label: 'Row', value: 'row' },
  { label: 'Row Reverse', value: 'row-reverse' },
  { label: 'Column', value: 'column' },
  { label: 'Column Reverse', value: 'column-reverse' },
] as const;

// Align items options
export const ALIGN_OPTIONS = [
  { label: 'Start', value: 'flex-start' },
  { label: 'Center', value: 'center' },
  { label: 'End', value: 'flex-end' },
  { label: 'Stretch', value: 'stretch' },
  { label: 'Baseline', value: 'baseline' },
] as const;

// Justify content options
export const JUSTIFY_OPTIONS = [
  { label: 'Start', value: 'flex-start' },
  { label: 'Center', value: 'center' },
  { label: 'End', value: 'flex-end' },
  { label: 'Between', value: 'space-between' },
  { label: 'Around', value: 'space-around' },
  { label: 'Evenly', value: 'space-evenly' },
] as const;

// Text align options
export const TEXT_ALIGN_OPTIONS = [
  { label: 'Left', value: 'left' },
  { label: 'Center', value: 'center' },
  { label: 'Right', value: 'right' },
  { label: 'Justify', value: 'justify' },
] as const;

// CSS properties that should inherit from document to blocks
export const INHERITABLE_PROPERTIES: (keyof CSSProperties)[] = [
  'fontFamily',
  'fontSize',
  'fontWeight',
  'color',
  'lineHeight',
  'letterSpacing',
  'textAlign',
];

// Helper to merge document styles with block styles
// Only inheritable properties cascade from document styles
export function mergeStyles(
  documentStyles: CSSProperties | undefined,
  blockStyles: CSSProperties | undefined
): CSSProperties {
  if (!documentStyles && !blockStyles) return {};
  if (!documentStyles) return blockStyles || {};
  if (!blockStyles) {
    // Only return inheritable properties from document styles
    const inherited: CSSProperties = {};
    for (const prop of INHERITABLE_PROPERTIES) {
      if (documentStyles[prop] !== undefined) {
        (inherited as Record<string, unknown>)[prop] = documentStyles[prop];
      }
    }
    return inherited;
  }

  // Merge: block styles override document styles for inheritable properties
  const merged: CSSProperties = { ...blockStyles };
  for (const prop of INHERITABLE_PROPERTIES) {
    if (merged[prop] === undefined && documentStyles[prop] !== undefined) {
      (merged as Record<string, unknown>)[prop] = documentStyles[prop];
    }
  }
  return merged;
}

// Parse a CSS value with unit into number and unit parts
export function parseValueWithUnit(value: string | undefined): { value: number; unit: CSSUnit } | null {
  if (!value) return null;
  const match = value.match(/^(-?\d*\.?\d+)(px|em|rem|%|pt)$/);
  if (!match) return null;
  return {
    value: parseFloat(match[1]),
    unit: match[2] as CSSUnit,
  };
}

// Format a number and unit into a CSS value string
export function formatValueWithUnit(value: number | undefined, unit: CSSUnit): string | undefined {
  if (value === undefined || value === null) return undefined;
  return `${value}${unit}`;
}
