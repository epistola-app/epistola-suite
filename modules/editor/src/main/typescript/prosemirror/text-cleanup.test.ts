// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import type { Schema } from 'prosemirror-model';
import { EditorState, type Plugin } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';
import { buildRichTextInputPlugins } from '../data-contract/sections/EpistolaRichTextInput.js';
import { createPlugins } from './plugins.js';
import { richTextBlockSchema } from './richTextBlockSchema.js';
import { richTextInlineSchema } from './richTextInlineSchema.js';
import { epistolaSchema } from './schema.js';
import { TEXT_CLEANUP, cleanText, type CleanupAction } from './text-cleanup.js';

const RENDERER_TABLE = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../../../generation/src/main/resources/app/epistola/generation/text-cleanup.tsv',
);

const mountedViews: EditorView[] = [];

afterEach(() => {
  for (const view of mountedViews) view.destroy();
  mountedViews.length = 0;
  document.body.replaceChildren();
});

function view(schema: Schema, plugins: Plugin[]): EditorView {
  const state = EditorState.create({ schema, plugins });
  const mounted = new EditorView(document.body.appendChild(document.createElement('div')), {
    state,
  });
  mountedViews.push(mounted);
  return mounted;
}

function templateEditor(): EditorView {
  return view(
    epistolaSchema,
    createPlugins(epistolaSchema, { expressionNodeViewOptions: {} as never }),
  );
}

/** The first paragraph's inline content as `text` and `<br>` for a hard break. */
function firstParagraph(editor: EditorView): string {
  const parts: string[] = [];
  editor.state.doc.firstChild?.forEach((node) => {
    parts.push(node.type.name === 'hard_break' ? '<br>' : (node.text ?? ''));
  });
  return parts.join('');
}

describe('text cleanup table', () => {
  it('is the renderer table', () => {
    const rendererTable = new Map<number, CleanupAction>();
    for (const line of readFileSync(RENDERER_TABLE, 'utf8').split('\n')) {
      if (!line.trim() || line.startsWith('#')) continue;
      const [codePoint, action, replacement] = line.split('\t');
      const code = parseInt(codePoint.slice(2), 16);
      rendererTable.set(
        code,
        action === 'break'
          ? { kind: 'break' }
          : {
              kind: 'replace',
              replacement: String.fromCodePoint(parseInt(replacement.slice(2), 16)),
            },
      );
    }

    expect(new Map(TEXT_CLEANUP)).toEqual(rendererTable);
  });
});

describe('cleanText', () => {
  it("turns Word's breaks into line breaks, its hyphens into a hyphen and a soft hyphen", () => {
    expect(cleanText('a\u000Bb\u000Cc d e')).toBe('a\nb\nc\nd\ne');
    expect(cleanText('06\u001E123 onder\u001Fwerp')).toBe('06-123 onder­werp');
  });

  it("turns Word's Symbol and Wingdings bullets into a bullet", () => {
    expect(cleanText('\t\t\t\t')).toBe('•\t•\t•\t•\t•');
  });

  it('removes other control characters and keeps tab and line endings', () => {
    expect(cleanText('a\u0000\tb\u001C\r\n\u0007c')).toBe('a\tb\r\nc');
  });

  it('returns text without any of them as it is', () => {
    const text = 'Gewone tekst — met “aanhalingstekens”, € en 😀';
    expect(cleanText(text)).toBe(text);
  });
});

describe('pasting into the template text editor', () => {
  it("turns a Word line break into a hard break and Word's hyphen and bullet into characters fonts have", () => {
    const editor = templateEditor();

    editor.pasteText('\tPostbus 12\u000B1234 AB, tel. 06\u001E12345678');

    expect(firstParagraph(editor)).toBe('•\tPostbus 12<br>1234 AB, tel. 06-12345678');
  });

  it('keeps the formatting of pasted HTML while cleaning its text', () => {
    const editor = templateEditor();

    editor.pasteHTML('<p>Dear <strong>Mr\u000BJansen</strong></p>');

    const paragraph = editor.state.doc.firstChild!;
    expect(firstParagraph(editor)).toBe('Dear Mr<br>Jansen');
    expect(paragraph.child(1).marks.map((mark) => mark.type.name)).toEqual(['strong']);
    expect(paragraph.child(3).marks.map((mark) => mark.type.name)).toEqual(['strong']);
  });
});

describe('pasting into a rich-text data value', () => {
  it.each([
    ['inline', richTextInlineSchema],
    ['block', richTextBlockSchema],
  ] as const)('cleans the text in %s mode', (mode, schema) => {
    const editor = view(schema, buildRichTextInputPlugins(mode, schema));

    editor.pasteText('Postbus 12\u000B1234 AB');

    expect(firstParagraph(editor)).toBe('Postbus 12<br>1234 AB');
  });
});
