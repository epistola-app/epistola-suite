// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { RESOURCE_TYPES, displayType } from './model';

describe('resource graph model', () => {
  it('defines every backend resource type with a readable label', () => {
    expect(RESOURCE_TYPES.map((type) => type.value)).toEqual([
      'template',
      'theme',
      'stencil',
      'attribute',
      'codeList',
      'font',
      'asset',
    ]);
    expect(displayType('codeList')).toBe('Code list');
  });
});
