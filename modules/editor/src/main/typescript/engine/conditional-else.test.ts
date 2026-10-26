// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * A conditional has an `else` slot beside its body (contract `2.0.0-alpha.3`): a new conditional is
 * created with both, so an author can fill what renders when the condition does not hold.
 */

import { describe, it, expect } from 'vitest';
import { createDefaultRegistry } from './registry.js';

describe('conditional else slot', () => {
  it('declares a body and an else slot', () => {
    const def = createDefaultRegistry().getOrThrow('conditional');
    expect(def.slots.map((slot) => slot.name)).toEqual(['body', 'else']);
  });

  it('creates a new conditional with both slots, the body first', () => {
    const { node, slots } = createDefaultRegistry().createNode('conditional');
    expect(slots.map((slot) => slot.name)).toEqual(['body', 'else']);
    expect(node.slots).toEqual(slots.map((slot) => slot.id));
  });
});
