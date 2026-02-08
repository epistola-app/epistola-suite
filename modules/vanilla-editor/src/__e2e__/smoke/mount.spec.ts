import { test, expect, Page } from '@playwright/test';

const TEST_PAGE_URL = new URL('../setup/test-page.html', import.meta.url).href;
const EDITOR_TIMEOUT = 5000;

type TemplateFactory = (blocks?: unknown[]) => unknown;

const createTemplate: TemplateFactory = (blocks = []) => ({
  id: `test-${Math.random().toString(36).substring(7)}`,
  name: 'Test Template',
  blocks,
  styles: {},
  pageSettings: {
    format: 'A4',
    orientation: 'portrait',
    margins: { top: 20, right: 20, bottom: 20, left: 20 }
  }
});

const createTextBlock = (content: string) => ({
  id: `block-${Math.random().toString(36).substring(7)}`,
  type: 'text',
  content: {
    type: 'doc',
    content: [{
      type: 'paragraph',
      content: [{ type: 'text', text: content }]
    }]
  },
  styles: {},
  children: []
});

async function getEditorState(page: Page): Promise<unknown> {
  return page.evaluate(() => {
    if (window.testEditor) {
      return {
        template: window.testEditor.getTemplate(),
        state: window.testEditor.getEditor()?.getState(),
      };
    }
    return null;
  });
}

async function logErrorContext(page: Page, testName: string): Promise<void> {
  const state = await getEditorState(page);
  const html = await page.content();
  console.error(`\n=== Test Failed: ${testName} ===`);
  console.error('Editor State:', JSON.stringify(state, null, 2));
  console.error('Page HTML (first 1000 chars):', html.substring(0, 1000));
  console.error('=== End Error Context ===\n');
}

test.describe('Smoke Tests', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(TEST_PAGE_URL);
    await page.waitForFunction(() => window.editorModuleLoaded === true);

    const testId = Math.random().toString(36).substring(7);
    await page.evaluate((id) => {
      const container = document.createElement('div');
      container.id = `editor-root-${id}`;
      document.body.appendChild(container);
      window.currentTestContainerId = id;
    }, testId);
  });

  test.afterEach(async ({ page }) => {
    await page.evaluate(() => {
      const containerId = window.currentTestContainerId;
      if (containerId) {
        const container = document.getElementById(`editor-root-${containerId}`);
        if (container) {
          container.remove();
        }
      }
    });
  });

  test('editor mounts successfully and shows empty state', async ({ page }, testInfo) => {
    const containerId = await page.evaluate(() => window.currentTestContainerId);
    const editorRoot = page.locator(`#editor-root-${containerId}`);

    try {
      await page.evaluate((template) => {
        const id = window.currentTestContainerId;
        window.testEditor = window.mountEditor({
          container: `#editor-root-${id}`,
          template: template,
          save: {
            handler: async () => {
              console.log('Save called');
            },
          },
        });
      }, createTemplate());

      await page.waitForSelector(`#editor-root-${containerId} [data-testid="empty-state"]`, {
        timeout: EDITOR_TIMEOUT,
      });

      const emptyState = editorRoot.getByTestId('empty-state');
      await expect(emptyState).toBeVisible();
      await expect(emptyState).toContainText('No blocks yet');
      await expect(editorRoot).not.toBeEmpty();
    } catch (error) {
      await logErrorContext(page, testInfo.title);
      throw error;
    }
  });

  test('editor renders with existing blocks', async ({ page }, testInfo) => {
    const containerId = await page.evaluate(() => window.currentTestContainerId);
    const editorRoot = page.locator(`#editor-root-${containerId}`);

    try {
      await page.evaluate((template) => {
        const id = window.currentTestContainerId;
        window.testEditor = window.mountEditor({
          container: `#editor-root-${id}`,
          template: template,
          save: {
            handler: async () => {},
          },
        });
      }, createTemplate([createTextBlock('Hello World')]));

      await page.waitForSelector(`#editor-root-${containerId} [data-testid="block"]`, {
        timeout: EDITOR_TIMEOUT,
      });

      const textBlock = editorRoot.getByTestId('block');
      await expect(textBlock).toBeVisible();
      await expect(textBlock).toHaveAttribute('data-block-type', 'text');

      const blockHeader = textBlock.getByTestId('block-header');
      await expect(blockHeader).toBeVisible();

      const deleteButton = textBlock.getByTestId('block-delete');
      await expect(deleteButton).toBeVisible();
    } catch (error) {
      await logErrorContext(page, testInfo.title);
      throw error;
    }
  });

  test('editor cleanup works correctly', async ({ page }, testInfo) => {
    const containerId = await page.evaluate(() => window.currentTestContainerId);
    const editorRoot = page.locator(`#editor-root-${containerId}`);

    try {
      await page.evaluate((template) => {
        const id = window.currentTestContainerId;
        window.testEditor = window.mountEditor({
          container: `#editor-root-${id}`,
          template: template,
          save: {
            handler: async () => {},
          },
        });
      }, createTemplate([createTextBlock('Test content')]));

      await page.waitForSelector(`#editor-root-${containerId} [data-testid="block"]`, {
        timeout: EDITOR_TIMEOUT,
      });

      await expect(editorRoot.getByTestId('block')).toBeVisible();

      await page.evaluate(() => {
        if (window.testEditor) {
          window.testEditor.destroy();
          (window as unknown as Record<string, boolean>).editorDestroyed = true;
        }
      });

      await page.waitForTimeout(100);

      const hasBlocks = await editorRoot.getByTestId('block').isVisible().catch(() => false);
      expect(hasBlocks).toBe(false);

      const destroyCalled = await page.evaluate(() =>
        (window as unknown as Record<string, boolean>).editorDestroyed ?? false
      );
      expect(destroyCalled).toBe(true);

      const html = await editorRoot.innerHTML();
      expect(
        html?.trim() === '' ||
        html?.includes('data-destroyed') ||
        html?.includes('editor-destroyed')
      ).toBe(true);
    } catch (error) {
      await logErrorContext(page, testInfo.title);
      throw error;
    }
  });
});
