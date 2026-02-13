/**
 * Global Setup - Authentication
 *
 * Logs in once and saves storage state to .auth/storage-state.json
 * This allows all tests to skip the login step.
 */

import { chromium } from '@playwright/test';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const STORAGE_STATE_PATH = path.resolve(__dirname, '../../.auth/storage-state.json');

async function globalSetup() {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();

  try {
    await page.goto('http://localhost:4000/login');
    await page.fill('#username', 'admin@local');
    await page.fill('#password', 'admin');
    await page.click('button[type="submit"]');

    await page.waitForURL('http://localhost:4000/**', { timeout: 10000 });

    if (page.url().includes('/login')) {
      throw new Error('Login failed - still on login page');
    }

    await context.storageState({ path: STORAGE_STATE_PATH });
    console.log('Storage state saved to:', STORAGE_STATE_PATH);
  } finally {
    await browser.close();
  }
}

export default globalSetup;
