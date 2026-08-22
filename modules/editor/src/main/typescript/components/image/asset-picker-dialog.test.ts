// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { openAssetPickerDialog, type AssetInfo, type CatalogInfo } from './asset-picker-dialog.js';

const catalogs: CatalogInfo[] = [
  { key: 'epistola-demo', name: 'Demo', type: 'AUTHORED' },
  { key: 'system', name: 'System', type: 'SUBSCRIBED' },
];

const demoAssets: AssetInfo[] = [
  {
    id: 'a1',
    name: 'demo-logo',
    mediaType: 'image/png',
    sizeBytes: 1,
    width: 10,
    height: 10,
    catalogKey: 'epistola-demo',
    contentUrl: '/d/a1',
  },
];

const systemAssets: AssetInfo[] = [
  {
    id: 's1',
    name: 'sys-icon',
    mediaType: 'image/png',
    sizeBytes: 1,
    width: 10,
    height: 10,
    catalogKey: 'system',
    contentUrl: '/s/s1',
  },
];

function makeCallbacks() {
  return {
    defaultCatalogKey: 'epistola-demo',
    listCatalogs: vi.fn(async () => catalogs),
    listAssets: vi.fn(async (catalogKey: string) =>
      catalogKey === 'system' ? systemAssets : demoAssets,
    ),
    uploadAsset: vi.fn(async (_file: File, _catalogKey: string) => demoAssets[0]),
  };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

function catalogSelect(): HTMLSelectElement {
  return document.querySelector<HTMLSelectElement>('.asset-picker-catalog-select')!;
}

describe('openAssetPickerDialog catalog selector', () => {
  beforeEach(() => {
    vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(() => {});
    vi.spyOn(HTMLDialogElement.prototype, 'close').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('defaults the catalog selector to the template catalog and lists its assets', async () => {
    const cb = makeCallbacks();
    openAssetPickerDialog(cb);
    await flush();
    await flush();

    const select = catalogSelect();
    expect(select).toBeTruthy();
    expect(select.value).toBe('epistola-demo');
    expect(cb.listAssets).toHaveBeenCalledWith('epistola-demo');
    expect(document.querySelector('[data-asset-id="a1"]')).toBeTruthy();
  });

  it('refetches assets when the catalog selection changes', async () => {
    const cb = makeCallbacks();
    openAssetPickerDialog(cb);
    await flush();
    await flush();

    const select = catalogSelect();
    select.value = 'system';
    select.dispatchEvent(new Event('change'));
    await flush();
    await flush();

    expect(cb.listAssets).toHaveBeenCalledWith('system');
    expect(document.querySelector('[data-asset-id="s1"]')).toBeTruthy();
    expect(document.querySelector('[data-asset-id="a1"]')).toBeNull();
  });

  it('resolves with the selected asset including its catalogKey', async () => {
    const cb = makeCallbacks();
    const result = openAssetPickerDialog(cb);
    await flush();
    await flush();

    const select = catalogSelect();
    select.value = 'system';
    select.dispatchEvent(new Event('change'));
    await flush();
    await flush();

    const card = document.querySelector<HTMLElement>('[data-asset-id="s1"]')!;
    card.dispatchEvent(new MouseEvent('dblclick'));

    const picked = await result;
    expect(picked?.id).toBe('s1');
    expect(picked?.catalogKey).toBe('system');
  });

  it('disables uploads while a subscribed catalog is selected', async () => {
    const cb = makeCallbacks();
    openAssetPickerDialog(cb);
    await flush();
    await flush();

    const select = catalogSelect();
    select.value = 'system';
    select.dispatchEvent(new Event('change'));
    await flush();

    const uploadZone = document.querySelector<HTMLElement>('#asset-picker-upload')!;
    expect(uploadZone.classList.contains('disabled')).toBe(true);
  });

  it('disables uploads when the catalog list fails to load', async () => {
    const cb = makeCallbacks();
    cb.listCatalogs.mockRejectedValueOnce(new Error('boom'));
    openAssetPickerDialog(cb);
    await flush();
    await flush();

    // The default catalog's type is unknown, so uploads stay locked rather
    // than defaulting to enabled. Assets are still loaded for browsing.
    const uploadZone = document.querySelector<HTMLElement>('#asset-picker-upload')!;
    const fileInput = document.querySelector<HTMLInputElement>('#asset-picker-file')!;
    expect(uploadZone.classList.contains('disabled')).toBe(true);
    expect(fileInput.disabled).toBe(true);
    expect(cb.listAssets).toHaveBeenCalledWith('epistola-demo');
  });
});

// Regression coverage for issue #644 (stored XSS): asset/catalog names come from
// raw uploaded filenames and must never be interpolated into innerHTML.
describe('openAssetPickerDialog XSS hardening (#644)', () => {
  const MALICIOUS_ASSET_NAME = '"><img src=x onerror=window.__pwned=1>.png';
  const MALICIOUS_CATALOG_NAME = '"><img src=x onerror=window.__pwned=1>';

  beforeEach(() => {
    vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(() => {});
    vi.spyOn(HTMLDialogElement.prototype, 'close').mockImplementation(() => {});
    (window as unknown as { __pwned?: unknown }).__pwned = undefined;
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('renders a crafted asset name as inert text, never as markup', async () => {
    const cb = makeCallbacks();
    cb.listAssets.mockImplementation(async () => [
      { ...demoAssets[0], id: 'evil-asset', name: MALICIOUS_ASSET_NAME },
    ]);

    openAssetPickerDialog(cb);
    await flush();
    await flush();

    const dialog = document.querySelector('dialog.asset-picker-dialog');
    expect(dialog).not.toBeNull();

    const card = dialog!.querySelector<HTMLElement>('[data-asset-id="evil-asset"]')!;
    expect(card).not.toBeNull();

    const nameEl = card.querySelector<HTMLElement>('.asset-picker-card-name')!;
    expect(nameEl).not.toBeNull();
    expect(nameEl.textContent).toBe(MALICIOUS_ASSET_NAME);
    expect(nameEl.getAttribute('title')).toBe(MALICIOUS_ASSET_NAME);

    // Exactly one <img> (the thumbnail) and no onerror-bearing element anywhere.
    expect(card.querySelectorAll('img').length).toBe(1);
    expect(card.querySelector('[onerror]')).toBeNull();
    expect(dialog!.querySelector('[onerror]')).toBeNull();

    // The malicious markup never actually executed.
    expect((window as unknown as { __pwned?: unknown }).__pwned).toBeUndefined();
  });

  it('renders a crafted catalog name as inert text in the catalog select', async () => {
    const cb = makeCallbacks();
    cb.listCatalogs.mockResolvedValue([
      { key: 'epistola-demo', name: MALICIOUS_CATALOG_NAME, type: 'AUTHORED' },
    ]);

    openAssetPickerDialog(cb);
    await flush();
    await flush();

    const select = catalogSelect();
    const option = select.querySelector('option')!;

    expect(option.textContent).toBe(MALICIOUS_CATALOG_NAME);
    expect(select.querySelector('[onerror]')).toBeNull();
    expect((window as unknown as { __pwned?: unknown }).__pwned).toBeUndefined();
  });
});
