// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, it, expect, vi } from 'vitest';
import { isReleasedVersionProblem, nextDraftLocation, offerNextDraft } from './released-version.js';

describe('released version', () => {
  it('recognises the released-version problem, and nothing else', () => {
    expect(
      isReleasedVersionProblem(409, 'https://epistola.app/errors/catalog-version-released'),
    ).toBe(true);
    expect(isReleasedVersionProblem(409, 'https://epistola.app/errors/conflict')).toBe(false);
    expect(
      isReleasedVersionProblem(412, 'https://epistola.app/errors/catalog-version-released'),
    ).toBe(false);
    expect(isReleasedVersionProblem(409, null)).toBe(false);
  });

  it('drops the version from the catalog address, keeping the rest of the page', () => {
    expect(
      nextDraftLocation({
        pathname: '/tenants/t/catalogs/acme/letters@2.0.0/themes/corporate',
        search: '?tab=styles',
      }),
    ).toBe('/tenants/t/catalogs/acme/letters/themes/corporate?tab=styles');
    expect(
      nextDraftLocation({
        pathname: '/tenants/t/catalogs/letters@1.2.0/templates/invoice',
        search: '',
      }),
    ).toBe('/tenants/t/catalogs/letters/templates/invoice');
    expect(
      nextDraftLocation({ pathname: '/tenants/t/catalogs/letters/templates/invoice', search: '' }),
    ).toBe('/tenants/t/catalogs/letters/templates/invoice');
  });

  it('opens the next draft only when the author agrees', () => {
    const assign = vi.fn();
    const win = (answer: boolean) => ({
      confirm: vi.fn(() => answer),
      location: {
        pathname: '/tenants/t/catalogs/letters@1.2.0/themes/corporate',
        search: '',
        assign,
      },
    });

    expect(offerNextDraft('Released as 1.2.0.', win(false))).toBe(false);
    expect(assign).not.toHaveBeenCalled();

    expect(offerNextDraft('Released as 1.2.0.', win(true))).toBe(true);
    expect(assign).toHaveBeenCalledWith('/tenants/t/catalogs/letters/themes/corporate');
  });
});
