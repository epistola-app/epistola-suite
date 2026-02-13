import { test, expect } from "./fixtures.js";
import { BLOCK_TYPES, SELECTORS, TIMEOUTS } from "./config.js";

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

test("add to selected container persists after save and refresh", async ({
  page,
}) => {
  const containerId = await addBlockAndGetNewId(page, BLOCK_TYPES.container);

  const containerBlock = page.locator(
    `${SELECTORS.block}[data-block-id="${containerId}"]`,
  );

  await containerBlock.locator(SELECTORS.blockHeader).click();

  const nestedTextBefore = await containerBlock
    .locator(SELECTORS.blockByType(BLOCK_TYPES.text))
    .count();

  await page.click(SELECTORS.addToSelectedButton);

  await expect(
    containerBlock.locator(SELECTORS.blockByType(BLOCK_TYPES.text)),
  ).toHaveCount(nestedTextBefore + 1);

  const saveStatus = page.locator('[data-editor-target="saveStatus"]');
  await expect(saveStatus).toContainText("Unsaved changes");

  await page.click('[data-editor-target="saveBtn"]');
  await expect(saveStatus).toContainText("Saved", { timeout: 10000 });

  await page.reload({ waitUntil: "load" });
  await page.waitForSelector(SELECTORS.editorRoot, { timeout: TIMEOUTS.veryLong });
  await page.waitForSelector(SELECTORS.block, { timeout: TIMEOUTS.long });

  const reloadedContainerBlock = page.locator(
    `${SELECTORS.block}[data-block-id="${containerId}"]`,
  );

  await expect(reloadedContainerBlock).toBeVisible();
  await expect(
    reloadedContainerBlock.locator(SELECTORS.blockByType(BLOCK_TYPES.text)),
  ).toHaveCount(nestedTextBefore + 1);
});
