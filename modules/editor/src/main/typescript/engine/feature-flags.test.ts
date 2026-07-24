// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { EditorEngine } from './EditorEngine.js';
import { createTestDocument, testRegistry } from './test-helpers.js';

describe('EditorEngine feature state', () => {
  it('reads enablement from feature configs', () => {
    const engine = new EditorEngine(createTestDocument(), testRegistry(), {
      features: {
        quality: { enabled: true, badge: { label: 'Alpha', className: 'badge-alpha' } },
        aiChat: { enabled: false, badge: { label: 'Alpha', className: 'badge-alpha' } },
      },
    });

    expect(engine.isFeatureEnabled('quality')).toBe(true);
    expect(engine.isFeatureEnabled('aiChat')).toBe(false);
    expect(engine.features.quality?.badge).toEqual({ label: 'Alpha', className: 'badge-alpha' });
  });
});
