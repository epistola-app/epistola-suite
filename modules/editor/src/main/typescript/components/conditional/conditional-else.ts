// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * The `else` branch of a conditional (contract `2.0.0-alpha.3`). A conditional made before the
 * slot existed has only `body`; "Add else branch" gives it an empty `else` slot, which renders
 * when the condition does not hold. Undo removes that slot again.
 */

import { html, nothing } from 'lit';
import { nanoid } from 'nanoid';
import type { Node, NodeId, SlotId, TemplateDocument } from '../../types/index.js';
import type { CommandResult } from '../../engine/commands.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';

export interface AddConditionalElse {
  type: 'AddConditionalElse';
  nodeId: NodeId;
  /** For redo after undo: the slot id to use again. */
  _slotId?: SlotId;
}

export interface RemoveConditionalElse {
  type: 'RemoveConditionalElse';
  nodeId: NodeId;
}

export type ConditionalCommand = AddConditionalElse | RemoveConditionalElse;

export const CONDITIONAL_COMMAND_TYPES = ['AddConditionalElse', 'RemoveConditionalElse'];

/** The id of a conditional's `else` slot, or undefined when it has none. */
export function elseSlotOf(doc: TemplateDocument, node: Node): SlotId | undefined {
  return node.slots.find((slotId) => doc.slots[slotId]?.name === 'else');
}

export function applyConditionalCommand(
  doc: TemplateDocument,
  command: ConditionalCommand,
): CommandResult {
  const node = doc.nodes[command.nodeId];
  if (!node) return { ok: false, error: `Node ${command.nodeId} not found` };
  if (node.type !== 'conditional') {
    return { ok: false, error: `${command.type} only applies to conditional nodes` };
  }

  if (command.type === 'AddConditionalElse') {
    if (elseSlotOf(doc, node)) return { ok: false, error: 'This conditional has an else branch' };
    const slotId = command._slotId ?? (nanoid() as SlotId);
    return {
      ok: true,
      doc: {
        ...doc,
        nodes: { ...doc.nodes, [node.id]: { ...node, slots: [...node.slots, slotId] } },
        slots: {
          ...doc.slots,
          [slotId]: { id: slotId, nodeId: node.id, name: 'else', children: [] },
        },
      },
      inverse: { type: 'RemoveConditionalElse', nodeId: node.id },
      structureChanged: true,
    };
  }

  const slotId = elseSlotOf(doc, node);
  if (!slotId) return { ok: false, error: 'This conditional has no else branch' };
  if (doc.slots[slotId].children.length > 0) {
    return { ok: false, error: 'The else branch is not empty' };
  }
  const { [slotId]: _removed, ...slots } = doc.slots;
  return {
    ok: true,
    doc: {
      ...doc,
      nodes: {
        ...doc.nodes,
        [node.id]: { ...node, slots: node.slots.filter((id) => id !== slotId) },
      },
      slots,
    },
    inverse: { type: 'AddConditionalElse', nodeId: node.id, _slotId: slotId },
    structureChanged: true,
  };
}

/** Inspector section: offers "Add else branch" when the conditional has none. */
export function renderConditionalInspector(node: Node, engine: EditorEngine): unknown {
  if (elseSlotOf(engine.doc, node)) return nothing;
  return html`
    <div class="inspector-section">
      <div class="inspector-section-label">Else</div>
      <button
        class="ep-btn ep-btn-outline ep-btn-sm"
        id=${`conditional-${node.id}-add-else`}
        @click=${() => engine.dispatch({ type: 'AddConditionalElse', nodeId: node.id })}
      >
        Add else branch
      </button>
    </div>
  `;
}
