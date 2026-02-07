/**
 * Block palette component.
 *
 * Displays draggable block types organized by category.
 */

import { registry, type BlockCategory, type BlockDefinition } from "../blocks/index.ts";
import type { DndManager } from "../dom/dnd.ts";

// ============================================================================
// Types
// ============================================================================

export interface PaletteOptions {
  /** Container element to mount into */
  container: HTMLElement;
  /** DnD manager for drag handling (required for proper drag-and-drop) */
  dnd?: DndManager;
  /** Callback when drag starts (fallback if no dnd manager) */
  onDragStart?: (blockType: string, event: DragEvent) => void;
  /** Callback when drag ends (fallback if no dnd manager) */
  onDragEnd?: (event: DragEvent) => void;
  /** Initially collapsed */
  collapsed?: boolean;
}

export interface Palette {
  /** Root element */
  element: HTMLElement;
  /** Toggle collapsed state */
  toggle(): void;
  /** Set collapsed state */
  setCollapsed(collapsed: boolean): void;
  /** Refresh palette from registry */
  refresh(): void;
  /** Destroy and cleanup */
  destroy(): void;
}

// ============================================================================
// Constants
// ============================================================================

const CATEGORY_ORDER: BlockCategory[] = [
  "content",
  "structure",
  "logic",
  "data",
  "layout",
];

const CATEGORY_LABELS: Record<BlockCategory, string> = {
  content: "Content",
  structure: "Structure",
  logic: "Logic",
  data: "Data",
  layout: "Layout",
};

// ============================================================================
// Component
// ============================================================================

/**
 * Create a block palette component.
 */
export function createPalette(options: PaletteOptions): Palette {
  const {
    container,
    dnd,
    onDragStart,
    onDragEnd,
    collapsed: initialCollapsed = false,
  } = options;

  // Track cleanup functions for DnD
  const dndCleanups: Array<() => void> = [];

  let isCollapsed = initialCollapsed;

  // Create root element
  const root = document.createElement("div");
  root.className = "ev2-palette";
  if (isCollapsed) root.classList.add("ev2-palette--collapsed");

  // Header
  const header = document.createElement("div");
  header.className = "ev2-palette__header";

  const title = document.createElement("h3");
  title.className = "ev2-palette__title";
  title.textContent = "Block Library";

  const toggleBtn = document.createElement("button");
  toggleBtn.type = "button";
  toggleBtn.className = "ev2-palette__toggle";
  toggleBtn.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>`;
  toggleBtn.title = "Toggle palette";

  header.appendChild(title);
  header.appendChild(toggleBtn);
  root.appendChild(header);

  // Content
  const content = document.createElement("div");
  content.className = "ev2-palette__content";
  root.appendChild(content);

  // Render blocks by category
  function render(): void {
    // Clean up old DnD handlers
    for (const cleanup of dndCleanups) {
      cleanup();
    }
    dndCleanups.length = 0;

    content.innerHTML = "";

    for (const category of CATEGORY_ORDER) {
      const blocks = registry.getByCategory(category);
      if (blocks.length === 0) continue;

      const section = document.createElement("div");
      section.className = "ev2-palette__category";

      const categoryLabel = document.createElement("div");
      categoryLabel.className = "ev2-palette__category-label";
      categoryLabel.textContent = CATEGORY_LABELS[category];
      section.appendChild(categoryLabel);

      const grid = document.createElement("div");
      grid.className = "ev2-palette__grid";

      for (const def of blocks) {
        const item = createPaletteItem(def);
        grid.appendChild(item);
      }

      section.appendChild(grid);
      content.appendChild(section);
    }
  }

  // Create draggable palette item
  function createPaletteItem(def: BlockDefinition): HTMLElement {
    const item = document.createElement("div");
    item.className = "ev2-palette__item";
    item.dataset.blockType = def.type;
    item.title = def.description ?? def.label;

    const icon = document.createElement("div");
    icon.className = "ev2-palette__item-icon";
    icon.innerHTML = def.icon;

    const label = document.createElement("div");
    label.className = "ev2-palette__item-label";
    label.textContent = def.label;

    item.appendChild(icon);
    item.appendChild(label);

    // Use DnD manager if available (preferred - handles MIME types correctly)
    if (dnd) {
      const cleanup = dnd.makePaletteDraggable(item, def.type);
      dndCleanups.push(cleanup);
    } else {
      // Fallback: manual drag handling (legacy)
      item.draggable = true;
      item.addEventListener("dragstart", (e) => {
        item.classList.add("ev2-palette__item--dragging");
        e.dataTransfer?.setData("text/plain", def.type);
        if (e.dataTransfer) {
          e.dataTransfer.effectAllowed = "copy";
        }
        onDragStart?.(def.type, e);
      });

      item.addEventListener("dragend", (e) => {
        item.classList.remove("ev2-palette__item--dragging");
        onDragEnd?.(e);
      });
    }

    return item;
  }

  // Toggle handler
  function handleToggle(): void {
    isCollapsed = !isCollapsed;
    root.classList.toggle("ev2-palette--collapsed", isCollapsed);
  }

  toggleBtn.addEventListener("click", handleToggle);

  // Initial render
  render();

  // Mount
  container.appendChild(root);

  return {
    element: root,

    toggle(): void {
      handleToggle();
    },

    setCollapsed(collapsed: boolean): void {
      isCollapsed = collapsed;
      root.classList.toggle("ev2-palette--collapsed", isCollapsed);
    },

    refresh(): void {
      render();
    },

    destroy(): void {
      // Clean up DnD handlers
      for (const cleanup of dndCleanups) {
        cleanup();
      }
      dndCleanups.length = 0;

      toggleBtn.removeEventListener("click", handleToggle);
      root.remove();
    },
  };
}
