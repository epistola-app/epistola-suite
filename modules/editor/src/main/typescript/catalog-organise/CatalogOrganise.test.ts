// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './CatalogOrganise.js';
import { pathAfterMove, type CatalogOrganise } from './CatalogOrganise.js';

const BASE = '/tenants/acme/catalogs/organise';

const catalogs = [
  { key: 'letters', name: 'Letters', type: 'authored' },
  { key: 'shared', name: 'Shared', type: 'authored' },
];

function resource(type: string, catalogKey: string, key: string, name = key) {
  return {
    id: `${type}:${catalogKey}/${key}`,
    type,
    catalogKey,
    key,
    name,
    catalogName: catalogKey,
  };
}

type Reply = { status?: number; body: unknown };

/** Answers each endpoint under [BASE] from [replies], recording every URL asked for. */
function serve(replies: Record<string, Reply | (() => Reply)>): string[] {
  const requested: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      requested.push(input);
      const endpoint = input.replace(BASE, '').split('?')[0];
      const reply = replies[endpoint];
      if (!reply) throw new Error(`unexpected request ${input}`);
      const { status = 200, body } = typeof reply === 'function' ? reply() : reply;
      return {
        ok: status >= 200 && status < 300,
        status,
        json: async () => body,
      };
    }),
  );
  return requested;
}

async function settle(element: CatalogOrganise): Promise<void> {
  for (let i = 0; i < 6; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await element.updateComplete;
  }
}

async function mount(attributes: Record<string, string>, host: Element = document.body) {
  const element = document.createElement('ep-catalog-organise') as CatalogOrganise;
  element.setAttribute('data-base-url', BASE);
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, value);
  host.appendChild(element);
  await settle(element);
  return element;
}

function button(element: Element, text: RegExp): HTMLButtonElement | undefined {
  return [...element.querySelectorAll('button')].find((candidate) =>
    text.test(candidate.textContent ?? ''),
  );
}

async function click(element: CatalogOrganise, text: RegExp): Promise<void> {
  const target = button(element, text);
  if (!target) throw new Error(`no button matching ${text}`);
  target.click();
  await settle(element);
}

const executablePlan = (source: ReturnType<typeof resource>) => ({
  relocations: [
    {
      source: { type: source.type, catalogKey: source.catalogKey, key: source.key },
      target: { type: source.type, catalogKey: 'shared', key: source.key },
      mutableRewriteCount: 0,
      immutableReferenceCount: 0,
    },
  ],
  mutableRewriteCount: 0,
  immutableReferenceCount: 0,
  blockers: [],
  warnings: [],
  planFingerprint: 'fp',
  executable: true,
});

describe('ep-catalog-organise', () => {
  beforeEach(() => {
    document.body.replaceChildren();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('says a stale plan must be previewed again, and withdraws the Move it offered', async () => {
    const header = resource('stencil', 'letters', 'header');
    serve({
      '/resources': { body: { catalogs, resources: [header] } },
      '/preview': { body: executablePlan(header) },
      '/execute': {
        status: 409,
        body: {
          code: 'stale-plan',
          message: 'Something changed since you previewed; preview again',
        },
      },
    });
    const element = await mount({ 'data-preselected': header.id, 'data-can-apply': 'true' });

    await click(element, /^\s*Preview\s*$/);
    await click(element, /^\s*Move 1 resource/);

    expect(element.textContent).toContain('Something changed since you previewed');
    expect(element.textContent).not.toContain('Request failed (409)');
    expect(button(element, /^\s*Move 1 resource/)).toBeUndefined();
  });

  it('shows a blocker on the resource it is about, not on another type at the same address', async () => {
    const stencil = resource('stencil', 'letters', 'header', 'Header stencil');
    const template = resource('template', 'letters', 'header', 'Header template');
    serve({
      '/resources': { body: { catalogs, resources: [stencil, template] } },
      '/preview': {
        body: {
          ...executablePlan(template),
          executable: false,
          blockers: [
            {
              code: 'target-occupied',
              message: 'shared/header is already a template',
              source: { type: 'TEMPLATE', catalogKey: 'letters', key: 'header' },
              sourceId: template.id,
            },
          ],
        },
      },
    });
    const element = await mount({
      'data-preselected': `${stencil.id},${template.id}`,
      'data-can-apply': 'true',
    });

    await click(element, /^\s*Preview\s*$/);

    const rows = [...element.querySelectorAll('tbody tr')];
    const rowFor = (name: string) => rows.find((row) => row.textContent?.includes(name));
    expect(rowFor('Header template')?.textContent).toContain('already a template');
    expect(rowFor('Header stencil')?.textContent).not.toContain('already a template');
  });

  it('tells a reader who cannot apply a move why, instead of offering Move', async () => {
    const header = resource('stencil', 'letters', 'header');
    serve({
      '/resources': { body: { catalogs, resources: [header] } },
      '/preview': { body: executablePlan(header) },
    });
    const element = await mount({ 'data-preselected': header.id, 'data-can-apply': 'false' });

    await click(element, /^\s*Preview\s*$/);

    expect(button(element, /^\s*Move 1 resource/)).toBeUndefined();
    expect(element.textContent).toContain('catalog management');
  });

  it('names every movable type when there is nothing to move', async () => {
    serve({ '/resources': { body: { catalogs, resources: [] } } });
    const element = await mount({ 'data-can-apply': 'true' });

    for (const type of [
      'templates',
      'stencils',
      'themes',
      'fonts',
      'images',
      'code lists',
      'attributes',
    ]) {
      expect(element.textContent?.toLowerCase()).toContain(type);
    }
  });

  it('asks for a deep-linked resource by name, and says when the list was cut off', async () => {
    const named = resource('stencil', 'letters', 'header-9');
    const requested = serve({
      '/resources': { body: { catalogs, resources: [named], truncated: true } },
    });
    const element = await mount({ 'data-preselected': named.id, 'data-can-apply': 'true' });

    expect(requested[0]).toContain(`resource=${encodeURIComponent(named.id)}`);
    expect(element.textContent).toContain('first 50');
  });

  it('after moving the resource its page is about, goes to that page at the new address', async () => {
    const invoice = resource('template', 'letters', 'invoice');
    serve({
      '/resources': { body: { catalogs, resources: [invoice] } },
      '/preview': { body: executablePlan(invoice) },
      '/execute': { body: executablePlan(invoice) },
    });
    const assign = vi.fn();
    vi.stubGlobal('location', {
      ...window.location,
      pathname: '/tenants/acme/templates/letters/invoice/settings',
      assign,
    });
    const dialog = document.createElement('dialog');
    document.body.appendChild(dialog);
    const element = await mount({ 'data-single': invoice.id, 'data-can-apply': 'true' }, dialog);

    const destination = element.querySelector<HTMLSelectElement>(
      '[data-testid="organise-single-destination"]',
    );
    if (!destination) throw new Error('no destination select');
    destination.value = 'shared';
    destination.dispatchEvent(new Event('change'));
    await settle(element);
    await click(element, /^\s*Preview\s*$/);
    await click(element, /^\s*Move 1 resource/);

    // No alias is left behind, so reloading the old address would be a 404.
    expect(assign).toHaveBeenCalledWith('/tenants/acme/templates/shared/invoice/settings');
  });
});

describe('pathAfterMove', () => {
  const plan = {
    source: { type: 'template', catalogKey: 'letters', key: 'invoice' },
    target: { type: 'template', catalogKey: 'shared', key: 'bill' },
  };

  it('replaces the catalog and key segments, keeping the rest of the path', () => {
    expect(pathAfterMove('/tenants/acme/templates/letters/invoice/settings', plan)).toBe(
      '/tenants/acme/templates/shared/bill/settings',
    );
  });

  it('matches whole segments only', () => {
    const path = '/tenants/acme/templates/letters/invoice-2/settings';
    expect(pathAfterMove(path, plan)).toBe(path);
  });
});
