/**
 * CSS rendering helpers for the editor canvas.
 *
 * The editor's resolved style values use the spacing-token unit `sp`
 * (e.g. `2sp`), which mirrors the backend's `SpacingScale`. Browsers
 * don't understand `sp`, so before handing values to `styleMap` we
 * rewrite each `Nsp` token to an absolute `pt` value so the canvas
 * preview matches the PDF output.
 */

/** Default spacing unit in points; mirrors backend SpacingScale.DEFAULT_BASE_UNIT. */
export const DEFAULT_SPACING_UNIT_PT = 4;

const SP_TOKEN = /(\d+(?:\.\d+)?)sp\b/g;

/**
 * Rewrite every `Nsp` token in a string to an absolute `pt` value.
 * Leaves other content (other units, color, keywords) untouched, so
 * shorthand values like `2sp solid #fff` are handled correctly.
 */
export function convertSpToPt(value: string, baseUnitPt: number): string {
  return value.replace(SP_TOKEN, (_, num) => `${parseFloat(num) * baseUnitPt}pt`);
}

/** Convert a camelCase key to kebab-case CSS property name. */
export function camelToKebab(key: string): string {
  return key.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
}

/**
 * Convert a resolved styles object to a styleMap-compatible record.
 *
 * - camelCase keys → kebab-case CSS properties
 * - `Nsp` tokens in values → `(N * baseUnitPt)pt`
 * - null/undefined values are dropped
 */
export function toStyleMap(
  styles: Record<string, unknown>,
  baseUnitPt: number = DEFAULT_SPACING_UNIT_PT,
): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(styles)) {
    if (value == null) continue;
    result[camelToKebab(key)] = convertSpToPt(String(value), baseUnitPt);
  }
  return result;
}
