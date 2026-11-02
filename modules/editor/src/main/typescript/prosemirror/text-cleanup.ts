// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Cleans up pasted text the way a render does: the characters text from Word carries and no font
 * draws become what they mean. Word's manual line break and page break become a line break, its
 * non-breaking and optional hyphens a hyphen and a soft hyphen, its Symbol and Wingdings bullets a
 * bullet. Other control characters, except tab, line feed and carriage return, are removed.
 *
 * The table is a copy of the renderer's
 * `modules/generation/src/main/resources/app/epistola/generation/text-cleanup.tsv`;
 * `text-cleanup.test.ts` fails when the two differ.
 */

import { Fragment, Slice, type Node as ProseMirrorNode, type Schema } from 'prosemirror-model';
import { Plugin } from 'prosemirror-state';

export type CleanupAction = { kind: 'break' } | { kind: 'replace'; replacement: string };

const BREAK: CleanupAction = { kind: 'break' };
const replace = (replacement: string): CleanupAction => ({ kind: 'replace', replacement });

/** What each character becomes, by code point. */
export const TEXT_CLEANUP: ReadonlyMap<number, CleanupAction> = new Map([
  [0x000b, BREAK], // Word: manual line break (Shift+Enter)
  [0x000c, BREAK], // Word: page or section break
  [0x2028, BREAK], // Unicode line separator
  [0x2029, BREAK], // Unicode paragraph separator
  [0x001e, replace('-')], // Word: non-breaking hyphen
  [0x001f, replace('­')], // Word: optional hyphen
  [0xf0b7, replace('•')], // Word bullet, Symbol font
  [0xf0a7, replace('•')], // Word square bullet, Wingdings
  [0xf076, replace('•')], // Word diamond bullet, Wingdings
  [0xf0d8, replace('•')], // Word arrowhead bullet, Wingdings
  [0xf0fc, replace('•')], // Word check-mark bullet, Wingdings
]);

function isRemovedControl(codePoint: number): boolean {
  return codePoint < 0x20 && codePoint !== 0x09 && codePoint !== 0x0a && codePoint !== 0x0d;
}

function needsCleaning(text: string): boolean {
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (TEXT_CLEANUP.has(code) || isRemovedControl(code)) return true;
  }
  return false;
}

/**
 * [text] cleaned up, split at every line break it holds. One line when there is none; the same
 * string when nothing needs cleaning.
 */
export function cleanTextLines(text: string): string[] {
  if (!needsCleaning(text)) return [text];
  const lines: string[] = [];
  let line = '';
  for (const char of text) {
    const codePoint = char.codePointAt(0) ?? 0;
    const action = TEXT_CLEANUP.get(codePoint);
    if (action?.kind === 'break') {
      lines.push(line);
      line = '';
    } else if (action?.kind === 'replace') {
      line += action.replacement;
    } else if (!isRemovedControl(codePoint)) {
      line += char;
    }
  }
  lines.push(line);
  return lines;
}

/** [text] cleaned up, line breaks as `\n`: what the renderer sets. */
export function cleanText(text: string): string {
  return cleanTextLines(text).join('\n');
}

function cleanTextNode(node: ProseMirrorNode, schema: Schema): ProseMirrorNode[] {
  const lines = cleanTextLines(node.text ?? '');
  if (lines.length === 1 && lines[0] === node.text) return [node];
  const hardBreak = schema.nodes.hard_break;
  const nodes: ProseMirrorNode[] = [];
  lines.forEach((line, index) => {
    if (index > 0) nodes.push(hardBreak ? hardBreak.create() : schema.text(' ', node.marks));
    if (line) nodes.push(schema.text(line, node.marks));
  });
  return nodes;
}

function cleanFragment(fragment: Fragment, schema: Schema): Fragment {
  const nodes: ProseMirrorNode[] = [];
  fragment.forEach((node) => {
    if (node.isText) nodes.push(...cleanTextNode(node, schema));
    else nodes.push(node.copy(cleanFragment(node.content, schema)));
  });
  return Fragment.fromArray(nodes);
}

/** [slice] with its text cleaned up, line breaks as hard breaks. */
export function cleanPastedSlice(slice: Slice, schema: Schema): Slice {
  return new Slice(cleanFragment(slice.content, schema), slice.openStart, slice.openEnd);
}

/** Cleans up whatever is pasted, from Word or anywhere else, before it lands in the document. */
export function pasteCleanupPlugin(schema: Schema): Plugin {
  return new Plugin({
    props: {
      transformPasted: (slice) => cleanPastedSlice(slice, schema),
    },
  });
}
