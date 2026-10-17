// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Which stencil instances the author is editing the stencil of, in place.
 *
 * A template uses a stencil by reference, so an instance's content is the
 * stencil's working copy and is locked. Editing it is a session state of the
 * editor, not part of the document: nothing about it is saved with the
 * template, and saving the template stores the instance as a reference
 * whatever its content shows. What the author changes reaches the stencil
 * only through "Save stencil".
 *
 * One state per stencil definition (one per mounted editor). The slot-lock
 * predicate reads it; changes are announced as the `stencil:editing`
 * component state so the canvas and inspector re-render.
 */

import type { NodeId } from '../../types/index.js';

/** Component-state key announcing that the set of edited instances changed. */
export const STENCIL_EDITING_STATE_KEY = 'stencil:editing';

interface ComponentStateSink {
  setComponentState(key: string, value: unknown): void;
}

export class StencilEditingState {
  private readonly _editing = new Set<string>();

  isEditing(nodeId: NodeId | string): boolean {
    return this._editing.has(nodeId);
  }

  /** Start editing the stencil of [nodeId]; with [engine], announce it. */
  start(nodeId: NodeId | string, engine?: ComponentStateSink): void {
    this._editing.add(nodeId);
    engine?.setComponentState(STENCIL_EDITING_STATE_KEY, [...this._editing]);
  }

  /** Stop editing the stencil of [nodeId]; with [engine], announce it. */
  stop(nodeId: NodeId | string, engine?: ComponentStateSink): void {
    this._editing.delete(nodeId);
    engine?.setComponentState(STENCIL_EDITING_STATE_KEY, [...this._editing]);
  }
}
