// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import { html } from 'lit';
import { EpistolaSidebar } from './EpistolaSidebar.js';

type SidebarHandle = EpistolaSidebar & {
  _activeTab: string;
  willUpdate: (changed: Map<string, unknown>) => void;
};

function selectedNodeChanged(): Map<string, unknown> {
  return new Map([['selectedNodeId', null]]);
}

describe('EpistolaSidebar selection behavior', () => {
  it('moves to the Inspector on a regular node selection', () => {
    const sidebar = new EpistolaSidebar() as SidebarHandle;
    sidebar.pluginTabs = [{ id: 'quality', label: 'Quality', render: () => html`` }];
    sidebar._activeTab = 'quality';
    sidebar.selectedNodeId = 'node-1' as never;

    sidebar.willUpdate(selectedNodeChanged());

    expect(sidebar._activeTab).toBe('inspector');
  });

  it('preserves the active tab for one selection change only', () => {
    const sidebar = new EpistolaSidebar() as SidebarHandle;
    sidebar.pluginTabs = [{ id: 'quality', label: 'Quality', render: () => html`` }];
    sidebar._activeTab = 'quality';

    sidebar.preserveActiveTabForNextSelection();
    sidebar.selectedNodeId = 'node-1' as never;
    sidebar.willUpdate(selectedNodeChanged());

    expect(sidebar._activeTab).toBe('quality');

    sidebar.selectedNodeId = 'node-2' as never;
    sidebar.willUpdate(selectedNodeChanged());

    expect(sidebar._activeTab).toBe('inspector');
  });
});
