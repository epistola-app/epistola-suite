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
      container.querySelector('[data-editor-app-modal="page-settings"]'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-editor-app-modal="document-styles"]'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-editor-app-modal="block-styles"]'),
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

  it("renders add-block toolbar from registered plugin metadata", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
      plugins: [
        {
          type: "custom-banner",
          create: (id) => ({ id, type: "custom-banner", content: null }) as any,
          validate: () => ({ valid: true, errors: [] }),
          constraints: {
            canHaveChildren: false,
            allowedChildTypes: [],
            canBeDragged: true,
            canBeNested: true,
            allowedParentTypes: ["root"],
          },
          toolbar: {
            visible: true,
            label: "Banner",
            group: "Custom",
            order: 1,
          },
        } as any,
        {
          type: "internal-cell",
          create: (id) => ({ id, type: "internal-cell", children: [] }) as any,
          validate: () => ({ valid: true, errors: [] }),
          constraints: {
            canHaveChildren: true,
            allowedChildTypes: null,
            canBeDragged: false,
            canBeNested: true,
            allowedParentTypes: null,
          },
          toolbar: false,
        } as any,
      ] as any,
    });

    const toolbar = container.querySelector(
      '[data-editor-app-target="pluginToolbar"]',
    );
    expect(toolbar?.textContent).toContain("Banner");
    expect(toolbar?.textContent).not.toContain("internal-cell");
    expect(
      container.querySelector(
        '[data-editor-app-target="pluginToolbar"] [data-block-type="custom-banner"]',
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

  it("saves page settings from app shell modal", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });

    (
      container.querySelector(
        '[data-editor-app-action="open-page-settings"]',
      ) as HTMLButtonElement
    ).click();
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
    orientation.value = "landscape";
    marginTop.value = "12";

    (
      container.querySelector(
        '[data-editor-app-action="save-page-settings"]',
      ) as HTMLButtonElement
    ).click();

    expect(mounted.getTemplate().pageSettings?.format).toBe("Letter");
    expect(mounted.getTemplate().pageSettings?.orientation).toBe("landscape");
    expect(mounted.getTemplate().pageSettings?.margins.top).toBe(12);

    mounted.destroy();
  });

  it("saves document styles from app shell modal", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });

    (
      container.querySelector(
        '[data-editor-app-action="open-document-styles"]',
      ) as HTMLButtonElement
    ).click();
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
    fontSizeValue.value = "18";
    fontSizeUnit.value = "px";

    (
      container.querySelector(
        '[data-editor-app-action="save-document-styles"]',
      ) as HTMLButtonElement
    ).click();

    expect(mounted.getTemplate().documentStyles?.fontFamily).toBe(
      "Georgia, serif",
    );
    expect(mounted.getTemplate().documentStyles?.fontSize).toBe("18px");

    mounted.destroy();
  });

  it("saves block styles from app shell modal for selected block", () => {
    const mounted = mountEditorApp({
      container,
      template: EMPTY_TEMPLATE,
    });
    const block = mounted.getEditor().addBlock("text")!;
    mounted.getEditor().selectBlock(block.id);

    (
      container.querySelector(
        '[data-editor-app-action="open-block-styles"]',
      ) as HTMLButtonElement
    ).click();
    const color = container.querySelector(
      "#ve-block-color",
    ) as HTMLInputElement;
    const padding = container.querySelector(
      "#ve-block-padding",
    ) as HTMLInputElement;

    color.value = "#112233";
    padding.value = "12px";

    (
      container.querySelector(
        '[data-editor-app-action="save-block-styles"]',
      ) as HTMLButtonElement
    ).click();

    const updated = mounted.getEditor().findBlock(block.id) as any;
    expect(updated.styles.color).toBe("#112233");
    expect(updated.styles.padding).toBe("12px");

    mounted.destroy();
  });
});
