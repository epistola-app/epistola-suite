/**
 * Expression Behavior Tests
 *
 * UI-level tests for condition/loop expression editors and inline expression chips.
 * Assertions rely on visible UI behavior only.
 */

import { test, expect } from './fixtures.js';
import { BLOCK_TYPES, SELECTORS } from './config.js';

test.describe('Expression UX - Conditional and Loop Blocks', () => {
  test('conditional expression shows coercion warning and then error for invalid syntax', async ({ appHelpers }) => {
    await appHelpers.addConditionalBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.conditional).last();
    const input = block.locator(SELECTORS.expressionInput).first();

    await input.click();
    await input.fill('"hello"');
    await expect(block.locator(SELECTORS.expressionPreviewWarning)).toContainText('Condition coerces to true');

    await input.fill('name..value');
    await expect(block.locator(SELECTORS.expressionPreviewError)).toContainText('Error:');
  });

  test('loop expression warns for non-array and clears warning for array literal', async ({ appHelpers }) => {
    await appHelpers.addLoopBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.loop).last();
    const input = block.locator(SELECTORS.expressionInput).first();

    await input.click();
    await input.fill('"hello"');
    await expect(block.locator(SELECTORS.expressionPreviewWarning)).toContainText('Expected an array for loop expression');

    await input.fill('[1,2,3]');
    await expect(block.locator(SELECTORS.expressionPreviewWarning)).toHaveCount(0);
    await expect(block.locator(SELECTORS.expressionPreview).first()).toContainText('Preview:');
  });
});

test.describe('Expression UX - Inline Chips', () => {
  test('typing bare double braces opens chip popover and cancel removes new chip', async ({ appHelpers, page }) => {
    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await appHelpers.typeInTextBlock(block, '{{');

    const popover = page.locator(SELECTORS.expressionChipPopover);
    await expect(popover).toBeVisible();
    await expect(page.locator(SELECTORS.expressionChipPopoverInput)).toHaveValue('');

    await page.locator(SELECTORS.expressionChipPopoverCancel).click();
    await expect(block.locator(SELECTORS.expressionChip)).toHaveCount(0);
  });

  test('new chip save keeps chip and supports click-to-edit update', async ({ appHelpers, page }) => {
    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await appHelpers.typeInTextBlock(block, '{{');
    const popoverInput = page.locator(SELECTORS.expressionChipPopoverInput);
    await expect(popoverInput).toBeVisible();

    await popoverInput.fill('"HELLO"');
    await expect(page.locator(SELECTORS.expressionChipPopoverPreview)).toContainText('HELLO');
    await page.locator(SELECTORS.expressionChipPopoverSave).click();

    const chip = block.locator(SELECTORS.expressionChip).first();
    await expect(chip).toBeVisible();
    await expect(chip.locator(SELECTORS.expressionChipExpr)).toContainText('"HELLO"');

    await appHelpers.openExpressionChipPopover(chip);
    await expect(popoverInput).toHaveValue('"HELLO"');
    await popoverInput.fill('"UPDATED"');
    await page.locator(SELECTORS.expressionChipPopoverSave).click();

    await expect(block.locator(SELECTORS.expressionChipExpr).first()).toContainText('"UPDATED"');
  });

  test('chip popover shows error preview for invalid expression', async ({ appHelpers, page }) => {
    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();

    await appHelpers.typeInTextBlock(block, '{{');
    const popoverInput = page.locator(SELECTORS.expressionChipPopoverInput);
    await expect(popoverInput).toBeVisible();

    await popoverInput.fill('name..value');
    await expect(page.locator(SELECTORS.expressionChipPopoverPreview)).toHaveClass(/error/);
    await expect(page.locator(SELECTORS.expressionChipPopoverPreview)).not.toContainText('Evaluating...');
    await expect(page.locator(SELECTORS.expressionChipPopoverPreview)).not.toHaveText('');

    await page.locator(SELECTORS.expressionChipPopoverCancel).click();
    await expect(block.locator(SELECTORS.expressionChip)).toHaveCount(0);
  });

});
