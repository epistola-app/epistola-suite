import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mountEditorApp } from "../mount";

const EMPTY_TEMPLATE = {
  id: "test-1",
  name: "Test",
  blocks: [],
  styles: {},
};

describe("mountEditorApp", () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement("div");
    container.id = "test-editor-app";
    document.body.appendChild(container);
  });

  afterEach(() => {
    container.remove();
  });

  it("mounts package-owned shell and editor canvas", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });

    expect(
      container.querySelector('[data-editor-app-shell="true"]'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-editor-target="blockContainer"]'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-editor-app-target="styleSidebar"]'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-editor-app-panel="document"]'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-editor-app-panel="block"]'),
    ).not.toBeNull();

    mounted.destroy();
  });

  it("throws on invalid container selector", () => {
    expect(() =>
      mountEditorApp({
        container: "#nonexistent",
        template: EMPTY_TEMPLATE,
      }),
    ).toThrow("Container not found");
  });

  it("renders add-block toolbar from built-in block metadata", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });

    const toolbar = container.querySelector(
      '[data-editor-app-target="pluginToolbar"]',
    );
    expect(toolbar?.textContent).toContain("Text");
    expect(toolbar?.textContent).toContain("Container");
    expect(
      container.querySelector(
        '[data-editor-app-target="pluginToolbar"] [data-block-type="text"]',
      ),
    ).not.toBeNull();

    mounted.destroy();
  });

  it("invokes onSave callback from shell save action", async () => {
    let saveCalled = false;
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
      onSave: async () => {
        saveCalled = true;
      },
    });

    const saveBtn = container.querySelector(
      '[data-editor-target="saveBtn"]',
    ) as HTMLButtonElement;
    saveBtn.click();
    await Promise.resolve();

    expect(saveCalled).toBe(true);
    mounted.destroy();
  });

  it("surfaces preview callback errors in shell state", async () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
      onPreview: async () => {
        throw new Error("Preview exploded");
      },
    });

    const previewBtn = container.querySelector(
      '[data-editor-app-action="preview"]',
    ) as HTMLButtonElement;
    previewBtn.click();
    await Promise.resolve();

    const status = container.querySelector(
      '[data-editor-app-target="previewStatus"]',
    ) as HTMLElement;
    expect(status.textContent).toContain("Preview exploded");

    mounted.destroy();
  });

  it("autosaves page settings from sidebar", async () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });

    const format = container.querySelector(
      "#ve-page-format",
    ) as HTMLSelectElement;
    const orientation = container.querySelector(
      "#ve-page-orientation",
    ) as HTMLSelectElement;
    const marginTop = container.querySelector(
      "#ve-margin-top",
    ) as HTMLInputElement;

    format.value = "Letter";
    format.dispatchEvent(new Event("change", { bubbles: true }));
    orientation.value = "landscape";
    orientation.dispatchEvent(new Event("change", { bubbles: true }));
    marginTop.value = "12";
    marginTop.dispatchEvent(new Event("input", { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(mounted.getTemplate().pageSettings?.format).toBe("Letter");
    expect(mounted.getTemplate().pageSettings?.orientation).toBe("landscape");
    expect(mounted.getTemplate().pageSettings?.margins.top).toBe(12);

    mounted.destroy();
  });

  it("autosaves document styles from sidebar", async () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });

    const fontFamily = container.querySelector(
      "#ve-doc-font-family",
    ) as HTMLInputElement;
    const fontSizeValue = container.querySelector(
      "#ve-doc-font-size-value",
    ) as HTMLInputElement;
    const fontSizeUnit = container.querySelector(
      "#ve-doc-font-size-unit",
    ) as HTMLSelectElement;

    fontFamily.value = "Georgia, serif";
    fontFamily.dispatchEvent(new Event("input", { bubbles: true }));
    fontSizeValue.value = "18";
    fontSizeValue.dispatchEvent(new Event("input", { bubbles: true }));
    fontSizeUnit.value = "px";
    fontSizeUnit.dispatchEvent(new Event("change", { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(mounted.getTemplate().documentStyles?.fontFamily).toBe(
      "Georgia, serif",
    );
    expect(mounted.getTemplate().documentStyles?.fontSize).toBe("18px");

    mounted.destroy();
  });

  it("autosaves block styles from sidebar for selected block", async () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });
    const block = mounted.getEditor().addBlock("text")!;
    mounted.getEditor().selectBlock(block.id);

    const color = container.querySelector(
      "#ve-block-color",
    ) as HTMLInputElement;
    const padding = container.querySelector(
      "#ve-block-padding",
    ) as HTMLInputElement;

    color.value = "#112233";
    color.dispatchEvent(new Event("input", { bubbles: true }));
    padding.value = "12px";
    padding.dispatchEvent(new Event("input", { bubbles: true }));
    await new Promise((resolve) => setTimeout(resolve, 300));

    const updated = mounted.getEditor().findBlock(block.id) as any;
    expect(updated.styles.color).toBe("#112233");
    expect(updated.styles.padding).toBe("12px");

    mounted.destroy();
  });

  it("shows 'No Block Selected' when block tab is opened manually", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });

    (
      container.querySelector(
        '[data-editor-app-tab="block"]',
      ) as HTMLButtonElement
    ).click();

    const message = container.querySelector(
      '[data-editor-app-target="noBlockSelected"]',
    ) as HTMLElement;
    expect(message.textContent).toContain("No Block Selected");

    mounted.destroy();
  });

  it("auto switches sidebar tab based on selection", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });
    const editor = mounted.getEditor();
    const block = editor.addBlock("text");
    expect(block).not.toBeNull();

    const pageTab = container.querySelector(
      '[data-editor-app-tab="document"]',
    ) as HTMLButtonElement;
    const blockTab = container.querySelector(
      '[data-editor-app-tab="block"]',
    ) as HTMLButtonElement;

    editor.selectBlock(block!.id);
    expect(blockTab.classList.contains("is-active")).toBe(true);

    editor.selectBlock(null);
    expect(pageTab.classList.contains("is-active")).toBe(true);

    mounted.destroy();
  });

  it("selects text block when clicking inside tiptap editor", async () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });
    const editor = mounted.getEditor();
    const block = editor.addBlock("text");
    expect(block).not.toBeNull();

    await Promise.resolve();

    const textEditor = container.querySelector(
      `[data-block-id="${block!.id}"] .text-block-editor`,
    ) as HTMLElement | null;
    expect(textEditor).not.toBeNull();

    textEditor!.dispatchEvent(new MouseEvent("click", { bubbles: true }));

    expect(editor.getState().selectedBlockId).toBe(block!.id);

    mounted.destroy();
  });

  it("toggles styles sidebar collapsed state", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });

    const main = container.querySelector(".ve-app-main") as HTMLElement;
    const sidebar = container.querySelector(
      '[data-editor-app-target="styleSidebar"]',
    ) as HTMLElement;
    const toggleButton = container.querySelector(
      '[data-editor-app-action="toggle-style-sidebar"]',
    ) as HTMLButtonElement;

    expect(main.classList.contains("ve-sidebar-collapsed")).toBe(false);
    expect(sidebar.classList.contains("is-collapsed")).toBe(false);
    expect(toggleButton.getAttribute("aria-expanded")).toBe("true");

    toggleButton.click();

    expect(main.classList.contains("ve-sidebar-collapsed")).toBe(true);
    expect(sidebar.classList.contains("is-collapsed")).toBe(true);
    expect(toggleButton.getAttribute("aria-expanded")).toBe("false");

    toggleButton.click();

    expect(main.classList.contains("ve-sidebar-collapsed")).toBe(false);
    expect(sidebar.classList.contains("is-collapsed")).toBe(false);
    expect(toggleButton.getAttribute("aria-expanded")).toBe("true");

    mounted.destroy();
  });
});
