// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import { EditorState } from 'prosemirror-state';
import { buildRichTextInputPlugins, EpistolaRichTextInput } from './EpistolaRichTextInput.js';
import { richTextBlockSchema } from '../../prosemirror/richTextBlockSchema.js';
import { richTextInlineSchema } from '../../prosemirror/richTextInlineSchema.js';
import { BUBBLE_MENU_KEY } from '../../prosemirror/bubble-menu.js';

describe('buildRichTextInputPlugins', () => {
  it('block mode includes the bubble menu (only UI to create/indent lists)', () => {
    const state = EditorState.create({
      schema: richTextBlockSchema,
      plugins: buildRichTextInputPlugins('block', richTextBlockSchema),
    });
    expect(BUBBLE_MENU_KEY.get(state)).toBeDefined();
  });

  it('inline mode includes the bubble menu for mark formatting only', () => {
    const state = EditorState.create({
      schema: richTextInlineSchema,
      plugins: buildRichTextInputPlugins('inline', richTextInlineSchema),
    });
    expect(BUBBLE_MENU_KEY.get(state)).toBeDefined();
  });
});

describe('EpistolaRichTextInput placeholder', () => {
  it('marks only empty rich-text content for placeholder styling', async () => {
    const input = new EpistolaRichTextInput();
    input.placeholder = 'toelichting';
    document.body.append(input);
    await input.updateComplete;

    const container = input.querySelector('.dc-rich-text-container')!;
    expect(container.getAttribute('data-placeholder')).toBe('toelichting');
    expect(container.hasAttribute('data-empty')).toBe(true);

    input.value = {
      type: 'doc',
      content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Ingevuld' }] }],
    };
    await input.updateComplete;

    expect(container.hasAttribute('data-empty')).toBe(false);
    input.remove();
  });
});
