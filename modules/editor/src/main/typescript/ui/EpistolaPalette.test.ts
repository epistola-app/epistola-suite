// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it } from 'vitest';
import { EditorEngine } from '../engine/EditorEngine.js';
import { createDefaultRegistry } from '../engine/registry.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';
import { EpistolaPalette } from './EpistolaPalette.js';

function emptyDocument(): TemplateDocument {
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: { root: { id: 'root' as NodeId, type: 'root', slots: ['root-slot' as SlotId] } },
    slots: {
      'root-slot': {
        id: 'root-slot' as SlotId,
        nodeId: 'root' as NodeId,
        name: 'children',
        children: [],
      },
    },
    themeRef: { type: 'inherit' },
  };
}

afterEach(() => {
  document.body.replaceChildren();
});

describe('EpistolaPalette', () => {
  // "Text" and "Rich Text Block" sat side by side, both said only "Click to insert …", and nothing
  // told an author that one is typed and the other comes from a data field.
  it('says what the two text blocks are for', async () => {
    const palette = new EpistolaPalette();
    palette.engine = new EditorEngine(emptyDocument(), createDefaultRegistry());
    document.body.appendChild(palette);
    await palette.updateComplete;

    const title = (type: string) =>
      palette.querySelector<HTMLElement>(`[data-testid="palette-item-${type}"]`)?.title;
    expect(title('text')).toBe('Text you type. Type {{ to put a data field in it.');
    expect(title('richTextVariable')).toBe(
      'Formatted text that comes from a data field, such as a letter body your application sends.',
    );
    // Blocks without a hint keep the plain one.
    expect(title('separator')).toBe('Click to insert Separator');
  });
});
