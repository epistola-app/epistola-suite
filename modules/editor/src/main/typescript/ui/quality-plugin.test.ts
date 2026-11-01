// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, expect, it, vi } from 'vitest';
import { render } from 'lit';
import {
  createQualityEditorPlugin,
  EpistolaQualityPanel,
  renderChecks,
  suggestionLabel,
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

describe('language findings in the panel', () => {
  it('shows the flagged text and the suggestions, never applying them', async () => {
    const panel = new EpistolaQualityPanel();
    document.body.appendChild(panel);
    (panel as unknown as { _data: unknown })._data = {
      currentInputKey: null,
      openCount: 1,
      staleCount: 0,
      findings: [
        finding({ message: 'Possible typo', quote: 'tekts', suggestions: ['tekst', 'tests'] }),
      ],
    };
    await panel.updateComplete;

    expect(panel.querySelector('.quality-finding-quote')?.textContent).toBe('“tekts”');
    expect(
      panel.querySelector('.quality-finding-suggestions')?.textContent?.replace(/\s+/g, ' ').trim(),
    ).toBe('Suggestions: “tekst”, “tests”');
    panel.remove();
  });

  it('reads an empty or blank replacement as an instruction', () => {
    expect(suggestionLabel('')).toBe('remove it');
    expect(suggestionLabel(' ')).toBe('one space');
    expect(suggestionLabel('voor')).toBe('“voor”');
  });

  it('lists the checkers that did not check, with their reason, and stays silent when all did', () => {
    const container = document.createElement('div');
    render(
      renderChecks([
        { sourceId: 'writing-rules', sourceName: 'Writing rules', status: 'CHECKED', reason: null },
        {
          sourceId: 'languagetool',
          sourceName: 'LanguageTool',
          status: 'SKIPPED',
          reason: 'not configured on this installation',
        },
        { sourceId: 'other', sourceName: 'Other', status: 'FAILED', reason: 'unavailable' },
      ]),
      container,
    );

    const items = [...container.querySelectorAll('li')].map((li) =>
      li.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(items).toEqual([
      'LanguageTool skipped: not configured on this installation',
      'Other failed: unavailable',
    ]);
    expect(container.querySelector('.quality-check-failed')).not.toBeNull();

    const quiet = document.createElement('div');
    render(
      renderChecks([
        { sourceId: 'writing-rules', sourceName: 'Writing rules', status: 'CHECKED', reason: null },
      ]),
      quiet,
    );
    expect(quiet.querySelector('ul')).toBeNull();
  });
});
