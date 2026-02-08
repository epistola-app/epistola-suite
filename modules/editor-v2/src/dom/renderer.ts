/**
 * DOM renderer for blocks.
 *
 * Handles rendering blocks to the DOM and managing the render lifecycle.
 * Uses the block registry to render blocks generically.
 */

import type { Block, Template } from "../types/template.ts";
import type { CSSStyles } from "../types/styles.ts";
import { registry } from "../blocks/registry.ts";
import { walkTree } from "../blocks/tree.ts";
import type { BlockDefinition } from "../blocks/types.ts";
import {
  createSelectionManager,
  type SelectionManager,
  applySelectionClasses,
  handleSelectionClick,
} from "./selection.ts";
import { createDndManager, type DndManager } from "./dnd.ts";

// ============================================================================
// Types
// ============================================================================

/**
 * Render mode for the editor.
 */
export type RenderMode = "edit" | "preview";

/**
 * Context for rendering operations.
 */
export interface EditorRenderContext {
  /** The root container element */
  root: HTMLElement;

  /** Current template being rendered */
  template: Template;

  /** Current data for expression evaluation */
  data: Record<string, unknown>;

  /** Current render mode */
  mode: RenderMode;

  /** Selection manager */
  selection: SelectionManager;

  /** Drag-and-drop manager */
  dnd: DndManager;

  /** Callback when a block is clicked */
  onBlockClick?: (blockId: string, event: MouseEvent) => void;

  /** Callback when a block content changes */
  onBlockChange?: (blockId: string, updates: Partial<Block>) => void;

  /** Callback when blocks are reordered via drag-and-drop */
  onBlockMove?: (
    blockId: string,
    targetBlockId: string | null,
    position: "before" | "after" | "inside",
    containerId?: string | null,
  ) => void;

  /** Callback when a new block is added from palette */
  onBlockAdd?: (
    blockType: string,
    targetBlockId: string | null,
    position: "before" | "after" | "inside",
    containerId?: string | null,
  ) => void;

  /** Callback for index-based block move (used by SortableJS) */
  onBlockMoveToIndex?: (
    blockId: string,
    parentId: string | null,
    index: number,
  ) => void;

  /** Callback for index-based block add (used by SortableJS) */
  onBlockAddAtIndex?: (
    blockType: string,
    parentId: string | null,
    index: number,
  ) => void;
}

/**
 * Renderer interface.
 */
export interface EditorRenderer {
  /** Get the render context */
  getContext(): EditorRenderContext;

  /** Render the full template */
  render(): void;

  /** Re-render a specific block */
  renderBlock(blockId: string): void;

  /** Get the element for a block ID */
  getBlockElement(blockId: string): HTMLElement | null;

  /** Get all block IDs in render order */
  getOrderedBlockIds(): string[];

  /** Dispose of the renderer */
  dispose(): void;
}

// ============================================================================
// CSS Class Names
// ============================================================================

export const RENDERER_CLASSES = {
  root: "ev2-root",
  canvas: "ev2-canvas",
  block: "ev2-block",
  blockContent: "ev2-block-content",
  blockType: (type: string) => `ev2-block--${type}`,
  placeholder: "ev2-placeholder",
  dropZone: "ev2-drop-zone",
} as const;

// ============================================================================
// Implementation
// ============================================================================

/**
 * Create an editor renderer.
 */
export function createRenderer(
  options: Omit<EditorRenderContext, "selection" | "dnd">,
): EditorRenderer {
  const selection = createSelectionManager();
  const dnd = createDndManager();

  const context: EditorRenderContext = {
    ...options,
    selection,
    dnd,
  };

  // Map of block ID to rendered element
  const blockElements = new Map<string, HTMLElement>();

  // Cached ordered block IDs for range selection
  let orderedBlockIds: string[] = [];

  /**
   * Apply styles to an element.
   */
  function applyStyles(element: HTMLElement, styles?: CSSStyles): void {
    if (!styles) return;

    for (const [key, value] of Object.entries(styles)) {
      if (value !== undefined && value !== null) {
        const cssKey = key.replace(/([A-Z])/g, "-$1").toLowerCase();
        element.style.setProperty(cssKey, String(value));
      }
    }
  }

  /**
   * Create the wrapper element for a block.
   */
  function createBlockWrapper(block: Block): HTMLElement {
    const wrapper = document.createElement("div");
    wrapper.className = `${RENDERER_CLASSES.block} ${RENDERER_CLASSES.blockType(block.type)}`;
    wrapper.dataset.blockId = block.id;
    wrapper.dataset.blockType = block.type;

    applyStyles(wrapper, block.styles);

    return wrapper;
  }

  /**
   * Render a single block.
   */
  function renderSingleBlock(block: Block, parent: HTMLElement): HTMLElement {
    const def = registry.get(block.type);
    const wrapper = createBlockWrapper(block);

    // Store reference
    blockElements.set(block.id, wrapper);

    // Apply selection state
    applySelectionClasses(wrapper, block.id, selection.getState());

    // Click handler for selection (edit mode only)
    if (context.mode === "edit") {
      wrapper.addEventListener("click", (event) => {
        event.stopPropagation();
        handleSelectionClick(event, block.id, selection, orderedBlockIds);
        context.onBlockClick?.(block.id, event);
      });
    }

    // Render content using block definition
    if (def?.render) {
      const content = def.render(block, {
        container: wrapper,
        data: context.data,
        isEditing: context.mode === "edit",
        onSelect: (blockId) => selection.select(blockId),
        onChange: context.onBlockChange,
      });
      wrapper.appendChild(content);
    } else {
      const content = renderDefaultBlock(block, def);
      wrapper.appendChild(content);
    }

    parent.appendChild(wrapper);
    return wrapper;
  }

  /**
   * Default block rendering for blocks without custom render function.
   */
  function renderDefaultBlock(
    block: Block,
    _def: BlockDefinition | undefined,
  ): HTMLElement {
    const content = document.createElement("div");
    content.className = RENDERER_CLASSES.blockContent;

    switch (block.type) {
      case "text":
        content.innerHTML = `<div class="ev2-text-placeholder">Text Block</div>`;
        break;

      case "container":
        content.innerHTML = `<div class="ev2-container-label">Container</div>`;
        renderChildren(block, content);
        break;

      case "conditional":
        content.innerHTML = `<div class="ev2-logic-label">Conditional</div>`;
        renderChildren(block, content);
        break;

      case "loop":
        content.innerHTML = `<div class="ev2-logic-label">Loop</div>`;
        renderChildren(block, content);
        break;

      case "columns":
        renderColumnsBlock(block, content);
        break;

      case "table":
        content.innerHTML = `<div class="ev2-table-placeholder">Table</div>`;
        break;

      case "pagebreak":
        content.innerHTML = `<div class="ev2-pagebreak">Page Break</div>`;
        break;

      case "pageheader":
        content.innerHTML = `<div class="ev2-header-label">Page Header</div>`;
        renderChildren(block, content);
        break;

      case "pagefooter":
        content.innerHTML = `<div class="ev2-footer-label">Page Footer</div>`;
        renderChildren(block, content);
        break;

      default: {
        const exhaustiveCheck: never = block;
        void exhaustiveCheck;
        content.innerHTML = `<div class="ev2-unknown-block">Unknown block type</div>`;
      }
    }

    return content;
  }

  /**
   * Render children of a container block.
   */
  function renderChildren(block: Block, parent: HTMLElement): void {
    const def = registry.get(block.type);
    if (!def?.getChildren) return;

    const children = def.getChildren(block);

    const childContainer = document.createElement("div");
    childContainer.className = RENDERER_CLASSES.dropZone;

    if (!children || children.length === 0) {
      const placeholder = document.createElement("div");
      placeholder.className = RENDERER_CLASSES.placeholder;
      placeholder.textContent = "Drop blocks here";
      childContainer.appendChild(placeholder);
    } else {
      for (const child of children) {
        renderSingleBlock(child, childContainer);
      }
    }

    // Make sortable in edit mode
    if (context.mode === "edit") {
      dnd.makeSortable(childContainer, block.id, null);
    }

    parent.appendChild(childContainer);
  }

  /**
   * Render a columns block.
   */
  function renderColumnsBlock(block: Block, parent: HTMLElement): void {
    if (block.type !== "columns" || !("columns" in block)) return;

    const columnsContainer = document.createElement("div");
    columnsContainer.className = "ev2-columns";
    columnsContainer.style.display = "flex";
    columnsContainer.style.gap = `${block.gap ?? 16}px`;

    for (const column of block.columns) {
      const columnEl = document.createElement("div");
      columnEl.className = "ev2-column";
      columnEl.style.flex = String(column.size);
      columnEl.dataset.columnId = column.id;

      if (column.children.length === 0) {
        const placeholder = document.createElement("div");
        placeholder.className = RENDERER_CLASSES.placeholder;
        placeholder.textContent = "Drop blocks here";
        columnEl.appendChild(placeholder);
      } else {
        for (const child of column.children) {
          renderSingleBlock(child, columnEl);
        }
      }

      // Make each column sortable in edit mode
      if (context.mode === "edit") {
        dnd.makeSortable(columnEl, block.id, column.id);
      }

      columnsContainer.appendChild(columnEl);
    }

    parent.appendChild(columnsContainer);
  }

  /**
   * Update ordered block IDs cache.
   */
  function updateOrderedBlockIds(): void {
    orderedBlockIds = [];
    walkTree(context.template.blocks, (block) => {
      orderedBlockIds.push(block.id);
    });
  }

  /**
   * Set up SortableJS callbacks.
   */
  function setupDndCallbacks(): void {
    dnd.onMove((blockId, newParentId, newIndex) => {
      context.onBlockMoveToIndex?.(blockId, newParentId, newIndex);
    });

    dnd.onAdd((blockType, parentId, index) => {
      context.onBlockAddAtIndex?.(blockType, parentId, index);
    });
  }

  /**
   * Set up selection change listener.
   */
  function setupSelectionListener(): void {
    selection.subscribe((state) => {
      for (const [blockId, element] of blockElements) {
        applySelectionClasses(element, blockId, state);
      }
    });
  }

  // Initialize
  setupDndCallbacks();
  setupSelectionListener();

  return {
    getContext(): EditorRenderContext {
      return context;
    },

    render(): void {
      // Clear existing content
      context.root.innerHTML = "";
      blockElements.clear();

      // Add root class
      context.root.classList.add(RENDERER_CLASSES.root);

      // Create canvas
      const canvas = document.createElement("div");
      canvas.className = RENDERER_CLASSES.canvas;

      // Render all blocks
      if (context.template.blocks.length === 0) {
        const placeholder = document.createElement("div");
        placeholder.className = RENDERER_CLASSES.placeholder;
        placeholder.textContent = "Drop blocks here to get started";
        canvas.appendChild(placeholder);
      } else {
        for (const block of context.template.blocks) {
          renderSingleBlock(block, canvas);
        }
      }

      // Make canvas sortable in edit mode
      if (context.mode === "edit") {
        dnd.makeSortable(canvas, null, null);
      }

      context.root.appendChild(canvas);

      // Update ordered IDs for range selection
      updateOrderedBlockIds();
    },

    renderBlock(_blockId: string): void {
      // TODO: Implement incremental re-render
      this.render();
    },

    getBlockElement(blockId: string): HTMLElement | null {
      return blockElements.get(blockId) ?? null;
    },

    getOrderedBlockIds(): string[] {
      return [...orderedBlockIds];
    },

    dispose(): void {
      selection.dispose();
      dnd.dispose();
      blockElements.clear();
      orderedBlockIds = [];
      context.root.innerHTML = "";
      context.root.classList.remove(RENDERER_CLASSES.root);
    },
  };
}

// ============================================================================
// Utility Functions
// ============================================================================

/**
 * Focus the block element in the DOM.
 */
export function focusBlock(element: HTMLElement): void {
  element.focus();
  element.scrollIntoView({ behavior: "smooth", block: "nearest" });
}

/**
 * Get block ID from an event target.
 */
export function getBlockIdFromEvent(event: Event): string | null {
  const target = event.target as HTMLElement;
  const blockEl = target.closest(`[data-block-id]`) as HTMLElement | null;
  return blockEl?.dataset.blockId ?? null;
}
