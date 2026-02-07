/**
 * Style sidebar component.
 *
 * Displays properties and styles for the selected block or document.
 */

import type { Block, DocumentStyles } from "../types/template.ts";
import type { CSSStyles } from "../types/styles.ts";
import {
  createUnitInput,
  createColorInput,
  createSpacingInput,
  createSelectInput,
  type SpacingSide,
} from "./inputs/index.ts";

// ============================================================================
// Types
// ============================================================================

export interface SidebarOptions {
  /** Container element to mount into */
  container: HTMLElement;
  /** Initially collapsed */
  collapsed?: boolean;
  /** Callback when document styles change */
  onDocumentStylesChange?: (styles: Partial<DocumentStyles>) => void;
  /** Callback when block styles change */
  onBlockStylesChange?: (blockId: string, styles: Partial<CSSStyles>) => void;
  /** Callback when block properties change */
  onBlockPropertiesChange?: (
    blockId: string,
    properties: Record<string, unknown>,
  ) => void;
}

export interface Sidebar {
  /** Root element */
  element: HTMLElement;
  /** Set selected block (null for document) */
  setSelectedBlock(block: Block | null): void;
  /** Set document styles */
  setDocumentStyles(styles: DocumentStyles): void;
  /** Toggle collapsed state */
  toggle(): void;
  /** Set collapsed state */
  setCollapsed(collapsed: boolean): void;
  /** Destroy and cleanup */
  destroy(): void;
}

type TabType = "properties" | "styles";

// ============================================================================
// Component
// ============================================================================

/**
 * Create a style sidebar component.
 */
export function createSidebar(options: SidebarOptions): Sidebar {
  const {
    container,
    collapsed: initialCollapsed = false,
    onDocumentStylesChange,
    onBlockStylesChange,
  } = options;

  let isCollapsed = initialCollapsed;
  let activeTab: TabType = "styles";
  let selectedBlock: Block | null = null;
  let documentStyles: DocumentStyles = {};

  // Create root element
  const root = document.createElement("div");
  root.className = "ev2-sidebar";
  if (isCollapsed) root.classList.add("ev2-sidebar--collapsed");

  // Header
  const header = document.createElement("div");
  header.className = "ev2-sidebar__header";

  const headerTitle = document.createElement("span");
  headerTitle.className = "ev2-sidebar__title";
  headerTitle.textContent = "Document Settings";

  const toggleBtn = document.createElement("button");
  toggleBtn.type = "button";
  toggleBtn.className = "ev2-sidebar__toggle";
  toggleBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>`;
  toggleBtn.title = "Toggle sidebar";

  header.appendChild(headerTitle);
  header.appendChild(toggleBtn);
  root.appendChild(header);

  // Tabs
  const tabs = document.createElement("div");
  tabs.className = "ev2-sidebar__tabs";

  const propertiesTab = document.createElement("button");
  propertiesTab.type = "button";
  propertiesTab.className = "ev2-sidebar__tab";
  propertiesTab.textContent = "Properties";

  const stylesTab = document.createElement("button");
  stylesTab.type = "button";
  stylesTab.className = "ev2-sidebar__tab ev2-sidebar__tab--active";
  stylesTab.textContent = "Styles";

  tabs.appendChild(propertiesTab);
  tabs.appendChild(stylesTab);
  root.appendChild(tabs);

  // Content
  const content = document.createElement("div");
  content.className = "ev2-sidebar__content";
  root.appendChild(content);

  // Footer
  const footer = document.createElement("div");
  footer.className = "ev2-sidebar__footer";
  footer.innerHTML = `<span class="ev2-sidebar__app-name">Epistola</span>`;
  root.appendChild(footer);

  // Render content based on selection and tab
  function render(): void {
    content.innerHTML = "";

    if (activeTab === "styles") {
      if (selectedBlock) {
        renderBlockStyles();
      } else {
        renderDocumentStyles();
      }
    } else {
      if (selectedBlock) {
        renderBlockProperties();
      } else {
        renderDocumentProperties();
      }
    }
  }

  // Render document styles
  function renderDocumentStyles(): void {
    const section = createSection("Typography");

    // Font family
    const fontFamily = createSelectInput({
      label: "Font Family",
      value: documentStyles.fontFamily,
      options: [
        { value: "Arial, sans-serif", label: "Arial" },
        { value: "Helvetica, sans-serif", label: "Helvetica" },
        { value: "Georgia, serif", label: "Georgia" },
        { value: "Times New Roman, serif", label: "Times New Roman" },
        { value: "Courier New, monospace", label: "Courier New" },
      ],
      onChange: (value) => {
        documentStyles.fontFamily = value;
        onDocumentStylesChange?.({ fontFamily: value });
      },
    });
    section.appendChild(fontFamily.element);

    // Font size
    const fontSize = createUnitInput({
      label: "Font Size",
      value: documentStyles.fontSize,
      units: ["px", "em", "rem", "pt"],
      defaultUnit: "px",
      onChange: (value) => {
        documentStyles.fontSize = value;
        onDocumentStylesChange?.({ fontSize: value });
      },
    });
    section.appendChild(fontSize.element);

    // Color
    const color = createColorInput({
      label: "Text Color",
      value: documentStyles.color,
      onChange: (value) => {
        documentStyles.color = value;
        onDocumentStylesChange?.({ color: value });
      },
    });
    section.appendChild(color.element);

    // Line height
    const lineHeight = createUnitInput({
      label: "Line Height",
      value: documentStyles.lineHeight,
      units: ["px", "em", "%"],
      defaultUnit: "em",
      step: 0.1,
      onChange: (value) => {
        documentStyles.lineHeight = value;
        onDocumentStylesChange?.({ lineHeight: value });
      },
    });
    section.appendChild(lineHeight.element);

    // Text align
    const textAlign = createSelectInput({
      label: "Text Align",
      value: documentStyles.textAlign,
      options: [
        { value: "left", label: "Left" },
        { value: "center", label: "Center" },
        { value: "right", label: "Right" },
        { value: "justify", label: "Justify" },
      ],
      onChange: (value) => {
        documentStyles.textAlign = value as DocumentStyles["textAlign"];
        onDocumentStylesChange?.({
          textAlign: value as DocumentStyles["textAlign"],
        });
      },
    });
    section.appendChild(textAlign.element);

    content.appendChild(section);

    // Background section
    const bgSection = createSection("Background");

    const bgColor = createColorInput({
      label: "Background Color",
      value: documentStyles.backgroundColor,
      onChange: (value) => {
        documentStyles.backgroundColor = value;
        onDocumentStylesChange?.({ backgroundColor: value });
      },
    });
    bgSection.appendChild(bgColor.element);

    content.appendChild(bgSection);
  }

  // Render block styles
  function renderBlockStyles(): void {
    if (!selectedBlock) return;

    const styles = selectedBlock.styles ?? {};

    // Spacing section
    const spacingSection = createSection("Spacing");

    // Padding
    const padding = createSpacingInput({
      label: "Padding",
      values: {
        top: styles.paddingTop,
        right: styles.paddingRight,
        bottom: styles.paddingBottom,
        left: styles.paddingLeft,
      },
      onChange: (side: SpacingSide, value) => {
        const key = `padding${side.charAt(0).toUpperCase()}${side.slice(1)}` as keyof CSSStyles;
        onBlockStylesChange?.(selectedBlock!.id, { [key]: value });
      },
    });
    spacingSection.appendChild(padding.element);

    // Margin
    const margin = createSpacingInput({
      label: "Margin",
      values: {
        top: styles.marginTop,
        right: styles.marginRight,
        bottom: styles.marginBottom,
        left: styles.marginLeft,
      },
      onChange: (side: SpacingSide, value) => {
        const key = `margin${side.charAt(0).toUpperCase()}${side.slice(1)}` as keyof CSSStyles;
        onBlockStylesChange?.(selectedBlock!.id, { [key]: value });
      },
    });
    spacingSection.appendChild(margin.element);

    content.appendChild(spacingSection);

    // Background section
    const bgSection = createSection("Background");

    const bgColor = createColorInput({
      label: "Background Color",
      value: styles.backgroundColor,
      onChange: (value) => {
        onBlockStylesChange?.(selectedBlock!.id, { backgroundColor: value });
      },
    });
    bgSection.appendChild(bgColor.element);

    content.appendChild(bgSection);

    // Border section
    const borderSection = createSection("Border");

    const borderWidth = createUnitInput({
      label: "Border Width",
      value: styles.borderWidth,
      units: ["px"],
      defaultUnit: "px",
      onChange: (value) => {
        onBlockStylesChange?.(selectedBlock!.id, { borderWidth: value });
      },
    });
    borderSection.appendChild(borderWidth.element);

    const borderColor = createColorInput({
      label: "Border Color",
      value: styles.borderColor,
      onChange: (value) => {
        onBlockStylesChange?.(selectedBlock!.id, { borderColor: value });
      },
    });
    borderSection.appendChild(borderColor.element);

    const borderStyle = createSelectInput({
      label: "Border Style",
      value: styles.borderStyle ?? "none",
      options: [
        { value: "none", label: "None" },
        { value: "solid", label: "Solid" },
        { value: "dashed", label: "Dashed" },
        { value: "dotted", label: "Dotted" },
      ],
      onChange: (value) => {
        onBlockStylesChange?.(selectedBlock!.id, {
          borderStyle: value as CSSStyles["borderStyle"],
        });
      },
    });
    borderSection.appendChild(borderStyle.element);

    const borderRadius = createUnitInput({
      label: "Border Radius",
      value: styles.borderRadius,
      units: ["px", "%"],
      defaultUnit: "px",
      onChange: (value) => {
        onBlockStylesChange?.(selectedBlock!.id, { borderRadius: value });
      },
    });
    borderSection.appendChild(borderRadius.element);

    content.appendChild(borderSection);
  }

  // Render document properties
  function renderDocumentProperties(): void {
    const section = createSection("Document");

    const info = document.createElement("p");
    info.className = "ev2-sidebar__info";
    info.textContent = "Select a block to edit its properties.";
    section.appendChild(info);

    content.appendChild(section);
  }

  // Render block properties
  function renderBlockProperties(): void {
    if (!selectedBlock) return;

    const section = createSection(`${selectedBlock.type} Block`);

    const info = document.createElement("p");
    info.className = "ev2-sidebar__info";
    info.textContent = `Block ID: ${selectedBlock.id.slice(0, 8)}...`;
    section.appendChild(info);

    // Type-specific properties would go here
    // For now, just show basic info

    content.appendChild(section);
  }

  // Create a section with title
  function createSection(title: string): HTMLElement {
    const section = document.createElement("div");
    section.className = "ev2-sidebar__section";

    const sectionTitle = document.createElement("h4");
    sectionTitle.className = "ev2-sidebar__section-title";
    sectionTitle.textContent = title;
    section.appendChild(sectionTitle);

    return section;
  }

  // Update title based on selection
  function updateTitle(): void {
    headerTitle.textContent = selectedBlock
      ? "Block Inspector"
      : "Document Settings";
  }

  // Tab handlers
  function setActiveTab(tab: TabType): void {
    activeTab = tab;
    propertiesTab.classList.toggle(
      "ev2-sidebar__tab--active",
      tab === "properties",
    );
    stylesTab.classList.toggle("ev2-sidebar__tab--active", tab === "styles");
    render();
  }

  // Event handlers
  toggleBtn.addEventListener("click", () => {
    isCollapsed = !isCollapsed;
    root.classList.toggle("ev2-sidebar--collapsed", isCollapsed);
  });

  propertiesTab.addEventListener("click", () => setActiveTab("properties"));
  stylesTab.addEventListener("click", () => setActiveTab("styles"));

  // Initial render
  render();

  // Mount
  container.appendChild(root);

  return {
    element: root,

    setSelectedBlock(block: Block | null): void {
      selectedBlock = block;
      updateTitle();
      render();
    },

    setDocumentStyles(styles: DocumentStyles): void {
      documentStyles = styles;
      if (!selectedBlock && activeTab === "styles") {
        render();
      }
    },

    toggle(): void {
      isCollapsed = !isCollapsed;
      root.classList.toggle("ev2-sidebar--collapsed", isCollapsed);
    },

    setCollapsed(collapsed: boolean): void {
      isCollapsed = collapsed;
      root.classList.toggle("ev2-sidebar--collapsed", isCollapsed);
    },

    destroy(): void {
      root.remove();
    },
  };
}
