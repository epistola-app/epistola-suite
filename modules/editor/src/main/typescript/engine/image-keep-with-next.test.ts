// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * An image offers "Keep with next" (contract `2.0.0-alpha.3`, #104), so a logo or chart stays on the
 * page of the block that follows it. The renderer already applies the style to every block.
 */

import { describe, it, expect } from 'vitest';
import { createDefaultRegistry } from './registry.js';

describe('image keepWithNext', () => {
  it('is among the styles an image applies', () => {
    expect(createDefaultRegistry().getOrThrow('image').applicableStyles).toContain('keepWithNext');
  });
});
