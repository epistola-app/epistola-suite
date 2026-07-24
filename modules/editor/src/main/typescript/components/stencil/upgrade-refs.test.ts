// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { collectStencilUpgradeRefs } from './upgrade-refs.js';
import { createTestDocument, nodeId } from '../../engine/test-helpers.js';
import type { Node, TemplateDocument } from '../../types/index.js';

/** Build a document whose root slot holds the given stencil-ish nodes. */
function docWithStencils(nodes: Node[]): TemplateDocument {
  const base = createTestDocument();
  const rootSlot = Object.values(base.slots)[0];
  for (const n of nodes) {
    base.nodes[n.id] = n;
    rootSlot.children.push(n.id);
  }
  return base;
}

function stencil(id: string, props: Record<string, unknown>): Node {
  return { id: nodeId(id), type: 'stencil', props } as unknown as Node;
}

describe('collectStencilUpgradeRefs', () => {
  it('includes catalogKey so the versions URL never gets an undefined segment', () => {
    const doc = docWithStencils([
      stencil('s1', { stencilId: 'default-footer', version: 2, catalogKey: 'sittard-geleen' }),
    ]);

    expect(collectStencilUpgradeRefs(doc)).toEqual([
      { stencilId: 'default-footer', version: 2, catalogKey: 'sittard-geleen' },
    ]);
  });

  it('skips unlinked stencils that have no catalogKey', () => {
    const doc = docWithStencils([
      stencil('linked', { stencilId: 'default-footer', version: 1, catalogKey: 'default' }),
      stencil('unlinked', { stencilId: 'draft-only', version: 1 }), // no catalogKey
    ]);

    const refs = collectStencilUpgradeRefs(doc);
    expect(refs).toHaveLength(1);
    expect(refs[0].stencilId).toBe('default-footer');
  });

  it('skips stencils missing stencilId or version', () => {
    const doc = docWithStencils([
      stencil('noId', { version: 1, catalogKey: 'default' }),
      stencil('noVersion', { stencilId: 'x', catalogKey: 'default' }),
    ]);

    expect(collectStencilUpgradeRefs(doc)).toEqual([]);
  });

  it('ignores non-stencil nodes', () => {
    expect(collectStencilUpgradeRefs(createTestDocument())).toEqual([]);
  });
});
