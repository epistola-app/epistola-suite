import { describe, it, expect } from 'vitest';
import {
  expandSpacingToStyles,
  readSpacingFromStyles,
  expandBorderToStyles,
  readBorderFromStyles,
  parseBorderShorthand,
  areBorderSidesEqual,
  convertSideValue,
  DEFAULT_SPACING_UNIT,
  type SpacingValue,
  type BorderValue,
} from './style-inputs.js';

// ---------------------------------------------------------------------------
// expandSpacingToStyles
// ---------------------------------------------------------------------------

describe('expandSpacingToStyles', () => {
  it('writes individual margin keys from a SpacingValue', () => {
    const styles: Record<string, unknown> = {};
    const spacing: SpacingValue = { top: '10px', right: '5px', bottom: '8px', left: '5px' };

    expandSpacingToStyles('margin', spacing, styles);

    expect(styles).toEqual({
      marginTop: '10px',
      marginRight: '5px',
      marginBottom: '8px',
      marginLeft: '5px',
    });
  });

  it('writes individual padding keys from a SpacingValue', () => {
    const styles: Record<string, unknown> = {};
    const spacing: SpacingValue = { top: '1em', right: '2em', bottom: '1em', left: '2em' };

    expandSpacingToStyles('padding', spacing, styles);

    expect(styles).toEqual({
      paddingTop: '1em',
      paddingRight: '2em',
      paddingBottom: '1em',
      paddingLeft: '2em',
    });
  });

  it('removes the legacy compound key when expanding individual sides', () => {
    const styles: Record<string, unknown> = { margin: { top: '10px' } };
    const spacing: SpacingValue = {
      top: '10px',
      right: undefined,
      bottom: undefined,
      left: undefined,
    };

    expandSpacingToStyles('margin', spacing, styles);

    expect(styles.margin).toBeUndefined();
    expect(styles.marginTop).toBe('10px');
  });

  it('deletes only undefined sides (explicit "0pt" is stored as override)', () => {
    const styles: Record<string, unknown> = { marginTop: '10pt', marginRight: '5pt' };
    const spacing: SpacingValue = {
      top: '10pt',
      right: '0pt',
      bottom: undefined,
      left: undefined,
    };

    expandSpacingToStyles('margin', spacing, styles);

    expect(styles.marginTop).toBe('10pt');
    expect(styles.marginRight).toBe('0pt'); // explicit zero is preserved
    expect(styles.marginBottom).toBeUndefined();
    expect(styles.marginLeft).toBeUndefined();
  });

  it('preserves explicit "0sp" / "0pt" / "0px" as stored override values', () => {
    const styles: Record<string, unknown> = {};
    const spacing: SpacingValue = { top: '0sp', right: '0pt', bottom: '0px', left: '5pt' };

    expandSpacingToStyles('margin', spacing, styles);

    expect(styles).toEqual({
      marginTop: '0sp',
      marginRight: '0pt',
      marginBottom: '0px',
      marginLeft: '5pt',
    });
  });

  it('deletes pre-existing keys when the SpacingValue side is undefined', () => {
    const styles: Record<string, unknown> = {
      marginTop: '10pt',
      marginRight: '5pt',
      marginBottom: '8pt',
      marginLeft: '5pt',
    };
    const spacing: SpacingValue = {
      top: undefined,
      right: undefined,
      bottom: undefined,
      left: undefined,
    };

    expandSpacingToStyles('margin', spacing, styles);

    expect(styles.marginTop).toBeUndefined();
    expect(styles.marginRight).toBeUndefined();
    expect(styles.marginBottom).toBeUndefined();
    expect(styles.marginLeft).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// readSpacingFromStyles
// ---------------------------------------------------------------------------

describe('readSpacingFromStyles', () => {
  it('reads individual margin keys into a SpacingValue', () => {
    const styles = {
      marginTop: '10px',
      marginRight: '5px',
      marginBottom: '8px',
      marginLeft: '5px',
    };

    const result = readSpacingFromStyles('margin', styles);

    expect(result).toEqual({ top: '10px', right: '5px', bottom: '8px', left: '5px' });
  });

  it('returns undefined per side when the key is missing (nil)', () => {
    const styles = { marginBottom: '12px' };

    const result = readSpacingFromStyles('margin', styles);

    expect(result).toEqual({
      top: undefined,
      right: undefined,
      bottom: '12px',
      left: undefined,
    });
  });

  it('returns undefined when no individual keys are set', () => {
    const styles = { fontSize: '14px', color: '#333' };

    const result = readSpacingFromStyles('margin', styles);

    expect(result).toBeUndefined();
  });

  it('preserves an explicit "0pt" stored value as an override (distinct from nil)', () => {
    const styles = { marginTop: '0pt', marginBottom: '5pt' };

    const result = readSpacingFromStyles('margin', styles);

    expect(result).toEqual({
      top: '0pt',
      right: undefined,
      bottom: '5pt',
      left: undefined,
    });
  });

  it('reads padding keys', () => {
    const styles = { paddingTop: '5px', paddingBottom: '10px' };

    const result = readSpacingFromStyles('padding', styles);

    expect(result).toEqual({
      top: '5px',
      right: undefined,
      bottom: '10px',
      left: undefined,
    });
  });

  it('handles legacy compound object as fallback', () => {
    const styles = { margin: { top: '10px', right: '5px', bottom: '10px', left: '5px' } };

    const result = readSpacingFromStyles('margin', styles);

    expect(result).toEqual({ top: '10px', right: '5px', bottom: '10px', left: '5px' });
  });
});

// ---------------------------------------------------------------------------
// convertSideValue — explicit unit-switch between supported units (sp, pt).
// ---------------------------------------------------------------------------

describe('convertSideValue', () => {
  const baseUnit = DEFAULT_SPACING_UNIT;

  it('returns value unchanged when fromUnit equals toUnit', () => {
    expect(convertSideValue('8pt', 'pt', 'pt', baseUnit)).toBe('8pt');
  });

  it('converts pt to sp via the spacing scale', () => {
    expect(convertSideValue('8pt', 'pt', 'sp', baseUnit)).toBe('2sp');
  });

  it('converts sp to pt by multiplying with base unit', () => {
    expect(convertSideValue('2sp', 'sp', 'pt', baseUnit)).toBe('8pt');
  });

  it('returns value unchanged for unknown source unit', () => {
    expect(convertSideValue('5em', 'em', 'pt', baseUnit)).toBe('5em');
  });
});

// ---------------------------------------------------------------------------
// areBorderSidesEqual
// ---------------------------------------------------------------------------

describe('areBorderSidesEqual', () => {
  it('returns true when all sides are identical', () => {
    const border: BorderValue = {
      top: { width: '2pt', style: 'solid', color: '#000' },
      right: { width: '2pt', style: 'solid', color: '#000' },
      bottom: { width: '2pt', style: 'solid', color: '#000' },
      left: { width: '2pt', style: 'solid', color: '#000' },
    };
    expect(areBorderSidesEqual(border)).toBe(true);
  });

  it('returns true when all sides are empty', () => {
    const border: BorderValue = {
      top: { width: '', style: 'none', color: '' },
      right: { width: '', style: 'none', color: '' },
      bottom: { width: '', style: 'none', color: '' },
      left: { width: '', style: 'none', color: '' },
    };
    expect(areBorderSidesEqual(border)).toBe(true);
  });

  it('returns false when sides differ', () => {
    const border: BorderValue = {
      top: { width: '2pt', style: 'solid', color: '#000' },
      right: { width: '1pt', style: 'solid', color: '#000' },
      bottom: { width: '2pt', style: 'solid', color: '#000' },
      left: { width: '2pt', style: 'solid', color: '#000' },
    };
    expect(areBorderSidesEqual(border)).toBe(false);
  });

  it('returns false when styles differ', () => {
    const border: BorderValue = {
      top: { width: '1pt', style: 'solid', color: '#000' },
      right: { width: '1pt', style: 'dashed', color: '#000' },
      bottom: { width: '1pt', style: 'solid', color: '#000' },
      left: { width: '1pt', style: 'solid', color: '#000' },
    };
    expect(areBorderSidesEqual(border)).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// parseBorderShorthand
// ---------------------------------------------------------------------------

describe('parseBorderShorthand', () => {
  it('parses a full shorthand string', () => {
    expect(parseBorderShorthand('2pt solid #ff0000')).toEqual({
      width: '2pt',
      style: 'solid',
      color: '#ff0000',
    });
  });

  it('parses width-only shorthand with defaults', () => {
    expect(parseBorderShorthand('1pt')).toEqual({
      width: '1pt',
      style: 'solid',
      color: '',
    });
  });

  it('returns empty side for null/empty input', () => {
    expect(parseBorderShorthand(null)).toEqual({ width: '', style: 'none', color: '' });
    expect(parseBorderShorthand('')).toEqual({ width: '', style: 'none', color: '' });
  });

  it('parses dashed border', () => {
    expect(parseBorderShorthand('1.5pt dashed #333')).toEqual({
      width: '1.5pt',
      style: 'dashed',
      color: '#333',
    });
  });
});

// ---------------------------------------------------------------------------
// expandBorderToStyles
// ---------------------------------------------------------------------------

describe('expandBorderToStyles', () => {
  it('writes per-side shorthand keys from a BorderValue', () => {
    const styles: Record<string, unknown> = {};
    const border: BorderValue = {
      top: { width: '2pt', style: 'solid', color: '#000' },
      right: { width: '1pt', style: 'dashed', color: '#ccc' },
      bottom: { width: '2pt', style: 'solid', color: '#000' },
      left: { width: '', style: 'none', color: '' },
    };

    expandBorderToStyles(border, styles);

    expect(styles).toEqual({
      borderTop: '2pt solid #000',
      borderRight: '1pt dashed #ccc',
      borderBottom: '2pt solid #000',
    });
    expect(styles.borderLeft).toBeUndefined();
  });

  it('omits sides with no width', () => {
    const styles: Record<string, unknown> = {};
    const border: BorderValue = {
      top: { width: '', style: 'solid', color: '#000' },
      right: { width: '', style: 'none', color: '' },
      bottom: { width: '', style: 'none', color: '' },
      left: { width: '', style: 'none', color: '' },
    };

    expandBorderToStyles(border, styles);

    expect(Object.keys(styles)).toHaveLength(0);
  });

  it('omits sides with style none', () => {
    const styles: Record<string, unknown> = {};
    const border: BorderValue = {
      top: { width: '2pt', style: 'none', color: '#000' },
      right: { width: '', style: 'none', color: '' },
      bottom: { width: '1pt', style: 'solid', color: '#333' },
      left: { width: '', style: 'none', color: '' },
    };

    expandBorderToStyles(border, styles);

    expect(styles.borderTop).toBeUndefined();
    expect(styles.borderBottom).toBe('1pt solid #333');
  });

  it('defaults missing color to #000000', () => {
    const styles: Record<string, unknown> = {};
    const border: BorderValue = {
      top: { width: '1pt', style: 'solid', color: '' },
      right: { width: '', style: 'none', color: '' },
      bottom: { width: '', style: 'none', color: '' },
      left: { width: '', style: 'none', color: '' },
    };

    expandBorderToStyles(border, styles);

    expect(styles.borderTop).toBe('1pt solid #000000');
  });
});

// ---------------------------------------------------------------------------
// readBorderFromStyles
// ---------------------------------------------------------------------------

describe('readBorderFromStyles', () => {
  it('reads per-side shorthand keys into a BorderValue', () => {
    const styles = {
      borderTop: '2pt solid #000',
      borderBottom: '1pt dashed #ccc',
    };

    const result = readBorderFromStyles(styles);

    expect(result).toEqual({
      top: { width: '2pt', style: 'solid', color: '#000' },
      right: { width: '', style: 'none', color: '' },
      bottom: { width: '1pt', style: 'dashed', color: '#ccc' },
      left: { width: '', style: 'none', color: '' },
    });
  });

  it('returns undefined when no border keys are set', () => {
    const styles = { fontSize: '14px', color: '#333' };

    expect(readBorderFromStyles(styles)).toBeUndefined();
  });

  it('round-trips correctly through expand and read', () => {
    const original: BorderValue = {
      top: { width: '2pt', style: 'solid', color: '#000000' },
      right: { width: '1pt', style: 'dashed', color: '#cccccc' },
      bottom: { width: '2pt', style: 'solid', color: '#000000' },
      left: { width: '', style: 'none', color: '' },
    };

    const styles: Record<string, unknown> = {};
    expandBorderToStyles(original, styles);
    const result = readBorderFromStyles(styles);

    expect(result).toEqual(original);
  });
});
