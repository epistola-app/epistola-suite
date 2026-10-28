// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { type MarginTree, collapseMargins, toPoints } from './margin-collapse.js';

/** A small tree: root > [c1 > [t1], c2 > [t2]], every block with a 20pt bottom baseline. */
function tree(overrides: Record<string, Record<string, unknown>> = {}): MarginTree {
  const nodes: Record<
    string,
    { type: string; children: string[]; styles: Record<string, unknown> }
  > = {
    root: { type: 'root', children: ['c1', 'c2'], styles: {} },
    c1: { type: 'container', children: ['t1'], styles: { marginBottom: '20pt' } },
    c2: { type: 'container', children: ['t2'], styles: { marginBottom: '20pt' } },
    t1: { type: 'text', children: [], styles: { marginBottom: '20pt' } },
    t2: { type: 'text', children: [], styles: { marginBottom: '20pt' } },
  };
  for (const [id, styles] of Object.entries(overrides)) {
    nodes[id] = { ...nodes[id], styles: { ...nodes[id].styles, ...styles } };
  }
  const parent = new Map<string, string>();
  for (const [id, n] of Object.entries(nodes))
    for (const child of n.children) parent.set(child, id);
  return {
    ownStyles: (id) => nodes[id]?.styles ?? {},
    type: (id) => nodes[id]?.type,
    children: (id) => nodes[id]?.children ?? [],
    parent: (id) => parent.get(id),
    spacingUnit: 4,
  };
}

const styled = (t: MarginTree, id: string) => collapseMargins(id, t, t.ownStyles(id));

describe('collapseMargins', () => {
  it('moves a container’s last child’s bottom margin into the container, the larger winning', () => {
    const t = tree({ t1: { marginBottom: '30pt' } });
    expect(styled(t, 'c1').marginBottom).toBe('30pt');
    expect(styled(t, 't1').marginBottom).toBe('0pt');
  });

  it('keeps the container’s own margin when it is the larger', () => {
    const t = tree();
    expect(styled(t, 'c1').marginBottom).toBe('20pt');
    expect(styled(t, 't1').marginBottom).toBe('0pt');
  });

  it('does not collapse through bottom padding, a bottom border or a minimum height', () => {
    for (const blocker of [
      { paddingBottom: '2pt' },
      { borderBottom: '1pt solid #000' },
      { minHeight: '40pt' },
    ]) {
      const t = tree({ c1: blocker });
      expect(styled(t, 't1').marginBottom).toBe('20pt');
      expect(styled(t, 'c1').marginBottom).toBe('20pt');
    }
  });

  it('leaves siblings alone: only the last child gives up its bottom margin', () => {
    const nodes = tree();
    const withTwo: MarginTree = {
      ...nodes,
      children: (id) => (id === 'c1' ? ['t1', 't2'] : nodes.children(id)),
      parent: (id) => (id === 't1' || id === 't2' ? 'c1' : nodes.parent(id)),
    };
    expect(styled(withTwo, 't1').marginBottom).toBe('20pt');
    expect(styled(withTwo, 't2').marginBottom).toBe('0pt');
  });

  it('carries the collapse through nested containers', () => {
    const t: MarginTree = (() => {
      const base = tree({ t1: { marginBottom: '40pt' } });
      return {
        ...base,
        type: (id) => (id === 'inner' ? 'container' : base.type(id)),
        children: (id) => (id === 'c1' ? ['inner'] : id === 'inner' ? ['t1'] : base.children(id)),
        parent: (id) => (id === 'inner' ? 'c1' : id === 't1' ? 'inner' : base.parent(id)),
        ownStyles: (id) => (id === 'inner' ? { marginBottom: '10pt' } : base.ownStyles(id)),
      };
    })();
    expect(styled(t, 'c1').marginBottom).toBe('40pt');
    expect(styled(t, 'inner').marginBottom).toBe('0pt');
    expect(styled(t, 't1').marginBottom).toBe('0pt');
  });

  it('compares sp tokens in points through the spacing unit', () => {
    expect(toPoints('1.5sp', 4)).toBe(6);
    expect(toPoints('8px', 4)).toBe(6);
    expect(toPoints('2em', 4)).toBeNull();
  });
});
