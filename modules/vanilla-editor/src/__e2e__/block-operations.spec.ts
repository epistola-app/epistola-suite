/**
 * Block Operations Tests
 *
 * Tests for adding, deleting, selecting, and moving blocks.
 */

import { test, expect } from "./fixtures.js";
import { BLOCK_TYPES, SELECTORS } from "./config.js";

test.describe("Block Operations - Adding Blocks", () => {
  test("add text block", async ({ page, appHelpers }) => {
    await appHelpers.addTextBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.text);
  });

  test("add container block", async ({ page, appHelpers }) => {
    await appHelpers.addContainerBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.container);
  });

  test("add conditional block", async ({ page, appHelpers }) => {
    await appHelpers.addConditionalBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.conditional);
  });

  test("add loop block", async ({ page, appHelpers }) => {
    await appHelpers.addLoopBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.loop);
  });

  test("add columns block", async ({ page, appHelpers }) => {
    await appHelpers.addColumnsBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.columns);
  });

  test("add table block", async ({ page, appHelpers }) => {
    await appHelpers.addTableBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.table);
  });

  test("add page break block", async ({ page, appHelpers }) => {
    await appHelpers.addPageBreakBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.pagebreak);
  });

  test("add page header block", async ({ page, appHelpers }) => {
    await appHelpers.addPageHeaderBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.pageheader);
  });

  test("add page footer block", async ({ page, appHelpers }) => {
    await appHelpers.addPageFooterBlock();
    await appHelpers.expectBlockVisible(BLOCK_TYPES.pagefooter);
  });

  test("add multiple blocks preserves order", async ({ page, appHelpers }) => {
    const initialCount = await appHelpers.getBlockCount();
    const types = [
      BLOCK_TYPES.text,
      BLOCK_TYPES.container,
      BLOCK_TYPES.conditional,
    ];

    for (const type of types) {
      await appHelpers.addBlock(type);
    }

    await appHelpers.waitForBlockCount(initialCount + types.length);

    const blockTypes = await appHelpers.getBlockTypes();
    const lastTypes = blockTypes.slice(-types.length);
    expect(lastTypes).toEqual(types);
  });

  test.skip("add child block to container", async ({ page, appHelpers }) => {
    await appHelpers.addContainerBlock();
    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    await expect(container.locator(".empty-state")).toContainText(
      "Drop blocks here",
    );

    await appHelpers.addBlockToContainer(container);

    await expect(
      container.locator(`[data-block-type="${BLOCK_TYPES.text}"]`),
    ).toBeVisible();
    await expect(container.locator(".empty-state")).not.toBeVisible();
  });

  test.skip("add child block to conditional", async ({ page, appHelpers }) => {
    await appHelpers.addConditionalBlock();
    const conditional = appHelpers
      .getBlockByType(BLOCK_TYPES.conditional)
      .last();

    await appHelpers.addBlockToContainer(conditional);

    await expect(
      conditional.locator(`[data-block-type="${BLOCK_TYPES.text}"]`),
    ).toBeVisible();
  });

  test.skip("add child block to loop", async ({ page, appHelpers }) => {
    await appHelpers.addLoopBlock();
    const loop = appHelpers.getBlockByType(BLOCK_TYPES.loop).last();

    await appHelpers.addBlockToContainer(loop);

    await expect(
      loop.locator(`[data-block-type="${BLOCK_TYPES.text}"]`),
    ).toBeVisible();
  });
});

test.describe("Block Operations - Deleting Blocks", () => {
  test("delete single block via delete button", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addTextBlock();
    const initialCount = await appHelpers.getBlockCount();
    const textBlock = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await appHelpers.deleteBlock(textBlock);

    await appHelpers.waitForBlockCount(initialCount - 1);
  });

  test.skip("delete container with children removes everything", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addContainerBlock();
    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();

    await appHelpers.addBlockToContainer(container);
    await appHelpers.addBlockToContainer(container);

    const nestedBlocks = container.locator(
      `[data-block-type="${BLOCK_TYPES.text}"]`,
    );
    await expect(nestedBlocks).toHaveCount(2);

    const initialCount = await appHelpers.getBlockCount();

    await appHelpers.deleteBlock(container);

    await appHelpers.waitForBlockCount(initialCount - 3);
  });

  test("delete one block among multiple leaves others intact", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addTextBlock();
    await appHelpers.addTextBlock();
    await appHelpers.addTextBlock();

    const initialCount = await appHelpers.getBlockCount();
    const allTextBlocks = appHelpers.getBlockByType(BLOCK_TYPES.text);

    const targetBlock = allTextBlocks.nth(-2);
    await appHelpers.deleteBlock(targetBlock);

    await appHelpers.waitForBlockCount(initialCount - 1);
  });
});

test.describe("Block Operations - Selecting Blocks", () => {
  test("click block header selects it", async ({ page, appHelpers }) => {
    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await block.locator(SELECTORS.blockHeader).click();

    await expect(block).toHaveClass(/selected/);
  });

  test("selecting another block deselects previous", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addTextBlock();
    await appHelpers.addTextBlock();

    const textBlocks = appHelpers.getBlockByType(BLOCK_TYPES.text);
    const firstBlock = textBlocks.nth(-2);
    const secondBlock = textBlocks.last();

    await firstBlock.locator(SELECTORS.blockHeader).click();
    await expect(firstBlock).toHaveClass(/selected/);
    await expect(secondBlock).not.toHaveClass(/selected/);

    await secondBlock.locator(SELECTORS.blockHeader).click();
    await expect(firstBlock).not.toHaveClass(/selected/);
    await expect(secondBlock).toHaveClass(/selected/);
  });
});

test.describe("Block Operations - Moving Blocks", () => {
  test("text editor rejects native drag-and-drop events", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addTextBlock();
    const textBlock = appHelpers.getBlockByType(BLOCK_TYPES.text).last();
    const editorSurface = textBlock.locator(".text-block-editor").first();

    const dragoverPrevented = await editorSurface.evaluate((el) => {
      const event = new DragEvent("dragover", {
        bubbles: true,
        cancelable: true,
        dataTransfer: new DataTransfer(),
      });
      el.dispatchEvent(event);
      return event.defaultPrevented;
    });

    const dropPrevented = await editorSurface.evaluate((el) => {
      const event = new DragEvent("drop", {
        bubbles: true,
        cancelable: true,
        dataTransfer: new DataTransfer(),
      });
      el.dispatchEvent(event);
      return event.defaultPrevented;
    });

    expect(dragoverPrevented).toBe(true);
    expect(dropPrevented).toBe(true);
  });

  test("dragging a text block into its own editor does not mutate content", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addTextBlock();
    const textBlock = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await appHelpers.typeInTextBlock(textBlock, "SELF_DROP_GUARD");

    const blockId = await textBlock.getAttribute("data-block-id");
    expect(blockId).toBeTruthy();

    const initialBlockCount = await appHelpers.getBlockCount();
    const contentBefore = await appHelpers.getTemplateBlockContent(blockId!);

    await appHelpers.dragBlockHandleToTarget(
      textBlock,
      textBlock.locator(".text-block-editor"),
    );

    const finalBlockCount = await appHelpers.getBlockCount();
    const contentAfter = await appHelpers.getTemplateBlockContent(blockId!);

    expect(finalBlockCount).toBe(initialBlockCount);
    expect(contentAfter).toEqual(contentBefore);

    await expect(
      textBlock.locator(".text-block-editor [data-block-id]"),
    ).toHaveCount(0);
  });

  test("dragging one text block into another editor does not mutate either block", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addTextBlock();
    await appHelpers.addTextBlock();

    const textBlocks = appHelpers.getBlockByType(BLOCK_TYPES.text);
    const sourceBlock = textBlocks.nth(-2);
    const targetBlock = textBlocks.last();

    await appHelpers.typeInTextBlock(sourceBlock, "SOURCE_BLOCK_TEXT");
    await appHelpers.typeInTextBlock(targetBlock, "TARGET_BLOCK_TEXT");

    const sourceId = await sourceBlock.getAttribute("data-block-id");
    const targetId = await targetBlock.getAttribute("data-block-id");
    expect(sourceId).toBeTruthy();
    expect(targetId).toBeTruthy();

    const sourceBefore = await appHelpers.getTemplateBlockContent(sourceId!);
    const targetBefore = await appHelpers.getTemplateBlockContent(targetId!);

    await appHelpers.dragBlockHandleToTarget(
      sourceBlock,
      targetBlock.locator(".text-block-editor"),
    );

    const sourceAfter = await appHelpers.getTemplateBlockContent(sourceId!);
    const targetAfter = await appHelpers.getTemplateBlockContent(targetId!);

    expect(sourceAfter).toEqual(sourceBefore);
    expect(targetAfter).toEqual(targetBefore);

    await expect(
      targetBlock.locator(".text-block-editor [data-block-id]"),
    ).toHaveCount(0);
  });

  test.skip("drag block from root into container", async ({
    page,
    appHelpers,
  }) => {
    // SKIPPED: Playwright's dragTo() is incompatible with SortableJS (HTML5 Drag API vs mouse events).
    // The actual drag-and-drop works in the browser, but can't be tested with Playwright's built-in dragTo().
    // TODO: Implement custom drag simulation using raw mouse events that SortableJS expects.
    await appHelpers.addContainerBlock();
    await appHelpers.addTextBlock();

    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();
    const textBlock = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await appHelpers.dragBlockToContainer(textBlock, container);

    await expect(
      container.locator(`[data-block-type="${BLOCK_TYPES.text}"]`),
    ).toBeVisible();
  });

  test.skip("move block with drag-and-drop", async () => {
    // TODO: Re-enable when we have a reliable UI-level drag simulation for SortableJS.
    // Intentionally no editor API calls in E2E.
  });
});

test.describe("Block Operations - Columns", () => {
  test("add column button increases column count", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addColumnsBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.columns).last();

    const initialCount = await block.locator(".column-wrapper").count();

    await appHelpers.addColumnToColumns(block);

    expect(await block.locator(".column-wrapper").count()).toBe(
      initialCount + 1,
    );
  });

  test("remove column button decreases column count", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addColumnsBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.columns).last();

    await appHelpers.addColumnToColumns(block);
    const initialCount = await block.locator(".column-wrapper").count();

    await appHelpers.removeColumnFromColumns(block, 0);

    expect(await block.locator(".column-wrapper").count()).toBe(
      initialCount - 1,
    );
  });
});

test.describe("Block Operations - Table", () => {
  test("add row button increases row count", async ({ page, appHelpers }) => {
    await appHelpers.addTableBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.table).last();

    const initialRowCount = await block.locator("tr").count();

    await appHelpers.addRowToTable(block);

    expect(await block.locator("tr").count()).toBe(initialRowCount + 1);
  });

  test("remove row button decreases row count", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addTableBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.table).last();

    await appHelpers.addRowToTable(block);
    const initialRowCount = await block.locator("tr").count();

    await appHelpers.removeRowFromTable(block, 0);

    expect(await block.locator("tr").count()).toBe(initialRowCount - 1);
  });

  test("clicking add text in table cell creates a text block", async ({
    page,
    appHelpers,
  }) => {
    await appHelpers.addTableBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.table).last();

    await appHelpers.addTextToTableCell(block, 0);

    const cellBlock = block.locator('[data-cell-id] [data-block-type="text"]');
    await expect(cellBlock.first()).toBeVisible();
  });
});
