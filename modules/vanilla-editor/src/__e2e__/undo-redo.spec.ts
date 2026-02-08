/**
 * Undo / Redo Tests
 *
 * Tests for undo and redo functionality.
 */

import { test, expect } from './fixtures.js';
import { BLOCK_TYPES } from './config.js';

test.describe('Undo / Redo - Basic Operations', () => {
  test('undo add block removes it', async ({ page, appHelpers }) => {
    const initialCount = await appHelpers.getBlockCount();

    await appHelpers.addBlock(BLOCK_TYPES.text);
    await appHelpers.waitForBlockCount(initialCount + 1);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount);
  });

  test('undo delete block restores it', async ({ page, appHelpers }) => {
    await appHelpers.addBlock(BLOCK_TYPES.text);
    const initialCount = await appHelpers.getBlockCount();
    const textBlock = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await appHelpers.deleteBlock(textBlock);
    await appHelpers.waitForBlockCount(initialCount - 1);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount);
  });

  test('undo delete restores container with children intact', async ({ page, appHelpers }) => {
    await appHelpers.addContainerBlock();
    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    await appHelpers.addBlockToContainer(container);
    await appHelpers.addBlockToContainer(container);

    const children = container.locator(`[data-block-type="${BLOCK_TYPES.text}"]`);
    await expect(children).toHaveCount(2);

    const initialCount = await appHelpers.getBlockCount();

    await appHelpers.deleteBlock(container);
    await appHelpers.waitForBlockCount(initialCount - 3);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount);
  });

  test('undo add column removes it', async ({ page, appHelpers }) => {
    await appHelpers.addColumnsBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.columns).last();

    const initialColCount = await block.locator('.column-wrapper').count();

    await appHelpers.addColumnToColumns(block);

    expect(await block.locator('.column-wrapper').count()).toBe(initialColCount + 1);

    await appHelpers.undo();

    expect(await block.locator('.column-wrapper').count()).toBe(initialColCount);
  });

  test('undo remove column restores it', async ({ page, appHelpers }) => {
    await appHelpers.addColumnsBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.columns).last();

    await appHelpers.addColumnToColumns(block);
    const initialColCount = await block.locator('.column-wrapper').count();

    await appHelpers.removeColumnFromColumns(block, 0);

    expect(await block.locator('.column-wrapper').count()).toBe(initialColCount - 1);

    await appHelpers.undo();

    expect(await block.locator('.column-wrapper').count()).toBe(initialColCount);
  });

  test('undo add row removes it', async ({ page, appHelpers }) => {
    await appHelpers.addTableBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.table).last();

    const initialRowCount = await block.locator('tr').count();

    await appHelpers.addRowToTable(block);

    expect(await block.locator('tr').count()).toBe(initialRowCount + 1);

    await appHelpers.undo();

    expect(await block.locator('tr').count()).toBe(initialRowCount);
  });

  test('undo remove row restores it', async ({ page, appHelpers }) => {
    await appHelpers.addTableBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.table).last();

    await appHelpers.addRowToTable(block);
    const initialRowCount = await block.locator('tr').count();

    await appHelpers.removeRowFromTable(block, 0);

    expect(await block.locator('tr').count()).toBe(initialRowCount - 1);

    await appHelpers.undo();

    expect(await block.locator('tr').count()).toBe(initialRowCount);
  });

  test('multiple undos walk back through history', async ({ page, appHelpers }) => {
    const initialCount = await appHelpers.getBlockCount();

    await appHelpers.addBlock(BLOCK_TYPES.text);
    await appHelpers.addBlock(BLOCK_TYPES.container);
    await appHelpers.addBlock(BLOCK_TYPES.conditional);

    await appHelpers.waitForBlockCount(initialCount + 3);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount + 2);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount + 1);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount);
  });
});

test.describe('Undo / Redo - Redo Operations', () => {
  test('redo after undo restores the block', async ({ page, appHelpers }) => {
    const initialCount = await appHelpers.getBlockCount();

    await appHelpers.addBlock(BLOCK_TYPES.text);
    await appHelpers.waitForBlockCount(initialCount + 1);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount);

    await appHelpers.redo();
    await appHelpers.waitForBlockCount(initialCount + 1);

    await expect(appHelpers.getBlockByType(BLOCK_TYPES.text).last()).toBeVisible();
  });

  test('redo delete after undo delete removes block again', async ({ page, appHelpers }) => {
    await appHelpers.addBlock(BLOCK_TYPES.text);
    const initialCount = await appHelpers.getBlockCount();
    const textBlock = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await appHelpers.deleteBlock(textBlock);
    await appHelpers.waitForBlockCount(initialCount - 1);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount);

    await appHelpers.redo();
    await appHelpers.waitForBlockCount(initialCount - 1);
  });

  test('multiple redos walk forward through history', async ({ page, appHelpers }) => {
    const initialCount = await appHelpers.getBlockCount();

    await appHelpers.addBlock(BLOCK_TYPES.text);
    await appHelpers.addBlock(BLOCK_TYPES.container);

    await appHelpers.waitForBlockCount(initialCount + 2);

    await appHelpers.undo();
    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount);

    await appHelpers.redo();
    await appHelpers.waitForBlockCount(initialCount + 1);

    await appHelpers.redo();
    await appHelpers.waitForBlockCount(initialCount + 2);
  });
});

test.describe('Undo / Redo - History Management', () => {
  test('new action after undo clears redo history', async ({ page, appHelpers }) => {
    const initialCount = await appHelpers.getBlockCount();

    await appHelpers.addBlock(BLOCK_TYPES.text);
    await appHelpers.waitForBlockCount(initialCount + 1);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount);

    await appHelpers.addBlock(BLOCK_TYPES.container);
    await appHelpers.waitForBlockCount(initialCount + 1);

    const lastBlock = appHelpers.getBlockByType(BLOCK_TYPES.container).last();
    await expect(lastBlock).toBeVisible();
  });
});

test.describe('Undo / Redo - Complex Scenarios', () => {
  test('history survives complex add-delete-undo-redo sequence', async ({ page, appHelpers }) => {
    const initialCount = await appHelpers.getBlockCount();

    await appHelpers.addBlock(BLOCK_TYPES.text);
    await appHelpers.addBlock(BLOCK_TYPES.container);
    await appHelpers.addBlock(BLOCK_TYPES.conditional);

    await appHelpers.waitForBlockCount(initialCount + 3);

    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();
    await appHelpers.deleteBlock(container);
    await appHelpers.waitForBlockCount(initialCount + 2);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount + 3);

    await appHelpers.undo();
    await appHelpers.waitForBlockCount(initialCount + 2);

    await appHelpers.redo();
    await appHelpers.waitForBlockCount(initialCount + 3);
  });

  test('rapid undo operations work correctly', async ({ page, appHelpers }) => {
    const initialCount = await appHelpers.getBlockCount();

    for (let i = 0; i < 5; i++) {
      await appHelpers.addBlock(BLOCK_TYPES.text);
    }

    await appHelpers.waitForBlockCount(initialCount + 5);

    for (let i = 0; i < 5; i++) {
      await appHelpers.undo();
    }

    await appHelpers.waitForBlockCount(initialCount);

    for (let i = 0; i < 5; i++) {
      await appHelpers.redo();
    }

    await appHelpers.waitForBlockCount(initialCount + 5);
  });
});
