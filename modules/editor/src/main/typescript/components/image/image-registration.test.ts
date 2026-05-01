import { describe, it, expect } from 'vitest';
import { createImageDefinition } from './image-registration.js';
import type { ComponentDefinition } from '../../engine/registry.js';

/** Minimal stubs — the asset picker is unused in onPropChange tests. */
const dummyOptions = {
  assetPicker: {
    listAssets: async () => [],
    uploadAsset: async () => ({ id: 'a', name: 'a', contentType: 'image/png' }),
  },
  contentUrlPattern: '/assets/{assetId}',
};

function imageDef(): ComponentDefinition {
  return createImageDefinition(dummyOptions);
}

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
