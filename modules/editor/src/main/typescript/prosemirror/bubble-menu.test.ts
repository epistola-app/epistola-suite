// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/** @vitest-environment happy-dom */
import { describe, expect, it } from 'vitest';
import { EditorState } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';
import { bubbleMenuPlugin } from './bubble-menu.js';
import { createMenuElement } from './bubble-menu.js';
import { epistolaSchema } from './schema.js';
import { richTextBlockSchema } from './richTextBlockSchema.js';
import { richTextInlineSchema } from './richTextInlineSchema.js';

/** Which rendered children are separators, in document order. */
function separatorMask(menuEl: HTMLElement): boolean[] {
  return Array.from(menuEl.children).map((c) => c.classList.contains('pm-bubble-sep'));
}

describe('bubble menu separators', () => {
  it('renders only mark buttons for the inline value schema', () => {
    const { menuEl } = createMenuElement(richTextInlineSchema);
    const labels = Array.from(menuEl.querySelectorAll('button')).map(
      (button) => button.textContent,
    );

    expect(labels).toEqual(['B', 'I', 'U', 'S', 'X₂', 'X²']);
    expect(labels).not.toEqual(expect.arrayContaining(['UL', 'OL', '#', '⇤', '⇥']));
    expect(separatorMask(menuEl)).not.toContain(true);
  });

  it('has no leading, trailing, or adjacent separators for the block value schema', () => {
    // richTextBlockSchema omits heading + expression, so those groups are empty.
    // Separators must not divide groups that were never rendered.
    const { menuEl } = createMenuElement(richTextBlockSchema);
    const mask = separatorMask(menuEl);

    expect(mask.at(0)).toBe(false);
    expect(mask.at(-1)).toBe(false);
    expect(mask.some((isSep, i) => isSep && mask[i + 1])).toBe(false);
  });

  it('divides groups on the full canvas schema without stray separators', () => {
    // Full schema has all four groups (marks, headings, lists, expression),
    // so separators appear between them but never lead/trail/double up.
    const { menuEl } = createMenuElement(epistolaSchema);
    const mask = separatorMask(menuEl);

    expect(mask.at(0)).toBe(false);
    expect(mask.at(-1)).toBe(false);
    expect(mask.some((isSep, i) => isSep && mask[i + 1])).toBe(false);
    // Four non-empty groups → exactly three dividers.
    expect(mask.filter(Boolean).length).toBe(3);
  });
});

describe('bubble menu editable guard', () => {
  it('does not show the menu when the editor is guarded as read-only', () => {
    const host = document.createElement('div');
    document.body.appendChild(host);
    const state = EditorState.create({
      schema: richTextBlockSchema,
      doc: richTextBlockSchema.node('doc', null, [
        richTextBlockSchema.node('paragraph', null, [richTextBlockSchema.text('Read only')]),
      ]),
      plugins: [bubbleMenuPlugin(richTextBlockSchema, { isEditable: () => false })],
    });
    const view = new EditorView(host, { state });

    view.focus();
    view.updateState(view.state);

    const menu = document.body.querySelector<HTMLElement>('.pm-bubble-menu');
    expect(menu).not.toBeNull();
    expect(menu?.style.display).toBe('none');

    view.destroy();
    host.remove();
  });
});
