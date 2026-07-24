// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, expect, it, vi } from 'vitest';
import {
  createQualityEditorPlugin,
  EpistolaQualityPanel,
  type QualityFinding,
} from './quality-plugin.js';
import type { PluginContext } from '../plugins/types.js';

function finding(overrides: Partial<QualityFinding> = {}): QualityFinding {
  return {
    id: 'finding-1',
    sourceId: 'source',
    sourceName: 'Source',
    ruleId: 'rule',
    severity: 'WARNING',
    status: 'OPEN',
    message: 'Message',
    nodeIds: ['node-1'],
    primaryNodeId: 'node-1',
    path: null,
    docsUrl: null,
    stale: false,
    commentCount: 0,
    ...overrides,
  };
}

describe('EpistolaQualityPanel', () => {
  it('selects a finding node through the plugin context with tab preservation', () => {
    const selectNode = vi.fn();
    const panel = new EpistolaQualityPanel();
    panel.context = { selectNode } as unknown as PluginContext;

    (panel as unknown as { _selectNode: (finding: QualityFinding) => void })._selectNode(finding());

    expect(selectNode).toHaveBeenCalledWith('node-1', {
      keepCurrentSidebarTabOpen: true,
      revealInCanvas: true,
    });
  });

  it('does not select findings without a primary node', () => {
    const selectNode = vi.fn();
    const panel = new EpistolaQualityPanel();
    panel.context = { selectNode } as unknown as PluginContext;

    (panel as unknown as { _selectNode: (finding: QualityFinding) => void })._selectNode(
      finding({ primaryNodeId: null }),
    );

    expect(selectNode).not.toHaveBeenCalled();
  });
});

describe('createQualityEditorPlugin', () => {
  it('applies the feature badge to the sidebar tab', () => {
    const plugin = createQualityEditorPlugin({
      descriptor: {
        id: 'quality',
        feature: 'quality',
        factoryExport: 'createQualityEditorPlugin',
      },
      feature: {
        enabled: true,
        badge: { label: 'Alpha', className: 'badge-alpha' },
      },
      config: {
        findingsUrl: '/quality',
        checkUrl: '/quality/check',
      },
      csrfToken: () => 'csrf-token',
    });

    expect(plugin?.sidebarTab?.badge).toEqual({ label: 'Alpha', className: 'badge-alpha' });
  });
});
