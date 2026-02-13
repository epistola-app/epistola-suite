import { test, expect } from "./fixtures.js";
import { AUTH, BLOCK_TYPES, ROUTES, SELECTORS, TIMEOUTS } from "./config.js";

async function addBlockAndGetNewId(
  page: import("@playwright/test").Page,
  type: string,
): Promise<string> {
  const beforeIds = await page
    .locator(SELECTORS.blockByType(type))
    .evaluateAll((nodes) =>
      nodes
        .map((node) => node.getAttribute("data-block-id") || "")
        .filter(Boolean),
    );

  await page.click(SELECTORS.addBlockButton(type));

  const newIdHandle = await page.waitForFunction(
    ({ selector, knownIds }) => {
      const ids = Array.from(document.querySelectorAll(selector))
        .map((node) => node.getAttribute("data-block-id") || "")
        .filter(Boolean);
      return ids.find((id) => !knownIds.includes(id)) || null;
    },
    { selector: SELECTORS.blockByType(type), knownIds: beforeIds },
    { timeout: 10000 },
  );

  const newId = (await newIdHandle.jsonValue()) as string | null;
  expect(newId).toBeTruthy();
  return newId!;
}

test("drag text block into container persists after save and refresh", async ({
  page,
}) => {
  await page.goto(`${AUTH.baseUrl}${ROUTES.editor}?veDndMode=fallback`, {
    waitUntil: "load",
  });
  await page.waitForSelector(SELECTORS.editorRoot, { timeout: TIMEOUTS.veryLong });
  await page.waitForSelector(SELECTORS.block, { timeout: TIMEOUTS.long });

  const textBlockId = await addBlockAndGetNewId(page, BLOCK_TYPES.text);
  const containerBlockId = await addBlockAndGetNewId(page, BLOCK_TYPES.container);

  const sourceHandle = page.locator(
    `${SELECTORS.block}[data-block-id="${textBlockId}"] ${SELECTORS.blockHeader} ${SELECTORS.dragHandle}`,
  );
  const targetDropZone = page.locator(
    `${SELECTORS.block}[data-block-id="${containerBlockId}"] ${SELECTORS.sortableContainer}`,
  );
  const preferredTarget =
    (await targetDropZone.locator(SELECTORS.emptyState).count()) > 0
      ? targetDropZone.locator(SELECTORS.emptyState).first()
      : targetDropZone;

  await sourceHandle.scrollIntoViewIfNeeded();
  await targetDropZone.scrollIntoViewIfNeeded();

  await sourceHandle.hover();
  await page.mouse.down();
  await preferredTarget.hover();
  await preferredTarget.hover();
  await page.waitForTimeout(TIMEOUTS.short);
  await page.mouse.up();
  await page.waitForTimeout(TIMEOUTS.medium);

  const containerBlock = page.locator(
    `${SELECTORS.block}[data-block-id="${containerBlockId}"]`,
  );
  const textInsideContainer = containerBlock.locator(
    `${SELECTORS.block}[data-block-id="${textBlockId}"]`,
  );

  await expect(textInsideContainer).toBeVisible();

  const saveStatus = page.locator('[data-editor-target="saveStatus"]');
  await expect(saveStatus).toContainText("Unsaved changes");

  await page.click('[data-editor-target="saveBtn"]');
  await expect(saveStatus).toContainText("Saved", { timeout: 10000 });

  await page.reload({ waitUntil: "load" });
  await page.waitForSelector(SELECTORS.editorRoot, { timeout: TIMEOUTS.veryLong });
  await page.waitForSelector(SELECTORS.block, { timeout: TIMEOUTS.long });

  const reloadedContainerBlock = page.locator(
    `${SELECTORS.block}[data-block-id="${containerBlockId}"]`,
  );
  await expect(
    reloadedContainerBlock.locator(
      `${SELECTORS.block}[data-block-id="${textBlockId}"]`,
    ),
  ).toBeVisible();
});
