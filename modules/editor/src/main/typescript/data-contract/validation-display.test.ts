// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { countDistinctErrorPaths } from './validation-display.js';

describe('countDistinctErrorPaths', () => {
  it('counts one issue when two errors land on the same field path', () => {
    // e.g. an array field with a contradictory minItems > maxItems schema
    // can report both violations for the same value at the same path.
    const errors = [
      { path: '$.items', message: 'must have at least 5 items' },
      { path: '$.items', message: 'must have at most 2 items' },
    ];

    expect(countDistinctErrorPaths(errors)).toBe(1);
  });

  it('counts each distinct path once', () => {
    const errors = [
      { path: '$.a', message: 'is required' },
      { path: '$.b', message: 'is required' },
    ];

    expect(countDistinctErrorPaths(errors)).toBe(2);
  });
});
