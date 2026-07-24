// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Mark specs shared by `richTextBlockSchema` and `richTextInlineSchema`.
 *
 * These are the marks accepted as part of rich-text parameter values:
 * basic strong/em/code/link from `prosemirror-schema-basic`, plus underline,
 * strikethrough, subscript, superscript. The wire-format JSON Schemas
 * (`richtext-block-v1.json`, `richtext-inline-v1.json`) keep this list in sync
 * — adding a mark here without adding it there will let the editor produce
 * values the validator rejects.
 */

import { type MarkSpec } from 'prosemirror-model';
import { marks as basicMarks } from 'prosemirror-schema-basic';

const underlineMark: MarkSpec = {
  toDOM() {
    return ['u', 0];
  },
  parseDOM: [{ tag: 'u' }, { style: 'text-decoration=underline' }],
};

const strikethroughMark: MarkSpec = {
  toDOM() {
    return ['s', 0];
  },
  parseDOM: [
    { tag: 's' },
    { tag: 'del' },
    { tag: 'strike' },
    { style: 'text-decoration=line-through' },
  ],
};

const subscriptMark: MarkSpec = {
  excludes: 'superscript',
  toDOM() {
    return ['sub', 0];
  },
  parseDOM: [{ tag: 'sub' }, { style: 'vertical-align=sub' }],
};

const superscriptMark: MarkSpec = {
  excludes: 'subscript',
  toDOM() {
    return ['sup', 0];
  },
  parseDOM: [{ tag: 'sup' }, { style: 'vertical-align=super' }],
};

export const richTextMarks: Record<string, MarkSpec> = {
  ...basicMarks,
  underline: underlineMark,
  strikethrough: strikethroughMark,
  subscript: subscriptMark,
  superscript: superscriptMark,
};
