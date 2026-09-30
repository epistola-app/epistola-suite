// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { pageFormatOptions } from './page-format-options.js';

describe('pageFormatOptions', () => {
  it('offers only the formats the renderer can produce', () => {
    expect(pageFormatOptions('A4').map((o) => o.value)).toEqual(['A4', 'Letter']);
  });

  it('does not offer Custom, which has no size and renders as A4', () => {
    expect(pageFormatOptions(undefined).map((o) => o.value)).not.toContain('Custom');
  });

  it('keeps a stored Custom visible, saying what it renders as', () => {
    const options = pageFormatOptions('Custom');

    expect(options.map((o) => o.value)).toEqual(['A4', 'Letter', 'Custom']);
    expect(options[2].label).toBe('Custom (renders as A4)');
  });
});
