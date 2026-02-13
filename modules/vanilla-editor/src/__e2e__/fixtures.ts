/**
 * Playwright Test Fixtures
 *
 * Custom fixtures for E2E tests using the editor application.
 * Uses config.ts for all configuration values.
 */

import { test as base, Page } from '@playwright/test';
import { AUTH, ROUTES, TIMEOUTS } from './config.js';
import { AppHelpers } from './helpers.js';

/**
 * E2E Test Fixtures
 */
export interface TestFixtures {
  page: Page;
  appHelpers: AppHelpers;
}

export const test = base.extend<TestFixtures>({
  /**
   * Authenticated page with storage state
   * Uses global storage state to avoid repeated logins
   */
  page: async ({ context }, use) => {
    await context.addCookies([
      { name: 'JSESSIONID', value: '', url: AUTH.baseUrl },
    ]);

    const page = await context.newPage();

    await page.goto(`${AUTH.baseUrl}${ROUTES.editor}`, {
      waitUntil: 'load',
    });

    try {
      await page.waitForSelector('#editor-root', { timeout: TIMEOUTS.veryLong });
      await page.waitForSelector('[data-block-id]', { timeout: TIMEOUTS.long });
    } catch {
      throw new Error('Editor did not load properly. Check if the app is running at localhost:4000');
    }

    await use(page);

    await page.close();
  },

  /**
   * AppHelpers instance for common operations
   */
  appHelpers: async ({ page }, use) => {
    const helpers = new AppHelpers(page);
    await use(helpers);
  },
});

export { expect } from '@playwright/test';
