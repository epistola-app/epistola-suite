// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  buildFontFamilyOptions,
  fontFamilyValueToCss,
  fontFaceFamily,
  setFontCatalog,
  setFontDeclarer,
  chooseFont,
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
    variants: [
      { weight: 400, italic: false },
      { weight: 700, italic: false },
    ],
    css: {
      family: `epistola-${catalogKey}-${slug}`,
      faces: [
        {
          weight: 400,
          italic: false,
          url: `/tenants/t/catalogs/${catalogKey}/fonts/${slug}/400/false/content`,
        },
        {
          weight: 700,
          italic: false,
          url: `/tenants/t/catalogs/${catalogKey}/fonts/${slug}/700/false/content`,
        },
      ],
    },
  };
}

describe('buildFontFamilyOptions', () => {
  it('builds one option per font with canonical JSON {slug,catalogKey} value', () => {
    const fonts = [makeFont('inter', 'Inter', 'sans'), makeFont('lora', 'Lora', 'serif')];
    const opts = buildFontFamilyOptions(fonts);

    expect(opts).toEqual([
      { label: 'Inter', value: '{"slug":"inter","catalogKey":"system"}', group: 'system' },
      { label: 'Lora', value: '{"slug":"lora","catalogKey":"system"}', group: 'system' },
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
      { label: 'Inter', value: '{"slug":"inter","catalogKey":"system"}', group: 'system' },
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

describe('fonts of a catalog the editing catalog does not declare', () => {
  const inter = makeFont('inter', 'Inter', 'sans');
  const brand: FontInfo = {
    ...makeFont('brand', 'Brand', 'sans', 'shared'),
    catalog: 'acme/shared',
    declared: false,
  };
  const brandValue = JSON.stringify({ slug: 'brand', catalogKey: 'shared' });

  beforeEach(() => {
    setFontDeclarer(null);
    setFontCatalog([inter, brand]);
  });

  it('are offered apart, saying choosing one adds a dependency, only when they can be declared', () => {
    expect(buildFontFamilyOptions([inter, brand], false).map((o) => o.label)).toEqual(['Inter']);

    const groups = buildFontFamilyOptions([inter, brand], true).map((o) => o.group);

    expect(groups).toEqual(['system', 'shared (adds a dependency)']);
  });

  it('declare the catalog first, and apply the font once it is declared', async () => {
    const asked: FontInfo[] = [];
    setFontDeclarer(async (font) => {
      asked.push(font);
      return true;
    });
    const applied: unknown[] = [];

    chooseFont(brandValue, (v) => applied.push(v));
    await vi.waitFor(() => expect(applied).toHaveLength(1));

    expect(asked.map((f) => f.catalog)).toEqual(['acme/shared']);
    expect(applied).toEqual([{ slug: 'brand', catalogKey: 'shared' }]);
  });

  it('apply nothing when the author declines, and show the previous font again', async () => {
    const select = document.createElement('select');
    for (const value of ['', brandValue]) {
      const option = document.createElement('option');
      option.value = value;
      select.appendChild(option);
    }
    select.value = brandValue;
    const applied: unknown[] = [];
    let settled = false;
    setFontDeclarer(async () => {
      settled = true;
      return false;
    });

    chooseFont(brandValue, (v) => applied.push(v), select, '');
    await vi.waitFor(() => expect(settled).toBe(true));
    await Promise.resolve();

    expect(applied).toEqual([]);
    expect(select.value).toBe('');
  });

  it('a font the editing catalog can use is applied at once, without asking', () => {
    let asked = false;
    setFontDeclarer(async () => {
      asked = true;
      return true;
    });
    const applied: unknown[] = [];

    chooseFont(JSON.stringify({ slug: 'inter', catalogKey: 'system' }), (v) => applied.push(v));

    expect(asked).toBe(false);
    expect(applied).toEqual([{ slug: 'inter', catalogKey: 'system' }]);
  });
});
