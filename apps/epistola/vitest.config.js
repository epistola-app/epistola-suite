// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'happy-dom',
    // Static-JS unit tests only — keep vitest away from Gradle's build/ output,
    // which contains copies of the production JS.
    include: ['src/test/js/**/*.test.js'],
  },
});
