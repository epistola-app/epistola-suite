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
import { createDefaultRegistry } from './registry.js';

describe('editor style registry', () => {
  it('replaces spacing longhands with the compound margin and padding controls', () => {
    const spacing = defaultStyleRegistry.groups.find((group) => group.label === 'Spacing');
    const keys = spacing?.properties.map((property) => property.key);

    expect(keys).toEqual(['padding', 'margin', 'listItemSpacing']);
  });

  it('replaces border longhands with the compound border control', () => {
    const borders = defaultStyleRegistry.groups.find((group) => group.label === 'Borders');
    const keys = borders?.properties.map((property) => property.key);

    expect(keys).toEqual(['border', 'borderRadius']);
  });

  it('does not mutate the contract registry', () => {
    createEditorStyleRegistry(styleRegistry);
    const spacing = styleRegistry.groups.find((group) => group.label === 'Spacing');

    expect(spacing?.properties.map((property) => property.key)).toContain('marginTop');
    expect(spacing?.properties.map((property) => property.key)).toContain('paddingLeft');
    const borders = styleRegistry.groups.find((group) => group.label === 'Borders');
    expect(borders?.properties.map((property) => property.key)).toContain('borderTop');
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
    expect(isEditorStyleApplicable('border', ['borderBottom'])).toBe(true);
    expect(isEditorStyleApplicable('margin', ['paddingTop'])).toBe(false);
  });

  it('exposes the shared list item spacing default for every list-producing component', () => {
    const registry = createDefaultRegistry();

    for (const type of ['text', 'richTextVariable', 'datalist']) {
      const component = registry.getOrThrow(type);
      expect(component.defaultStyles?.listItemSpacing).toBe('0.5sp');
      expect(
        component.applicableStyles === 'all' ||
          component.applicableStyles.includes('listItemSpacing'),
      ).toBe(true);
    }
  });
});
