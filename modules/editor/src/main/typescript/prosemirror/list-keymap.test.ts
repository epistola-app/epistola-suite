import { describe, it, expect } from 'vitest';
import { EditorState, TextSelection } from 'prosemirror-state';
import { sinkListItem, liftListItem, splitListItem } from 'prosemirror-schema-list';
import { Node as PMNode } from 'prosemirror-model';
import { epistolaSchema } from './schema.js';

/**
 * The Tab / Shift-Tab / Enter list keymap (see `plugins.ts`) is wired to these
 * three prosemirror-schema-list commands. These tests pin that our customised
 * `bullet_list` (which carries a `listStyle` attribute) still supports nesting,
 * so authors can build "een opsomming in een opsomming" with Tab.
 */
describe('list editing commands on the Epistola schema', () => {
  const schema = epistolaSchema;

  /** A bullet list with two items, "A" then "B". */
  function twoItemList(): PMNode {
    const item = (text: string) =>
      schema.node('list_item', null, [schema.node('paragraph', null, [schema.text(text)])]);
    return schema.node('doc', null, [
      schema.node('bullet_list', { listStyle: 'disc' }, [item('A'), item('B')]),
    ]);
  }

  /** Position of the cursor inside the text node whose content is [text]. */
  function posInText(doc: PMNode, text: string): number {
    let pos = 1;
    doc.descendants((node, p) => {
      if (node.isText && node.text === text) pos = p + 1;
    });
    return pos;
  }

  function stateInItem(doc: PMNode, text: string): EditorState {
    return EditorState.create({ doc, selection: TextSelection.create(doc, posInText(doc, text)) });
  }

  it('Tab (sinkListItem) nests the second item under the first as a sub-list', () => {
    const state = stateInItem(twoItemList(), 'B');

    let next = state;
    const handled = sinkListItem(schema.nodes.list_item)(state, (tr) => {
      next = state.apply(tr);
    });

    expect(handled).toBe(true);
    const outer = next.doc.firstChild!; // bullet_list
    expect(outer.type.name).toBe('bullet_list');
    expect(outer.childCount).toBe(1); // "B" no longer a sibling of "A"

    const firstItem = outer.firstChild!; // list_item "A"
    const nested = firstItem.lastChild!; // nested bullet_list
    expect(nested.type.name).toBe('bullet_list');
    expect(nested.textContent).toBe('B');
  });

  it('Shift-Tab (liftListItem) lifts a nested item back out to the parent list', () => {
    // Start from the nested shape produced by sinking, then lift "B" back out.
    const sunk = stateInItem(twoItemList(), 'B');
    let nestedState = sunk;
    sinkListItem(schema.nodes.list_item)(sunk, (tr) => {
      nestedState = sunk.apply(tr);
    });

    const state = stateInItem(nestedState.doc, 'B');
    let next = state;
    const handled = liftListItem(schema.nodes.list_item)(state, (tr) => {
      next = state.apply(tr);
    });

    expect(handled).toBe(true);
    const outer = next.doc.firstChild!;
    expect(outer.type.name).toBe('bullet_list');
    expect(outer.childCount).toBe(2); // "A" and "B" siblings again
  });

  it('Enter (splitListItem) creates a new sibling list item', () => {
    const state = stateInItem(twoItemList(), 'B');
    let next = state;
    const handled = splitListItem(schema.nodes.list_item)(state, (tr) => {
      next = state.apply(tr);
    });

    expect(handled).toBe(true);
    expect(next.doc.firstChild!.childCount).toBe(3); // A, B, and the new empty item
  });
});
