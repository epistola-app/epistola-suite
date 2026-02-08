/**
 * Block Rendering Tests
 *
 * Tests for block rendering and visual structure.
 */

import { test, expect } from './fixtures.js';
import { BLOCK_TYPES, SELECTORS } from './config.js';

test.describe('Block Rendering - Text Blocks', () => {
  test('text block renders with ProseMirror editor', async ({ page, appHelpers }) => {
    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.text);

    const editorArea = block.locator('.ProseMirror');
    await expect(editorArea).toBeVisible();
  });

  test('text block inside container renders correctly', async ({ page, appHelpers }) => {
    await appHelpers.addContainerBlock();
    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    await appHelpers.addBlockToContainer(container);

    const nestedText = container.locator(`[data-block-type="${BLOCK_TYPES.text}"]`);
    await expect(nestedText).toBeVisible();
    await expect(nestedText.locator('.ProseMirror')).toBeVisible();
  });
});

test.describe('Block Rendering - Container Blocks', () => {
  test('empty container shows placeholder', async ({ page, appHelpers }) => {
    await appHelpers.addContainerBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.container);

    const dropZone = block.locator(SELECTORS.sortableContainer);
    await expect(dropZone).toBeVisible();
    await expect(block.locator(SELECTORS.emptyState)).toContainText('Drop blocks here');
  });

  test('container with children renders all nested blocks', async ({ page, appHelpers }) => {
    await appHelpers.addContainerBlock();
    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    await appHelpers.addBlockToContainer(container);
    await appHelpers.addBlockToContainer(container);

    const textBlocks = container.locator(`[data-block-type="${BLOCK_TYPES.text}"]`);
    await expect(textBlocks).toHaveCount(2);
  });

  test('container with nested blocks renders correctly', async ({ page, appHelpers }) => {
    // Test that addBlockToContainer works correctly after the production bug fix
    await appHelpers.addContainerBlock();
    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    // Add two text blocks to the container
    await appHelpers.addBlockToContainer(container);
    await appHelpers.addBlockToContainer(container);

    // Verify both nested blocks are visible
    const nestedBlocks = container.locator(`[data-block-type="${BLOCK_TYPES.text}"]`);
    await expect(nestedBlocks).toHaveCount(2);
  });

  test('container has drag handle and badge', async ({ page, appHelpers }) => {
    await appHelpers.addContainerBlock();
    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    const dragHandle = container.locator(SELECTORS.dragHandle);
    await expect(dragHandle).toBeAttached();

    const badge = container.locator(`${SELECTORS.blockHeader} .badge`);
    await expect(badge).toContainText(BLOCK_TYPES.container);
  });
});

test.describe('Block Rendering - Conditional Blocks', () => {
  test('conditional block renders expression input', async ({ page, appHelpers }) => {
    await appHelpers.addConditionalBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.conditional).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.conditional);

    const input = block.locator(SELECTORS.expressionInput);
    await expect(input).toBeVisible();
  });

  test('conditional block empty children shows placeholder', async ({ page, appHelpers }) => {
    await appHelpers.addConditionalBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.conditional).last();

    await expect(block.locator(SELECTORS.emptyState)).toContainText('Content when condition is true');
  });

  test('conditional block with children renders nested content', async ({ page, appHelpers }) => {
    await appHelpers.addConditionalBlock();
    const conditional = appHelpers.getBlockByType(BLOCK_TYPES.conditional).last();

    await appHelpers.addBlockToContainer(conditional);

    const nestedText = conditional.locator(`[data-block-type="${BLOCK_TYPES.text}"]`);
    await expect(nestedText).toBeVisible();
    await expect(conditional.locator(SELECTORS.emptyState)).not.toBeVisible();
  });
});

test.describe('Block Rendering - Loop Blocks', () => {
  test('loop block renders expression input and alias fields', async ({ page, appHelpers }) => {
    await appHelpers.addLoopBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.loop).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.loop);

    await expect(block.locator(SELECTORS.expressionInput)).toBeVisible();

    const inputs = block.locator('.input-group input');
    await expect(inputs).toHaveCount(2);
  });

  test('loop block empty children shows placeholder', async ({ page, appHelpers }) => {
    await appHelpers.addLoopBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.loop).last();

    await expect(block.locator(SELECTORS.emptyState)).toContainText('Loop body');
  });
});

test.describe('Block Rendering - Columns Blocks', () => {
  test('columns block renders correct number of columns', async ({ page, appHelpers }) => {
    await appHelpers.addColumnsBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.columns).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.columns);
    await expect(block.locator(SELECTORS.columnWrapper)).toHaveCount(2);
  });

  test('empty columns show placeholder in each column', async ({ page, appHelpers }) => {
    await appHelpers.addColumnsBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.columns).last();

    const placeholders = block.locator(`${SELECTORS.columnWrapper} ${SELECTORS.emptyState}`);
    await expect(placeholders).toHaveCount(2);
  });
});

test.describe('Block Rendering - Table Blocks', () => {
  test('table block renders correct row and column counts', async ({ page, appHelpers }) => {
    await appHelpers.addTableBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.table).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.table);

    await expect(block.locator('tr')).toHaveCount(3);
    await expect(block.locator('tr:first-child th[data-cell-id]')).toHaveCount(3);
  });

  test('empty table cells show add text placeholder', async ({ page, appHelpers }) => {
    await appHelpers.addTableBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.table).last();

    const addTextLinks = block.locator(SELECTORS.addTextLink);
    await expect(addTextLinks.first()).toBeVisible();
  });

  test('each table row has a remove button', async ({ page, appHelpers }) => {
    await appHelpers.addTableBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.table).last();

    const removeButtons = block.locator('button[title="Remove row"]');
    await expect(removeButtons).toHaveCount(3);
  });
});

test.describe('Block Rendering - Page Blocks', () => {
  test('page header renders info banner and empty placeholder', async ({ page, appHelpers }) => {
    await appHelpers.addPageHeaderBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.pageheader).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.pageheader);
    await expect(block.locator('.alert-info')).toContainText('top of every page');
    await expect(block.locator(SELECTORS.emptyState)).toContainText('Add header content');
  });

  test('page footer renders info banner and empty placeholder', async ({ page, appHelpers }) => {
    await appHelpers.addPageFooterBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.pagefooter).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.pagefooter);
    await expect(block.locator('.alert-info')).toContainText('bottom of every page');
    await expect(block.locator(SELECTORS.emptyState)).toContainText('Add footer content');
  });

  test('pagebreak renders dashed separator with label', async ({ page, appHelpers }) => {
    await appHelpers.addPageBreakBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.pagebreak).last();

    await expect(block).toHaveAttribute('data-block-type', BLOCK_TYPES.pagebreak);
    await expect(block.locator('text=Page Break')).toBeVisible();
  });
});

test.describe('Block Rendering - Block Headers', () => {
  test('each block has header with drag handle, badge, and delete button', async ({ page, appHelpers }) => {
    await appHelpers.addTextBlock();
    await appHelpers.addContainerBlock();

    const textBlock = appHelpers.getBlockByType(BLOCK_TYPES.text).last();
    const containerBlock = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    for (const block of [textBlock, containerBlock]) {
      const header = block.locator(SELECTORS.blockHeader);
      await expect(header).toBeVisible();

      await expect(header.locator(SELECTORS.dragHandle)).toBeAttached();
      await expect(header.locator('.badge')).toBeVisible();
      await expect(block.locator(SELECTORS.deleteButton)).toBeVisible();
    }
  });

  test('badge text matches block type', async ({ page, appHelpers }) => {
    await appHelpers.addTextBlock();
    await appHelpers.addContainerBlock();
    await appHelpers.addLoopBlock();

    await expect(
      appHelpers.getBlockByType(BLOCK_TYPES.text).last().locator(`${SELECTORS.blockHeader} .badge`)
    ).toContainText(BLOCK_TYPES.text);
    await expect(
      appHelpers.getBlockByType(BLOCK_TYPES.container).last().locator(`${SELECTORS.blockHeader} .badge`)
    ).toContainText(BLOCK_TYPES.container);
    await expect(
      appHelpers.getBlockByType(BLOCK_TYPES.loop).last().locator(`${SELECTORS.blockHeader} .badge`)
    ).toContainText(BLOCK_TYPES.loop);
  });
});
