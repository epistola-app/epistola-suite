// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * The editor spaces blocks as a draft renders (rendering defaults V7): the theme's
 * `defaultBlockStyles` margins for a flow block, else 6pt below it whatever the spacing unit; text
 * keeps none of its own, and the separator its component defaults.
 */

import { describe, it, expect } from 'vitest';
import { blockDefaultStyles } from './styles.js';
import { createDefaultRegistry } from './registry.js';
import { EditorEngine } from './EditorEngine.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';

describe('blockDefaultStyles', () => {
  it('gives a flow block 6pt below it when the theme names no baseline', () => {
    expect(blockDefaultStyles('container', undefined, undefined)).toEqual({ marginBottom: '6pt' });
    expect(blockDefaultStyles('image', undefined, {})).toEqual({ marginBottom: '6pt' });
  });

  it('gives text no space of its own without a baseline', () => {
    expect(blockDefaultStyles('text', undefined, undefined)).toEqual({});
  });

  it("takes the theme's baseline margins, margins only", () => {
    expect(
      blockDefaultStyles('table', undefined, {
        marginTop: '2sp',
        marginBottom: '8pt',
        color: 'red',
      }),
    ).toEqual({ marginTop: '2sp', marginBottom: '8pt' });
    expect(blockDefaultStyles('text', undefined, { marginBottom: '4pt' })).toEqual({
      marginBottom: '4pt',
    });
  });

  it("leaves the separator's own defaults alone", () => {
    const own = { marginTop: '1.5sp', marginBottom: '1.5sp' };
    expect(blockDefaultStyles('separator', own, { marginBottom: '20pt' })).toBe(own);
  });
});

describe('the engine resolves a block with the theme baseline', () => {
  const doc = (): TemplateDocument =>
    ({
      modelVersion: 1,
      root: 'root' as NodeId,
      nodes: {
        root: { id: 'root' as NodeId, type: 'root', slots: ['children' as SlotId] },
        c1: { id: 'c1' as NodeId, type: 'container', slots: ['c1-children' as SlotId] },
      },
      slots: {
        children: {
          id: 'children' as SlotId,
          nodeId: 'root' as NodeId,
          name: 'children',
          children: ['c1' as NodeId],
        },
        'c1-children': {
          id: 'c1-children' as SlotId,
          nodeId: 'c1' as NodeId,
          name: 'children',
          children: [],
        },
      },
    }) as unknown as TemplateDocument;

  it('falls back to 6pt, and follows the theme when it names one', () => {
    const engine = new EditorEngine(doc(), createDefaultRegistry());
    expect(engine.getResolvedNodeStyles('c1' as NodeId).marginBottom).toBe('6pt');
    engine.setTheme({ defaultBlockStyles: { marginBottom: '12pt' } });
    expect(engine.getResolvedNodeStyles('c1' as NodeId).marginBottom).toBe('12pt');
  });
});
