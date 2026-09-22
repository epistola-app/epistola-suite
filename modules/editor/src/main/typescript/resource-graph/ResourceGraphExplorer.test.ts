// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, describe, expect, it, vi } from 'vitest';

// The canvas renderer needs a real 2d context; what is under test is the side panel beside it.
vi.mock('cytoscape', () => ({
  default: () => ({ on: () => {}, destroy: () => {}, fit: () => {}, zoom: () => {} }),
}));

import './ResourceGraphExplorer.js';
import type { ResourceType } from './model.js';

const BASE = '/tenants/acme/resource-graph';

async function focused(type: ResourceType): Promise<HTMLElement> {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ items: [], total: 0, catalogs: [] }),
    })),
  );
  const element = document.createElement('ep-resource-graph') as HTMLElement & {
    updateComplete: Promise<boolean>;
  };
  element.setAttribute('data-base-url', BASE);
  element.setAttribute('data-relocation-enabled', 'true');
  document.body.appendChild(element);
  await element.updateComplete;
  const focus = {
    id: `${type}:letters/brand`,
    type,
    catalogKey: 'letters',
    key: 'brand',
    name: 'Brand',
  };
  // The focused subgraph, as the explorer holds it after loading one.
  (element as unknown as { graph: unknown }).graph = {
    focus,
    nodes: [{ ...focus, resourceId: 'r1', catalogName: 'Letters', catalogType: 'AUTHORED' }],
    edges: [],
  };
  await element.updateComplete;
  return element;
}

describe('ep-resource-graph move hand-off', () => {
  afterEach(() => {
    document.body.replaceChildren();
    vi.unstubAllGlobals();
  });

  it.each<ResourceType>(['template', 'theme', 'stencil', 'attribute', 'codeList', 'font', 'image'])(
    'hands a focused %s to Organise in the address form it reads',
    async (type) => {
      const element = await focused(type);

      const link = [...(element.shadowRoot ?? element).querySelectorAll('a')].find((a) =>
        a.getAttribute('href')?.includes('/catalogs/organise'),
      );

      expect(link?.getAttribute('href')).toBe(
        `/tenants/acme/catalogs/organise?resource=${encodeURIComponent(`${type}:letters/brand`)}`,
      );
    },
  );
});
