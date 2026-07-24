// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * ProseMirror schema for *inline* rich-text parameter values.
 *
 * Single-paragraph constraint: `doc.content` requires exactly one `paragraph`,
 * which itself contains text and hard-breaks (no lists, no nested blocks). The
 * editor toolbar can produce marks (bold/italic/etc.) but cannot create lists
 * or split into multiple paragraphs.
 *
 * Wire format constrained by https://epistola.app/schemas/richtext-inline-v1.json.
 * Companion: `richTextBlockSchema` for full block content.
 */

import { Schema, type NodeSpec } from 'prosemirror-model';
import { nodes as basicNodes } from 'prosemirror-schema-basic';
import { richTextMarks } from './richTextMarks.js';

const inlineNodes: Record<string, NodeSpec> = {
  // Single paragraph constraint at the doc level — typing Enter is a no-op
  // because there's no place to put a second paragraph.
  doc: { content: 'paragraph' },
  paragraph: basicNodes.paragraph,
  text: basicNodes.text,
  hard_break: basicNodes.hard_break,
};

/** ProseMirror schema for *inline* (single-paragraph) rich-text values. */
export const richTextInlineSchema = new Schema({
  nodes: inlineNodes,
  marks: richTextMarks,
});

/** Default value for a freshly-created `richTextInline` field. */
export const EMPTY_RICH_TEXT_INLINE_DOC = {
  type: 'doc',
  content: [{ type: 'paragraph' }],
} as const;
