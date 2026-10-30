// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './CatalogOrganise.js';
import { pathAfterMove, type CatalogOrganise } from './CatalogOrganise.js';

const BASE = '/tenants/acme/catalogs/organise';

const catalogs = [
  { key: 'letters', name: 'Letters', editable: true },
  { key: 'shared', name: 'Shared', editable: true },
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
      source: {
        type: source.type,
        catalogKey: source.catalogKey,
        catalogAddress: `acme/${source.catalogKey}`,
        key: source.key,
      },
      target: {
        type: source.type,
        catalogKey: 'shared',
        catalogAddress: 'acme/shared',
        key: source.key,
      },
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

  it('says everything that uses it follows, what each next release becomes, and in which order', async () => {
    const house = resource('theme', 'letters', 'house');
    serve({
      '/resources': {
        body: {
          catalogs: [
            { key: 'acme/letters', name: 'Letters', editable: true },
            { key: 'acme/shared', name: 'Shared', editable: true },
          ],
          resources: [house],
        },
      },
      '/preview': {
        body: {
          ...executablePlan(house),
          releases: [
            { catalog: 'acme/shared', kind: 'ADDITIVE', reasons: ["gains theme 'house'"] },
            {
              catalog: 'acme/letters',
              kind: 'BREAKING',
              reasons: ["no longer contains theme 'house'", 'now depends on shared'],
            },
          ],
          releaseOrder: ['acme/shared', 'acme/letters'],
        },
      },
    });
    const element = await mount({ 'data-preselected': house.id, 'data-can-apply': 'true' });

    await click(element, /^\s*Preview\s*$/);

    expect(element.querySelector('[data-testid="organise-everything-updated"]')).not.toBeNull();
    const releases = [...element.querySelectorAll('[data-testid="organise-release"]')].map((item) =>
      item.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(releases).toEqual([
      "The next release of Shared is a minor release: gains theme 'house'",
      "The next release of Letters is a major release: no longer contains theme 'house'; now depends on shared",
    ]);
    expect(element.querySelector('[data-testid="organise-release-order"]')?.textContent).toContain(
      'Shared, then Letters',
    );
  });

  it('creates a shared catalog and makes it where the selection goes', async () => {
    const house = resource('theme', 'letters', 'house');
    const requested = serve({
      '/resources': { body: { catalogs, resources: [house] } },
      '/catalogs': { body: { key: 'acme/brand', name: 'Brand', editable: true } },
    });
    const element = await mount({ 'data-preselected': house.id, 'data-can-apply': 'true' });

    await click(element, /Create a shared catalog/);
    const type = (testId: string, value: string) => {
      const input = element.querySelector<HTMLInputElement>(`[data-testid="${testId}"]`);
      if (!input) throw new Error(`no ${testId}`);
      input.value = value;
      input.dispatchEvent(new InputEvent('input'));
    };
    type('organise-new-shared-name', 'Brand');
    type('organise-new-shared-key', 'brand');
    await settle(element);
    await click(element, /Create and move here/);

    expect(requested.some((url) => url.endsWith('/catalogs'))).toBe(true);
    const destination = element.querySelector<HTMLSelectElement>(
      '[data-testid="organise-shared-destination"]',
    );
    expect(destination?.value).toBe('acme/brand');
  });

  it('offers no catalog creation to a reader who cannot apply a move', async () => {
    const house = resource('theme', 'letters', 'house');
    serve({ '/resources': { body: { catalogs, resources: [house] } } });
    const element = await mount({ 'data-preselected': house.id, 'data-can-apply': 'false' });

    expect(button(element, /Create a shared catalog/)).toBeUndefined();
  });

  it('asks only for what the refactoring chosen changes, and names it on the button', async () => {
    const house = resource('theme', 'letters', 'house');
    serve({
      '/resources': { body: { catalogs, resources: [house] } },
      '/preview': { body: executablePlan(house) },
    });

    const rename = await mount({
      'data-single': house.id,
      'data-mode': 'rename',
      'data-can-apply': 'true',
    });
    expect(rename.querySelector('[data-testid="organise-single-destination"]')).toBeNull();
    expect(rename.querySelector('[data-testid="organise-single-key"]')).not.toBeNull();
    const key = rename.querySelector<HTMLInputElement>('[data-testid="organise-single-key"]');
    if (!key) throw new Error('no key input');
    key.value = 'brand';
    key.dispatchEvent(new InputEvent('input'));
    await settle(rename);
    await click(rename, /^\s*Preview\s*$/);
    expect(button(rename, /^\s*Rename\s*$/)).toBeDefined();
    expect(rename.textContent).toContain('Ready to rename');

    document.body.replaceChildren();
    const move = await mount({
      'data-single': house.id,
      'data-mode': 'move',
      'data-can-apply': 'true',
    });
    expect(move.querySelector('[data-testid="organise-single-key"]')).toBeNull();
    expect(move.querySelector('[data-testid="organise-single-destination"]')).not.toBeNull();
  });

  it('says why a single rename is blocked, and that nothing will be renamed', async () => {
    const house = resource('theme', 'letters', 'house');
    serve({
      '/resources': { body: { catalogs, resources: [house] } },
      '/preview': {
        body: {
          ...executablePlan(house),
          executable: false,
          blockers: [
            {
              code: 'invalid-target-key',
              message: "'Bad Key' cannot be used: lowercase letters only",
              source: house,
              sourceId: house.id,
            },
          ],
        },
      },
    });
    const element = await mount({
      'data-single': house.id,
      'data-mode': 'rename',
      'data-can-apply': 'true',
    });
    const key = element.querySelector<HTMLInputElement>('[data-testid="organise-single-key"]');
    if (!key) throw new Error('no key input');
    key.value = 'Bad Key';
    key.dispatchEvent(new InputEvent('input'));
    await settle(element);
    await click(element, /^\s*Preview\s*$/);

    expect(element.textContent).toContain('Blocked — nothing will be renamed');
    expect(element.textContent).toContain("'Bad Key' cannot be used: lowercase letters only");
  });

  it('offers another key when the catalog a resource moves to already has its key', async () => {
    const house = resource('theme', 'letters', 'house');
    serve({
      '/resources': { body: { catalogs, resources: [house] } },
      '/preview': {
        body: {
          ...executablePlan(house),
          executable: false,
          blockers: [
            {
              code: 'target-occupied',
              message: 'theme:acme/shared/house is already a resource; give it another key',
              source: house,
              sourceId: house.id,
            },
          ],
        },
      },
    });
    const element = await mount({
      'data-single': house.id,
      'data-mode': 'move',
      'data-can-apply': 'true',
    });
    const destination = element.querySelector<HTMLSelectElement>(
      '[data-testid="organise-single-destination"]',
    );
    if (!destination) throw new Error('no destination select');
    destination.value = 'shared';
    destination.dispatchEvent(new Event('change'));
    await settle(element);
    expect(element.querySelector('[data-testid="organise-single-key"]')).toBeNull();
    await click(element, /^\s*Preview\s*$/);

    expect(element.textContent).toContain('is already a resource; give it another key');
    expect(element.querySelector('[data-testid="organise-single-key"]')).not.toBeNull();
  });

  it('opened to create a shared catalog, shows its form before anything is selected', async () => {
    serve({
      '/resources': { body: { catalogs, resources: [resource('theme', 'letters', 'house')] } },
    });
    const element = await mount({ 'data-start-shared': 'true', 'data-can-apply': 'true' });

    expect(element.querySelector('[data-testid="organise-new-shared-start"]')).not.toBeNull();
    expect(button(element, /^\s*Create\s*$/)).toBeDefined();
  });

  it('previews and moves in the draft the resource was opened in', async () => {
    const invoice = resource('template', 'letters', 'invoice');
    const bodies: unknown[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: string, init?: { body?: string }) => {
        if (init?.body) bodies.push(JSON.parse(init.body));
        const resources = input.includes('/resources');
        return {
          ok: true,
          status: 200,
          json: async () =>
            resources ? { catalogs, resources: [invoice] } : executablePlan(invoice),
        };
      }),
    );
    const element = await mount({
      'data-single': invoice.id,
      'data-draft': 'hotfix-1',
      'data-can-apply': 'true',
    });

    const destination = element.querySelector<HTMLSelectElement>(
      '[data-testid="organise-single-destination"]',
    );
    if (!destination) throw new Error('no destination select');
    destination.value = 'shared';
    destination.dispatchEvent(new Event('change'));
    await settle(element);
    await click(element, /^\s*Preview\s*$/);

    expect(bodies).toEqual([expect.objectContaining({ draft: 'hotfix-1' })]);
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
      pathname: '/tenants/acme/catalogs/acme/letters/templates/invoice/settings',
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
    expect(assign).toHaveBeenCalledWith(
      '/tenants/acme/catalogs/acme/shared/templates/invoice/settings',
    );
  });
});

describe('pathAfterMove', () => {
  const plan = {
    source: {
      type: 'template',
      catalogKey: 'letters',
      catalogAddress: 'acme/letters',
      key: 'invoice',
    },
    target: { type: 'template', catalogKey: 'shared', catalogAddress: 'acme/shared', key: 'bill' },
  };

  it('replaces the catalog address and key segments, keeping the rest of the path', () => {
    expect(
      pathAfterMove('/tenants/acme/catalogs/acme/letters/templates/invoice/settings', plan),
    ).toBe('/tenants/acme/catalogs/acme/shared/templates/bill/settings');
  });

  it('matches only the catalog of the same namespace', () => {
    const path = '/tenants/acme/catalogs/bureau/letters/templates/invoice/settings';
    expect(pathAfterMove(path, plan)).toBe(path);
  });

  it('matches whole segments only', () => {
    const path = '/tenants/acme/catalogs/acme/letters/templates/invoice-2/settings';
    expect(pathAfterMove(path, plan)).toBe(path);
  });
});
