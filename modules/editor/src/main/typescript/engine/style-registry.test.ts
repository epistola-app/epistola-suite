// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { styleRegistry } from '@epistola.app/epistola-catalog/registry';
import type { StyleRegistry } from '@epistola.app/epistola-catalog';
import {
  createEditorStyleRegistry,
  defaultStyleRegistry,
  isEditorStyleApplicable,
} from './style-registry.js';

describe('editor style registry', () => {
  it('replaces spacing longhands with the compound margin and padding controls', () => {
    const spacing = defaultStyleRegistry.groups.find((group) => group.label === 'Spacing');
    const keys = spacing?.properties.map((property) => property.key);

    expect(keys).toEqual(['padding', 'margin']);
  });

  it('does not mutate the contract registry', () => {
    createEditorStyleRegistry(styleRegistry);
    const spacing = styleRegistry.groups.find((group) => group.label === 'Spacing');

    expect(spacing?.properties.map((property) => property.key)).toContain('marginTop');
    expect(spacing?.properties.map((property) => property.key)).toContain('paddingLeft');
  });

  it('keeps a longhand when a future registry omits its compound control', () => {
    const source: StyleRegistry = {
      schemaVersion: 1,
      groups: [
        {
          name: 'spacing',
          label: 'Spacing',
          properties: [{ key: 'marginTop', label: 'Top Margin', type: 'unit' }],
        },
      ],
    };

    expect(createEditorStyleRegistry(source).groups[0].properties).toHaveLength(1);
  });

  it('maps component longhand allowlists to their compound control', () => {
    expect(isEditorStyleApplicable('margin', ['marginBottom'])).toBe(true);
    expect(isEditorStyleApplicable('padding', ['paddingLeft'])).toBe(true);
    expect(isEditorStyleApplicable('margin', ['paddingTop'])).toBe(false);
  });
});
