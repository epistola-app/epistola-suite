import { describe, it, expect, beforeEach } from 'vitest';
import {
  buildFontFamilyOptions,
  fontFamilyValueToCss,
  fontFaceFamily,
  setFontCatalog,
  getFontCatalog,
  type FontInfo,
} from './font-catalog.js';
import { normalizeFontFamilyValue, fontFamilyValueToSelectValue } from './font-ref.js';
import { defaultStyleRegistry } from './style-registry.js';

function makeFont(slug: string, name: string, kind: string, catalogKey = 'system'): FontInfo {
  return {
    slug,
    name,
    kind,
    catalogKey,
    variants: ['regular', 'bold'],
    css: {
      family: `epistola-${catalogKey}-${slug}`,
      urls: {
        regular: `/tenants/t/fonts/${catalogKey}/${slug}/regular/content`,
        bold: `/tenants/t/fonts/${catalogKey}/${slug}/bold/content`,
      },
    },
  };
}

describe('buildFontFamilyOptions', () => {
  it('builds one option per font with canonical JSON {slug,catalogKey} value', () => {
    const fonts = [makeFont('inter', 'Inter', 'sans'), makeFont('lora', 'Lora', 'serif')];
    const opts = buildFontFamilyOptions(fonts);

    expect(opts).toEqual([
      { label: 'Inter', value: '{"slug":"inter","catalogKey":"system"}' },
      { label: 'Lora', value: '{"slug":"lora","catalogKey":"system"}' },
    ]);
  });

  it('returns an empty list for an empty catalog', () => {
    expect(buildFontFamilyOptions([])).toEqual([]);
  });
});

describe('fontFaceFamily', () => {
  it('namespaces by catalog and slug', () => {
    expect(fontFaceFamily('inter', 'system')).toBe('epistola-system-inter');
    expect(fontFaceFamily('brand-sans', 'acme')).toBe('epistola-acme-brand-sans');
  });
});

describe('setFontCatalog', () => {
  beforeEach(() => setFontCatalog([]));

  it('mutates the live defaultStyleRegistry fontFamily options in place', () => {
    const fontProp = defaultStyleRegistry.groups
      .flatMap((g) => g.properties)
      .find((p) => p.key === 'fontFamily')!;

    expect(fontProp.options).toEqual([]);

    setFontCatalog([makeFont('inter', 'Inter', 'sans')]);

    expect(fontProp.options).toEqual([
      { label: 'Inter', value: '{"slug":"inter","catalogKey":"system"}' },
    ]);
    expect(getFontCatalog()).toHaveLength(1);
  });
});

describe('fontFamilyValueToCss ({slug,catalogKey} -> CSS family)', () => {
  beforeEach(() => setFontCatalog([makeFont('inter', 'Inter', 'sans')]));

  it('maps a structured object reference to the @font-face family + kind fallback', () => {
    expect(fontFamilyValueToCss({ slug: 'inter', catalogKey: 'system' })).toBe(
      "'epistola-system-inter', sans-serif",
    );
  });

  it('maps the canonical JSON-string form the select carries', () => {
    expect(fontFamilyValueToCss('{"slug":"inter","catalogKey":"system"}')).toBe(
      "'epistola-system-inter', sans-serif",
    );
  });

  it('uses the serif fallback for a serif font', () => {
    setFontCatalog([makeFont('lora', 'Lora', 'serif')]);
    expect(fontFamilyValueToCss({ slug: 'lora', catalogKey: 'system' })).toBe(
      "'epistola-system-lora', serif",
    );
  });

  it('uses the monospace fallback for a mono font', () => {
    setFontCatalog([makeFont('jetbrains-mono', 'JetBrains Mono', 'mono')]);
    expect(fontFamilyValueToCss({ slug: 'jetbrains-mono', catalogKey: 'system' })).toBe(
      "'epistola-system-jetbrains-mono', monospace",
    );
  });

  it('falls back to sans-serif when the font is not in the loaded catalog', () => {
    expect(fontFamilyValueToCss({ slug: 'unknown', catalogKey: 'system' })).toBe(
      "'epistola-system-unknown', sans-serif",
    );
  });

  it('passes a legacy CSS-stack string through unchanged', () => {
    expect(fontFamilyValueToCss('Arial, sans-serif')).toBe('Arial, sans-serif');
  });

  it('returns null for null / empty values', () => {
    expect(fontFamilyValueToCss(null)).toBeNull();
    expect(fontFamilyValueToCss('')).toBeNull();
    expect(fontFamilyValueToCss({})).toBeNull();
  });
});

describe('normalizeFontFamilyValue (select string -> stored object)', () => {
  it('parses the canonical JSON string into the backend object form', () => {
    expect(normalizeFontFamilyValue('{"slug":"inter","catalogKey":"system"}')).toEqual({
      slug: 'inter',
      catalogKey: 'system',
    });
  });

  it('passes an already-structured object through', () => {
    const ref = { slug: 'lora', catalogKey: 'acme' };
    expect(normalizeFontFamilyValue(ref)).toBe(ref);
  });

  it('clears the style for empty / unset', () => {
    expect(normalizeFontFamilyValue('')).toBeUndefined();
    expect(normalizeFontFamilyValue(null)).toBeUndefined();
    expect(normalizeFontFamilyValue('   ')).toBeUndefined();
  });

  it('round-trips object -> select value -> object', () => {
    const ref = { slug: 'inter', catalogKey: 'system' };
    const selectValue = fontFamilyValueToSelectValue(ref);
    expect(selectValue).toBe('{"slug":"inter","catalogKey":"system"}');
    expect(normalizeFontFamilyValue(selectValue)).toEqual(ref);
  });
});
