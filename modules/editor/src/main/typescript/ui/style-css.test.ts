import { describe, expect, it } from 'vitest';
import { camelToKebab, convertSpToPt, toStyleMap } from './style-css.js';

describe('convertSpToPt', () => {
  it('converts a single sp value with the default unit', () => {
    expect(convertSpToPt('2sp', 4)).toBe('8pt');
  });

  it('supports decimal sp values', () => {
    expect(convertSpToPt('1.5sp', 4)).toBe('6pt');
  });

  it('rewrites every sp token inside a shorthand', () => {
    expect(convertSpToPt('2sp solid #fff', 4)).toBe('8pt solid #fff');
  });

  it('leaves non-sp values untouched', () => {
    expect(convertSpToPt('10pt', 4)).toBe('10pt');
    expect(convertSpToPt('50%', 4)).toBe('50%');
    expect(convertSpToPt('center', 4)).toBe('center');
  });

  it('uses a custom base unit', () => {
    expect(convertSpToPt('3sp', 6)).toBe('18pt');
  });
});

describe('camelToKebab', () => {
  it('converts camelCase to kebab-case', () => {
    expect(camelToKebab('marginTop')).toBe('margin-top');
    expect(camelToKebab('backgroundColor')).toBe('background-color');
  });

  it('leaves single-word keys unchanged', () => {
    expect(camelToKebab('color')).toBe('color');
  });
});

describe('toStyleMap', () => {
  it('converts camelCase keys and sp values together', () => {
    expect(toStyleMap({ marginTop: '2sp', marginLeft: '1sp' }, 4)).toEqual({
      'margin-top': '8pt',
      'margin-left': '4pt',
    });
  });

  it('drops null and undefined values', () => {
    expect(toStyleMap({ marginTop: '2sp', color: null, fontSize: undefined }, 4)).toEqual({
      'margin-top': '8pt',
    });
  });

  it('preserves non-sp values', () => {
    expect(toStyleMap({ color: '#333', fontSize: '12pt' }, 4)).toEqual({
      color: '#333',
      'font-size': '12pt',
    });
  });

  it('falls back to the default base unit when none is given', () => {
    expect(toStyleMap({ marginTop: '2sp' })).toEqual({ 'margin-top': '8pt' });
  });
});
