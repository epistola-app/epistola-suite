// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { Selection } from 'prosemirror-state';
import type { EditorView } from 'prosemirror-view';

/** Collapse a non-empty ProseMirror selection at its current head. */
export function collapseEditorSelection(view: EditorView): void {
  const { selection } = view.state;
  if (selection.empty) return;

  view.dispatch(view.state.tr.setSelection(Selection.near(selection.$head)));
}
