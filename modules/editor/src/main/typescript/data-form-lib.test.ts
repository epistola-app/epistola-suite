// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { pointerToFormPath } from './data-form-lib.js';

describe('pointerToFormPath', () => {
  it('turns a JSON Pointer into the example form path', () => {
    expect(pointerToFormPath('/citizen/address/street')).toBe('citizen.address.street');
    expect(pointerToFormPath('/lines/0/amount')).toBe('lines.0.amount');
  });

  it('undoes the RFC 6901 escapes', () => {
    expect(pointerToFormPath('/a~1b/c~0d')).toBe('a/b.c~d');
  });
});
