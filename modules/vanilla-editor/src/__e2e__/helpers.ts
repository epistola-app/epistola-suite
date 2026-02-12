/**
 * AppHelpers - Common operations for E2E tests
 *
 * Provides methods for editor interactions, block finding, and state inspection.
 * Uses selectors from config.ts for consistency.
 */

import { Page, Locator, expect } from "@playwright/test";
import { SELECTORS, TIMEOUTS, BLOCK_TYPES } from "./config.js";

export class AppHelpers {
  constructor(private page: Page) {}

  private get editorRoot(): Locator {
    return this.page.locator(SELECTORS.editorRoot);
  }

  // ============================================
  // Navigation & Initialization
  // ============================================

  async waitForEditor(): Promise<void> {
    await this.page.waitForSelector(SELECTORS.editorRoot, {
      timeout: TIMEOUTS.long,
    });
    await this.page.waitForSelector(SELECTORS.block, {
      timeout: TIMEOUTS.long,
    });
  }

  // ============================================
  // Block Finding
  // ============================================

  getBlocks(): Locator {
    return this.page.locator(SELECTORS.block);
  }

  getBlockByType(type: string): Locator {
    return this.page.locator(SELECTORS.blockByType(type));
  }

  getBlockById(id: string): Locator {
    return this.page.locator(`${SELECTORS.block}[data-block-id="${id}"]`);
  }

  async getBlockCount(): Promise<number> {
    return await this.getBlocks().count();
  }

  async getBlockIds(): Promise<string[]> {
    return await this.page.evaluate((selector) => {
      const blocks = Array.from(document.querySelectorAll(selector));
      return blocks.map((block) => block.getAttribute("data-block-id") || "");
    }, SELECTORS.block);
  }

  async getBlockTypes(): Promise<string[]> {
    return await this.page.evaluate((selector) => {
      const blocks = Array.from(document.querySelectorAll(selector));
      return blocks.map((block) => block.getAttribute("data-block-type") || "");
    }, SELECTORS.block);
  }

  // ============================================
  // Adding Blocks
  // ============================================

  async addBlock(type: string): Promise<void> {
    await this.page.click(SELECTORS.addBlockButton(type));
    await this.page.waitForSelector(SELECTORS.blockByType(type), {
      timeout: TIMEOUTS.medium,
    });
  }

  async addBlockToSelected(): Promise<void> {
    const addToSelectedButton = this.page.locator(
      SELECTORS.addToSelectedButton,
    );
    if (await addToSelectedButton.count()) {
      await addToSelectedButton.click();
      await this.page.waitForTimeout(TIMEOUTS.short);
      return;
    }

    throw new Error(
      "Add-to-selected action is not available in this editor build",
    );
  }

  // Convenience methods for common blocks
  async addTextBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.text);
  }

  async addContainerBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.container);
  }

  async addConditionalBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.conditional);
  }

  async addLoopBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.loop);
  }

  async addColumnsBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.columns);
  }

  async addTableBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.table);
  }

  async addPageBreakBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.pagebreak);
  }

  async addPageHeaderBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.pageheader);
  }

  async addPageFooterBlock(): Promise<void> {
    await this.addBlock(BLOCK_TYPES.pagefooter);
  }

  // ============================================
  // Selecting Blocks
  // ============================================

  async selectBlock(block: Locator): Promise<void> {
    await block.locator(SELECTORS.blockHeader).click();
  }

  async selectLastBlockOfType(type: string): Promise<void> {
    const block = this.getBlockByType(type).last();
    await this.selectBlock(block);
  }

  // ============================================
  // Deleting Blocks
  // ============================================

  async deleteBlock(block: Locator): Promise<void> {
    await block.hover();
    // Use :scope > to select only the direct child delete button, not nested ones
    await block
      .locator(`:scope > ${SELECTORS.blockHeader} ${SELECTORS.deleteButton}`)
      .click();
    await this.page.waitForTimeout(TIMEOUTS.short);
  }

  async deleteLastBlockOfType(type: string): Promise<void> {
    const block = this.getBlockByType(type).last();
    await this.deleteBlock(block);
  }

  // ============================================
  // Undo / Redo
  // ============================================

  async undo(): Promise<void> {
    await this.page.click(SELECTORS.undoButton);
    await this.page.waitForTimeout(TIMEOUTS.short);
  }

  async redo(): Promise<void> {
    await this.page.click(SELECTORS.redoButton);
    await this.page.waitForTimeout(TIMEOUTS.short);
  }

  // ============================================
  // Waiting
  // ============================================

  async waitForBlockCount(
    count: number,
    timeout = TIMEOUTS.medium,
  ): Promise<void> {
    await this.page.waitForFunction(
      ({ selector, expectedCount }) => {
        return document.querySelectorAll(selector).length === expectedCount;
      },
      { selector: SELECTORS.block, expectedCount: count },
      { timeout },
    );
  }

  async waitForBlockTypeCount(
    type: string,
    count: number,
    timeout = TIMEOUTS.medium,
  ): Promise<void> {
    await this.page.waitForFunction(
      ({ type, count }) => {
        const blocks = document.querySelectorAll(`[data-block-type="${type}"]`);
        return blocks.length === count;
      },
      { type, count },
      { timeout },
    );
  }

  // ============================================
  // Drag and Drop
  // ============================================

  async dragBlockToContainer(
    block: Locator,
    container: Locator,
  ): Promise<void> {
    const dragHandle = block.locator(SELECTORS.dragHandle);
    const dropZone = container.locator(SELECTORS.sortableContainer);
    await dragHandle.dragTo(dropZone);
    await this.page.waitForTimeout(TIMEOUTS.medium);
  }

  async dragBlockHandleToTarget(block: Locator, target: Locator): Promise<void> {
    const dragHandle = block.locator(SELECTORS.dragHandle).first();
    const dropTarget = target.first();

    await dragHandle.scrollIntoViewIfNeeded();
    await dropTarget.scrollIntoViewIfNeeded();

    await dragHandle.dragTo(dropTarget);
    await this.page.waitForTimeout(TIMEOUTS.medium);
  }

  async getTemplateBlockContent(blockId: string): Promise<unknown> {
    return await this.page.evaluate(async (id) => {
      const shell = document.querySelector('[data-editor-app-shell="true"]');
      if (!shell) {
        throw new Error("Editor shell not found");
      }

      const loadModule = new Function("path", "return import(path)") as (
        path: string,
      ) => Promise<any>;
      const mod = await loadModule("/vanilla-editor/vanilla-editor.js");
      const editor = mod.getEditorForElement?.(shell) as
        | { getTemplate: () => { blocks: unknown[] } }
        | null
        | undefined;

      if (!editor) {
        throw new Error("Editor instance not found");
      }

      const visit = (blocks: any[]): any | null => {
        for (const candidate of blocks) {
          if (candidate?.id === id) {
            return candidate;
          }

          if (Array.isArray(candidate?.children)) {
            const found = visit(candidate.children);
            if (found) return found;
          }

          if (candidate?.type === "columns" && Array.isArray(candidate?.columns)) {
            for (const column of candidate.columns) {
              const found = visit(column?.children ?? []);
              if (found) return found;
            }
          }

          if (candidate?.type === "table" && Array.isArray(candidate?.rows)) {
            for (const row of candidate.rows) {
              for (const cell of row?.cells ?? []) {
                const found = visit(cell?.children ?? []);
                if (found) return found;
              }
            }
          }
        }

        return null;
      };

      const block = visit(editor.getTemplate().blocks as any[]);
      if (!block) return null;
      return block.content ?? null;
    }, blockId);
  }

  // ============================================
  // Container / Nested Operations
  // ============================================

  async getSelectedContainer(): Promise<Locator | null> {
    const selected = this.page
      .locator(`${SELECTORS.block}[class*="selected"]`)
      .first();
    if ((await selected.count()) === 0) {
      return null;
    }
    return selected;
  }

  async addBlockToContainer(container: Locator): Promise<void> {
    const containerBlockId = await container.getAttribute("data-block-id");
    if (!containerBlockId) {
      throw new Error(
        "Cannot add child block: target container has no data-block-id",
      );
    }

    const insertedViaEditor = await this.page.evaluate(async (parentId) => {
      const shell = document.querySelector('[data-editor-app-shell="true"]');
      if (!shell) return false;

      const loadModule = new Function("path", "return import(path)") as (
        path: string,
      ) => Promise<any>;
      const mod = await loadModule("/vanilla-editor/vanilla-editor.js");
      const editor = mod.getEditorForElement?.(shell) as
        | { addBlock: (type: string, parentId: string) => unknown }
        | null
        | undefined;
      if (!editor) return false;

      return Boolean(editor.addBlock("text", parentId));
    }, containerBlockId);

    if (insertedViaEditor) {
      await this.page.waitForTimeout(TIMEOUTS.short);
      return;
    }

    const nestedTextBefore = await container
      .locator(`[data-block-type="${BLOCK_TYPES.text}"]`)
      .count();

    await container.click();

    const addToSelectedButton = this.page.locator(
      SELECTORS.addToSelectedButton,
    );
    if (await addToSelectedButton.count()) {
      await this.addBlockToSelected();
      await this.page.waitForTimeout(TIMEOUTS.short);

      const nestedTextAfter = await container
        .locator(`[data-block-type="${BLOCK_TYPES.text}"]`)
        .count();

      if (nestedTextAfter > nestedTextBefore) {
        return;
      }
    }

    const existingTextIds = await this.page.evaluate(() =>
      Array.from(document.querySelectorAll('[data-block-type="text"]')).map(
        (el) => el.getAttribute("data-block-id") || "",
      ),
    );

    await this.addTextBlock();

    const newTextBlockId = await this.page.waitForFunction(
      (knownIds) => {
        const textBlocks = Array.from(
          document.querySelectorAll('[data-block-type="text"]'),
        );
        for (const block of textBlocks) {
          const id = block.getAttribute("data-block-id");
          if (id && !knownIds.includes(id)) return id;
        }
        return null;
      },
      existingTextIds,
      { timeout: TIMEOUTS.medium },
    );

    const resolvedBlockId = (await newTextBlockId.jsonValue()) as string | null;
    if (!resolvedBlockId) {
      throw new Error(
        "Failed to identify newly added text block for container insertion",
      );
    }

    const newTextBlock = this.getBlockById(resolvedBlockId);
    await this.dragBlockToContainer(newTextBlock, container);
  }

  // ============================================
  // Expression Editing
  // ============================================

  async fillExpressionInput(block: Locator, value: string): Promise<void> {
    const input = block.locator(SELECTORS.expressionInput).first();
    await input.click();
    await input.fill(value);
  }

  async typeInTextBlock(block: Locator, value: string): Promise<void> {
    const editor = block.locator(".text-block-editor").first();
    await editor.click();
    await this.page.keyboard.type(value);
  }

  async openExpressionChipPopover(chip: Locator): Promise<void> {
    await chip.click();
    await expect(
      this.page.locator(SELECTORS.expressionChipPopover),
    ).toBeVisible();
  }

  // ============================================
  // Table Operations
  // ============================================

  async addRowToTable(table: Locator): Promise<void> {
    await table.locator("button", { hasText: "Add Row" }).click();
    await this.page.waitForTimeout(TIMEOUTS.short);
  }

  async removeRowFromTable(table: Locator, rowIndex: number): Promise<void> {
    await table.locator('button[title="Remove row"]').nth(rowIndex).click();
    await this.page.waitForTimeout(TIMEOUTS.short);
  }

  async addTextToTableCell(table: Locator, cellIndex: number): Promise<void> {
    await table.locator(SELECTORS.addTextLink).nth(cellIndex).click();
    await this.page.waitForTimeout(TIMEOUTS.short);
  }

  // ============================================
  // Columns Operations
  // ============================================

  async addColumnToColumns(columnsBlock: Locator): Promise<void> {
    await columnsBlock.locator("button", { hasText: "Add Column" }).click();
    await this.page.waitForTimeout(TIMEOUTS.short);
  }

  async removeColumnFromColumns(
    columnsBlock: Locator,
    columnIndex: number,
  ): Promise<void> {
    await columnsBlock
      .locator(SELECTORS.columnWrapper)
      .nth(columnIndex)
      .locator('button[title="Remove column"]')
      .click();
    await this.page.waitForTimeout(TIMEOUTS.short);
  }

  // ============================================
  // Assertions (convenience wrappers)
  // ============================================

  async expectBlockVisible(type: string): Promise<void> {
    await expect(this.getBlockByType(type).last()).toBeVisible();
  }

  async expectBlockCount(count: number): Promise<void> {
    await expect(await this.getBlockCount()).toBe(count);
  }
}
