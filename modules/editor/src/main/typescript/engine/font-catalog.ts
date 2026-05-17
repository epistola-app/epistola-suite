/**
 * Backend-driven font catalog.
 *
 * The font picker is no longer a fixed list of CSS stacks: the host page
 * fetches the tenant's real font catalog (bundled `system` fonts plus any
 * customer fonts in the editing catalog) and feeds it here. This module is
 * the single place that:
 *
 *   1. holds the loaded `FontInfo[]` at runtime,
 *   2. builds the `fontFamily` select options (and mutates the live
 *      `defaultStyleRegistry` option array in place so every consumer —
 *      template inspector, theme editor sections — sees them),
 *   3. injects one `@font-face` rule per (font, variant) so the editor
 *      canvas can actually render the chosen face,
 *   4. maps a stored `fontFamily` value to canvas CSS.
 *
 * The stored style value is the structured reference the backend reads
 * (`StyleApplicator.parseFontRef`): a `{ slug, catalogKey }` object. The
 * `<select>` element only carries strings, so the option *value* is the
 * canonical JSON encoding of that object and the inspector parses it back
 * into an object before storing (see `font-ref.ts`).
 */

import { defaultStyleRegistry } from './style-registry.js';

/** One face of a font family: CSS numeric weight (1–1000) + italic. */
export interface FontVariant {
  weight: number;
  italic: boolean;
}

/** One renderable face: weight + italic + the content URL for its binary. */
export interface FontFace {
  weight: number;
  italic: boolean;
  url: string;
}

/** One font family as returned by the `/tenants/{id}/fonts/search` handler. */
export interface FontInfo {
  slug: string;
  name: string;
  kind: string;
  catalogKey: string;
  variants: FontVariant[];
  css: {
    family: string;
    faces: FontFace[];
  };
}

/** Coarse `FontKind` wire value → CSS generic fallback. */
const KIND_FALLBACK: Record<string, string> = {
  sans: 'sans-serif',
  serif: 'serif',
  mono: 'monospace',
  condensed: 'sans-serif',
  display: 'sans-serif',
};

let currentCatalog: FontInfo[] = [];

const STYLE_ELEMENT_ID = 'epistola-font-faces';

/** Build the `@font-face` family name for a font reference. */
export function fontFaceFamily(slug: string, catalogKey: string): string {
  return `epistola-${catalogKey}-${slug}`;
}

/**
 * Replace the active font catalog. Rebuilds the `fontFamily` select options
 * (in place, so existing registry references update) and re-injects the
 * `@font-face` rules.
 */
export function setFontCatalog(fonts: FontInfo[]): void {
  currentCatalog = fonts;
  syncStyleRegistryOptions();
  injectFontFaces();
}

/** The active catalog (mostly for tests/introspection). */
export function getFontCatalog(): readonly FontInfo[] {
  return currentCatalog;
}

/**
 * Build the `fontFamily` select options from a catalog. Each option `value`
 * is the canonical JSON encoding of the `{ slug, catalogKey }` reference the
 * backend stores and reads.
 */
export function buildFontFamilyOptions(
  fonts: readonly FontInfo[],
): { label: string; value: string; group: string }[] {
  return fonts.map((f) => ({
    label: f.name,
    value: JSON.stringify({ slug: f.slug, catalogKey: f.catalogKey }),
    // Groups the picker into `<optgroup>`s by catalog so the author can see
    // which catalog a font comes from (and same-named fonts in different
    // catalogs are distinguishable).
    group: f.catalogKey,
  }));
}

/**
 * Map a stored `fontFamily` style value to a canvas `font-family` string.
 *
 * Accepts the structured object the backend reads, its canonical JSON
 * string encoding (what the `<select>` carries before normalization), or a
 * legacy CSS-stack string (returned unchanged for forward safety). Returns
 * `null` when there is nothing usable.
 */
export function fontFamilyValueToCss(value: unknown): string | null {
  const ref = parseRef(value);
  if (!ref) {
    if (typeof value === 'string' && value.trim() !== '') return value;
    return null;
  }
  const family = fontFaceFamily(ref.slug, ref.catalogKey);
  const info = currentCatalog.find((f) => f.slug === ref.slug && f.catalogKey === ref.catalogKey);
  const fallback = info ? (KIND_FALLBACK[info.kind] ?? 'sans-serif') : 'sans-serif';
  return `'${family}', ${fallback}`;
}

function parseRef(value: unknown): { slug: string; catalogKey: string } | null {
  let obj: unknown = value;
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed.startsWith('{')) return null;
    try {
      obj = JSON.parse(trimmed);
    } catch {
      return null;
    }
  }
  if (obj && typeof obj === 'object') {
    const rec = obj as Record<string, unknown>;
    const slug = typeof rec.slug === 'string' ? rec.slug : null;
    const catalogKey = typeof rec.catalogKey === 'string' ? rec.catalogKey : null;
    if (slug && catalogKey) return { slug, catalogKey };
  }
  return null;
}

/**
 * Mutate the live `fontFamily` option array in `defaultStyleRegistry` so all
 * consumers (which read `.groups` lazily at render time) pick up the
 * backend-driven options without re-threading the registry.
 */
function syncStyleRegistryOptions(): void {
  for (const group of defaultStyleRegistry.groups) {
    for (const prop of group.properties) {
      if (prop.key === 'fontFamily') {
        prop.options = buildFontFamilyOptions(currentCatalog);
      }
    }
  }
}

function injectFontFaces(): void {
  if (typeof document === 'undefined') return;

  let styleEl = document.getElementById(STYLE_ELEMENT_ID) as HTMLStyleElement | null;
  if (!styleEl) {
    styleEl = document.createElement('style');
    styleEl.id = STYLE_ELEMENT_ID;
    document.head.appendChild(styleEl);
  }

  const rules: string[] = [];
  for (const font of currentCatalog) {
    const family = fontFaceFamily(font.slug, font.catalogKey);
    for (const face of font.css.faces) {
      if (!face.url) continue;
      rules.push(
        `@font-face{font-family:'${family}';font-weight:${face.weight};` +
          `font-style:${face.italic ? 'italic' : 'normal'};font-display:swap;` +
          `src:url('${face.url}') format('truetype');}`,
      );
    }
  }
  styleEl.textContent = rules.join('\n');
}
