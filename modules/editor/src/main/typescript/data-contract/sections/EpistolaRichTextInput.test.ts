// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { EditorState } from 'prosemirror-state';
import { buildRichTextInputPlugins } from './EpistolaRichTextInput.js';
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
