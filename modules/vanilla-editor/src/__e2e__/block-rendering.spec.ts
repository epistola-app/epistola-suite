/**
 * Block Rendering Tests
 *
 * Tests for block rendering and visual structure.
 */

import { test, expect } from './fixtures.js';
import { BLOCK_TYPES, SELECTORS } from './config.js';

test.describe('Document Styles - UI', () => {
  test('document styles modal roundtrip preserves values and units', async ({ page }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();

    await page.fill('#doc-font-family', 'Georgia, serif');
    await page.fill('#doc-font-size-value', '18');
    await page.selectOption('#doc-font-size-unit', 'px');
    await page.selectOption('#doc-font-weight', '600');
    await page.fill('#doc-color', '#123456');
    await page.fill('#doc-line-height', '1.7');
    await page.fill('#doc-letter-spacing-value', '0.5');
    await page.selectOption('#doc-letter-spacing-unit', 'em');
    await page.selectOption('#doc-text-align', 'right');
    await page.fill('#doc-background-color', '#f0f0f0');

    await page.click('#btn-save-document-styles');

    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();

    await expect(page.locator('#doc-font-family')).toHaveValue('Georgia, serif');
    await expect(page.locator('#doc-font-size-value')).toHaveValue('18');
    await expect(page.locator('#doc-font-size-unit')).toHaveValue('px');
    await expect(page.locator('#doc-font-weight')).toHaveValue('600');
    await expect(page.locator('#doc-color')).toHaveValue('#123456');
    await expect(page.locator('#doc-line-height')).toHaveValue('1.7');
    await expect(page.locator('#doc-letter-spacing-value')).toHaveValue('0.5');
    await expect(page.locator('#doc-letter-spacing-unit')).toHaveValue('em');
    await expect(page.locator('#doc-text-align')).toHaveValue('right');
    await expect(page.locator('#doc-background-color')).toHaveValue('#f0f0f0');
  });

  test('document styles modal applies font size and color to newly added text block', async ({ page, appHelpers }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();

    await page.fill('#doc-font-size-value', '18');
    await page.selectOption('#doc-font-size-unit', 'px');
    await page.fill('#doc-color', '#123456');
    await page.click('#btn-save-document-styles');

    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();
    const textEditor = block.locator('.text-block-editor');

    await expect(textEditor).toBeVisible();

    const computed = await textEditor.evaluate((el) => {
      const styles = window.getComputedStyle(el as HTMLElement);
      return {
        fontSize: styles.fontSize,
        color: styles.color,
      };
    });

    expect(computed.fontSize).toBe('18px');
    expect(computed.color).toBe('rgb(18, 52, 86)');
  });

  test('document styles modal preserves font and letter spacing units across save/reopen', async ({ page }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();

    await page.fill('#doc-font-size-value', '120');
    await page.selectOption('#doc-font-size-unit', '%');
    await page.fill('#doc-letter-spacing-value', '2');
    await page.selectOption('#doc-letter-spacing-unit', 'px');
    await page.click('#btn-save-document-styles');

    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();
    await expect(page.locator('#doc-font-size-value')).toHaveValue('120');
    await expect(page.locator('#doc-font-size-unit')).toHaveValue('%');
    await expect(page.locator('#doc-letter-spacing-value')).toHaveValue('2');
    await expect(page.locator('#doc-letter-spacing-unit')).toHaveValue('px');

    await page.fill('#doc-font-size-value', '11');
    await page.selectOption('#doc-font-size-unit', 'pt');
    await page.fill('#doc-letter-spacing-value', '0.2');
    await page.selectOption('#doc-letter-spacing-unit', 'em');
    await page.click('#btn-save-document-styles');

    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();
    await expect(page.locator('#doc-font-size-value')).toHaveValue('11');
    await expect(page.locator('#doc-font-size-unit')).toHaveValue('pt');
    await expect(page.locator('#doc-letter-spacing-value')).toHaveValue('0.2');
    await expect(page.locator('#doc-letter-spacing-unit')).toHaveValue('em');
  });

  test('document styles modal applies font weight text align and letter spacing to text block', async ({ page, appHelpers }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();

    await page.selectOption('#doc-font-weight', '600');
    await page.selectOption('#doc-text-align', 'right');
    await page.fill('#doc-letter-spacing-value', '1');
    await page.selectOption('#doc-letter-spacing-unit', 'px');
    await page.click('#btn-save-document-styles');

    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();
    const textEditor = block.locator('.text-block-editor');
    await expect(textEditor).toBeVisible();

    const computed = await textEditor.evaluate((el) => {
      const styles = window.getComputedStyle(el as HTMLElement);
      return {
        fontWeight: styles.fontWeight,
        textAlign: styles.textAlign,
        letterSpacing: styles.letterSpacing,
      };
    });

    expect(computed.fontWeight).toBe('600');
    expect(computed.textAlign).toBe('right');
    expect(computed.letterSpacing).toBe('1px');
  });

  test('document styles modal saves background color value', async ({ page }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();

    await page.fill('#doc-background-color', '#f0f0f0');
    await page.click('#btn-save-document-styles');

    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();
    await expect(page.locator('#doc-background-color')).toHaveValue('#f0f0f0');
  });

  test('document styles does not persist default #000000 text color', async ({ page }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();

    await page.fill('#doc-font-family', 'Georgia, serif');
    await page.fill('#doc-font-size-value', '16');
    await page.selectOption('#doc-font-size-unit', 'px');
    await page.fill('#doc-color', '#000000');
    await page.click('#btn-save-document-styles');

    const documentStyles = await page.evaluate(() => {
      const editor = (window as any).__editor;
      return editor?.getTemplate()?.documentStyles || {};
    });

    expect(documentStyles.fontFamily).toBe('Georgia, serif');
    expect(documentStyles.fontSize).toBe('16px');
    expect(documentStyles.color).not.toBe('#000000');
  });
});

test.describe('Block Styles - UI', () => {
  test('block styles modal roundtrip preserves values for selected block', async ({ page, appHelpers }) => {
    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();
    await appHelpers.selectBlock(block);

    await page.click('#btn-block-styles');
    await expect(page.locator('#block-styles-modal')).toBeVisible();

    await page.fill('#block-font-size', '20px');
    await page.selectOption('#block-font-weight', '700');
    await page.fill('#block-color', '#225577');
    await page.selectOption('#block-text-align', 'center');
    await page.fill('#block-padding', '12px');
    await page.fill('#block-margin', '4px');
    await page.fill('#block-bg-color', '#ffeecc');
    await page.fill('#block-border-radius', '6px');
    await page.click('#btn-save-block-styles');

    await page.click('#btn-block-styles');
    await expect(page.locator('#block-styles-modal')).toBeVisible();
    await expect(page.locator('#block-font-size')).toHaveValue('20px');
    await expect(page.locator('#block-font-weight')).toHaveValue('700');
    await expect(page.locator('#block-color')).toHaveValue('#225577');
    await expect(page.locator('#block-text-align')).toHaveValue('center');
    await expect(page.locator('#block-padding')).toHaveValue('12px');
    await expect(page.locator('#block-margin')).toHaveValue('4px');
    await expect(page.locator('#block-bg-color')).toHaveValue('#ffeecc');
    await expect(page.locator('#block-border-radius')).toHaveValue('6px');
  });

  test('block styles modal applies styles to selected text block content', async ({ page, appHelpers }) => {
    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();
    await appHelpers.selectBlock(block);

    await page.click('#btn-block-styles');
    await expect(page.locator('#block-styles-modal')).toBeVisible();
    await page.fill('#block-font-size', '20px');
    await page.fill('#block-color', '#224466');
    await page.fill('#block-padding', '12px');
    await page.fill('#block-bg-color', '#ffeecc');
    await page.click('#btn-save-block-styles');

    const blockContent = block.locator('.block-content');
    const textEditor = block.locator('.text-block-editor');
    await expect(blockContent).toBeVisible();
    await expect(textEditor).toBeVisible();

    const contentStyles = await blockContent.evaluate((el) => {
      const styles = window.getComputedStyle(el as HTMLElement);
      return {
        backgroundColor: styles.backgroundColor,
        paddingTop: styles.paddingTop,
      };
    });
    const textStyles = await textEditor.evaluate((el) => {
      const styles = window.getComputedStyle(el as HTMLElement);
      return {
        fontSize: styles.fontSize,
        color: styles.color,
      };
    });

    expect(contentStyles.backgroundColor).toBe('rgb(255, 238, 204)');
    expect(contentStyles.paddingTop).toBe('12px');
    expect(textStyles.fontSize).toBe('20px');
    expect(textStyles.color).toBe('rgb(34, 68, 102)');
  });

  test('block styles override document styles for selected block', async ({ page, appHelpers }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();
    await page.fill('#doc-font-size-value', '18');
    await page.selectOption('#doc-font-size-unit', 'px');
    await page.fill('#doc-color', '#333333');
    await page.click('#btn-save-document-styles');

    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();
    await appHelpers.selectBlock(block);

    await page.click('#btn-block-styles');
    await expect(page.locator('#block-styles-modal')).toBeVisible();
    await page.fill('#block-color', '#111111');
    await page.click('#btn-save-block-styles');

    const textEditor = block.locator('.text-block-editor');
    await expect(textEditor).toBeVisible();

    const computed = await textEditor.evaluate((el) => {
      const styles = window.getComputedStyle(el as HTMLElement);
      return {
        fontSize: styles.fontSize,
        color: styles.color,
      };
    });

    expect(computed.fontSize).toBe('18px');
    expect(computed.color).toBe('rgb(17, 17, 17)');
  });

  test('clear block styles removes overrides and falls back to document styles', async ({ page, appHelpers }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();
    await page.fill('#doc-color', '#333333');
    await page.click('#btn-save-document-styles');

    await appHelpers.addTextBlock();
    const block = appHelpers.getBlockByType(BLOCK_TYPES.text).last();
    await appHelpers.selectBlock(block);

    await page.click('#btn-block-styles');
    await expect(page.locator('#block-styles-modal')).toBeVisible();
    await page.fill('#block-color', '#111111');
    await page.fill('#block-padding', '12px');
    await page.click('#btn-save-block-styles');

    await page.click('#btn-block-styles');
    await expect(page.locator('#block-styles-modal')).toBeVisible();
    await page.click('#btn-clear-block-styles');
    await expect(page.locator('#block-color')).toHaveValue('');
    await expect(page.locator('#block-padding')).toHaveValue('');
    await page.click('#btn-save-block-styles');

    await page.click('#btn-block-styles');
    await expect(page.locator('#block-styles-modal')).toBeVisible();
    await expect(page.locator('#block-color')).toHaveValue('');
    await expect(page.locator('#block-padding')).toHaveValue('');
    await page.click('#btn-save-block-styles');

    const textEditor = block.locator('.text-block-editor');
    await expect(textEditor).toBeVisible();

    const computedColor = await textEditor.evaluate((el) => {
      return window.getComputedStyle(el as HTMLElement).color;
    });

    expect(computedColor).toBe('rgb(51, 51, 51)');
  });
});

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

  test('nested text block inherits container font size and background color', async ({ page, appHelpers }) => {
    await page.click('#btn-document-styles');
    await expect(page.locator('#document-styles-modal')).toBeVisible();
    await page.fill('#doc-font-size-value', '4');
    await page.selectOption('#doc-font-size-unit', 'rem');
    await page.click('#btn-save-document-styles');

    await appHelpers.addContainerBlock();
    const container = appHelpers.getBlockByType(BLOCK_TYPES.container).last();
    await appHelpers.selectBlock(container);

    await page.click('#btn-block-styles');
    await expect(page.locator('#block-styles-modal')).toBeVisible();
    await page.fill('#block-font-size', '2rem');
    await page.fill('#block-bg-color', '#ffeecc');
    await page.click('#btn-save-block-styles');

    await appHelpers.addBlockToContainer(container);
    const nestedText = container.locator(`[data-block-type="${BLOCK_TYPES.text}"]`).last();
    const nestedEditor = nestedText.locator('.text-block-editor');
    await expect(nestedEditor).toBeVisible();

    const nestedStyles = await nestedEditor.evaluate((el) => {
      const styles = window.getComputedStyle(el as HTMLElement);
      return {
        fontSize: styles.fontSize,
        backgroundColor: styles.backgroundColor,
      };
    });

    const headerStyles = await nestedText
      .locator('.block-header')
      .first()
      .evaluate((el) => {
        const styles = window.getComputedStyle(el as HTMLElement);
        return {
          fontSize: styles.fontSize,
        };
      });

    expect(nestedStyles.fontSize).toBe('32px');
    expect(nestedStyles.backgroundColor).toBe('rgb(255, 238, 204)');
    expect(headerStyles.fontSize).toBe('14px');
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
