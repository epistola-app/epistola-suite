// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { ThemeEditorState } from '../ThemeEditorState.js';
import { renderPresetItem } from './PresetItem.js';

function templateToHtml(value: unknown): string {
  if (value == null || value === false || typeof value === 'symbol') return '';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(templateToHtml).join('');
  if (typeof value === 'object' && 'strings' in value && 'values' in value) {
    const result = value as { strings: ArrayLike<string>; values: unknown[] };
    return Array.from(result.strings)
      .map((part, index) => part + templateToHtml(result.values[index]))
      .join('');
  }
  return '';
}

describe('PresetItem spacing controls', () => {
  it('renders compound margin and padding controls without longhand duplicates', () => {
    const state = new ThemeEditorState({
      id: 'test',
      name: 'Test',
      documentStyles: {},
      blockStylePresets: {
        card: {
          label: 'Card',
          styles: { marginTop: '1sp', paddingLeft: '2sp' },
        },
      },
      spacingUnit: 6,
    });

    const html = templateToHtml(
      renderPresetItem(state, 'card', state.theme.blockStylePresets.card, () => undefined),
    );

    expect(html.match(/>Margin</g)).toHaveLength(1);
    expect(html.match(/>Padding</g)).toHaveLength(1);
    expect(html).not.toContain('Top Margin');
    expect(html).not.toContain('Left Padding');
  });
});
