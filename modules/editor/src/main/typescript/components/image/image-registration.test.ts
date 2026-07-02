import { describe, it, expect } from 'vitest';
import { createImageDefinition, resolveContentUrl, crossCatalogKey } from './image-registration.js';
import type { ComponentDefinition } from '../../engine/registry.js';

/** Minimal stubs — the asset picker is unused in onPropChange tests. */
const dummyAsset = {
  id: 'a',
  name: 'a',
  mediaType: 'image/png',
  sizeBytes: 1,
  width: null,
  height: null,
  catalogKey: 'epistola-demo',
  contentUrl: '/images/a',
};
const dummyOptions = {
  assetPicker: {
    defaultCatalogKey: 'epistola-demo',
    listCatalogs: async () => [],
    listAssets: async () => [],
    uploadAsset: async () => dummyAsset,
  },
  contentUrlPattern: '/tenants/t1/images/{catalogId}/{assetId}/content',
  defaultCatalogKey: 'epistola-demo',
};

function imageDef(): ComponentDefinition {
  return createImageDefinition(dummyOptions);
}

// ---------------------------------------------------------------------------
// resolveContentUrl — cross-catalog content URL building
// ---------------------------------------------------------------------------

describe('resolveContentUrl', () => {
  const pattern = '/tenants/t1/images/{catalogId}/{assetId}/content';

  it("substitutes the node's catalog key and asset id", () => {
    expect(resolveContentUrl(pattern, 'a1', 'system', 'epistola-demo')).toBe(
      '/tenants/t1/images/system/a1/content',
    );
  });

  it('falls back to the default catalog when the node has none', () => {
    expect(resolveContentUrl(pattern, 'a1', undefined, 'epistola-demo')).toBe(
      '/tenants/t1/images/epistola-demo/a1/content',
    );
  });
});

// ---------------------------------------------------------------------------
// crossCatalogKey — only record a catalog for genuinely cross-catalog images
// ---------------------------------------------------------------------------

describe('crossCatalogKey', () => {
  it('returns null when the asset is in the template catalog', () => {
    expect(crossCatalogKey('epistola-demo', 'epistola-demo')).toBeNull();
  });

  it('returns the asset catalog when it differs from the template catalog', () => {
    expect(crossCatalogKey('system', 'epistola-demo')).toBe('system');
  });
});

// ---------------------------------------------------------------------------
// onPropChange — aspect ratio lock
// ---------------------------------------------------------------------------

describe('image onPropChange aspect ratio lock', () => {
  it('adjusts height when width changes', () => {
    const def = imageDef();
    const props = { width: '100pt', height: '200pt', aspectRatioLocked: true };

    const result = def.onPropChange!('width', '150pt', props);

    // 200 * (150/100) = 300
    expect(result.height).toBe('300pt');
  });

  it('adjusts width when height changes', () => {
    const def = imageDef();
    const props = { width: '100pt', height: '200pt', aspectRatioLocked: true };

    const result = def.onPropChange!('height', '100pt', props);

    // 100 * (100/200) = 50
    expect(result.width).toBe('50pt');
  });

  it('does nothing when aspect ratio is unlocked', () => {
    const def = imageDef();
    const props = { width: '100pt', height: '200pt', aspectRatioLocked: false };

    const result = def.onPropChange!('width', '150pt', props);

    expect(result.width).toBe('100pt');
    expect(result.height).toBe('200pt');
  });

  it('does nothing when changing a non-dimension key', () => {
    const def = imageDef();
    const props = { width: '100pt', height: '200pt', aspectRatioLocked: true, alt: 'old' };

    const result = def.onPropChange!('alt', 'new', props);

    expect(result.width).toBe('100pt');
    expect(result.height).toBe('200pt');
  });

  it('does nothing when old width is empty', () => {
    const def = imageDef();
    const props = { width: '', height: '200pt', aspectRatioLocked: true };

    const result = def.onPropChange!('height', '100pt', props);

    expect(result.width).toBe('');
  });

  it('does nothing when old height is empty', () => {
    const def = imageDef();
    const props = { width: '100pt', height: '', aspectRatioLocked: true };

    const result = def.onPropChange!('width', '150pt', props);

    expect(result.height).toBe('');
  });

  it('does nothing when value uses non-pt unit', () => {
    const def = imageDef();
    const props = { width: '100pt', height: '200pt', aspectRatioLocked: true };

    const result = def.onPropChange!('width', '50%', props);

    // parsePt returns null for non-pt values, so height stays unchanged
    expect(result.height).toBe('200pt');
  });

  it('rounds the computed dimension', () => {
    const def = imageDef();
    const props = { width: '100pt', height: '33pt', aspectRatioLocked: true };

    const result = def.onPropChange!('width', '200pt', props);

    // 33 * (200/100) = 66
    expect(result.height).toBe('66pt');
  });

  it('handles fractional ratios with rounding', () => {
    const def = imageDef();
    const props = { width: '300pt', height: '200pt', aspectRatioLocked: true };

    const result = def.onPropChange!('width', '100pt', props);

    // 200 * (100/300) = 66.666... → 67
    expect(result.height).toBe('67pt');
  });
});
