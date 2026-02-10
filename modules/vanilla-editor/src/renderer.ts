/**
 * BlockRenderer — Renders blocks to the DOM using uhtml tagged template literals.
 *
 * Replaces the morphdom + createElement approach with declarative templates.
 * Each block type has a dedicated render function returning a uhtml `Hole`.
 */

import { render, html } from "uhtml";
import type { Hole } from "uhtml";
import type {
  TemplateEditor,
  CSSStyles,
  Block,
  TextBlock,
  ContainerBlock,
  ConditionalBlock,
  LoopBlock,
  ColumnsBlock,
  TableBlock,
  PageHeaderBlock,
  PageFooterBlock,
} from "@epistola/headless-editor";
import type { RendererOptions } from "./types.js";

/** Return type of uhtml's html tagged template */
type HtmlResult = Node | HTMLElement | Hole;

function toKebabCase(property: string): string {
  return property.replace(/[A-Z]/g, (match) => `-${match.toLowerCase()}`);
}

function styleObjectToString(styles: CSSStyles): string {
  const declarations: string[] = [];

  for (const [property, value] of Object.entries(styles)) {
    if (value === undefined || value === null) continue;
    declarations.push(`${toKebabCase(property)}: ${String(value)};`);
  }

  return declarations.join(" ");
}

// ============================================================================
// Badge & Icon Utilities
// ============================================================================

/** Badge CSS class mapping for each block type */
const BADGE_COLORS: Record<string, string> = {
  text: "bg-secondary",
  container: "bg-primary",
  conditional: "bg-warning",
  loop: "bg-info",
  columns: "bg-purple",
  table: "bg-success",
  pagebreak: "bg-dark",
  pageheader: "bg-danger",
  pagefooter: "bg-danger",
};

/** Get the badge CSS class for a block type */
export function getBadgeClass(type: string): string {
  return BADGE_COLORS[type] ?? "bg-secondary";
}

/** Get inline style overrides for a block type badge */
export function getBadgeStyle(type: string): string {
  if (type === "columns") return "background-color: #6f42c1 !important;";
  return "";
}

/** Bootstrap icon class mapping for each block type */
const BLOCK_ICONS: Record<string, string> = {
  text: "bi-type",
  container: "bi-box",
  conditional: "bi-question-circle",
  loop: "bi-arrow-repeat",
  columns: "bi-layout-three-columns",
  table: "bi-table",
  pagebreak: "bi-dash-lg",
  pageheader: "bi-arrow-up-square",
  pagefooter: "bi-arrow-down-square",
};

/** Get the Bootstrap icon class for a block type */
export function getBlockIcon(type: string): string {
  return BLOCK_ICONS[type] ?? "bi-square";
}

// ============================================================================
// BlockRenderer
// ============================================================================

/**
 * Renders the block tree into a container element using uhtml.
 * Subscribes to the editor's template store and re-renders on changes.
 */
export class BlockRenderer {
  private editor: TemplateEditor;
  private container: HTMLElement;
  private debug: boolean;

  constructor(options: RendererOptions) {
    this.editor = options.editor;
    this.container = options.container;
    this.debug = options.debug ?? false;
  }

  /**
   * Render the current template state into the container.
   *
   * Uses uhtml v4's incremental rendering with udomdiff for efficient
   * array updates. Both empty and non-empty branches return arrays to
   * keep the interpolation type consistent across renders.
   */
  render(): void {
    const start = this.debug ? performance.now() : 0;
    const state = this.editor.getState();
    const blocks = state.template.blocks;
    const selectedBlockId = state.selectedBlockId;

    render(
      this.container,
      html`${
        blocks.length === 0
          ? [this.renderEmpty()]
          : blocks.map((block) => this.renderBlock(block, selectedBlockId))
      }`,
    );

    if (this.debug && start) {
      const elapsed = (performance.now() - start).toFixed(2);
      console.log(
        `[BlockRenderer] render: ${elapsed}ms, ${blocks.length} root blocks`,
      );
    }
  }

  /** Clean up the container (called on destroy) */
  destroy(): void {
    while (this.container.firstChild) {
      this.container.removeChild(this.container.firstChild);
    }
  }

  // ==========================================================================
  // Block Rendering
  // ==========================================================================

  /** Render a single block with its header and type-specific content */
  private renderBlock(
    block: Block,
    selectedBlockId: string | null,
  ): HtmlResult {
    const isSelected = selectedBlockId === block.id;
    const resolvedStyles = this.editor.getResolvedBlockStyles(block.id);
    const resolvedStyleString = styleObjectToString(resolvedStyles);

    return html`
      <div
        class=${`block ${isSelected ? "selected" : ""}`}
        data-block-id=${block.id}
        data-block-type=${block.type}
        data-testid="block"
        onclick=${(e: Event) => {
          this.editor.selectBlock(block.id);
          e.stopPropagation();
        }}
      >
        ${this.renderBlockHeader(block)}
        <div class="block-content" style=${resolvedStyleString}>
          ${this.renderBlockContent(block, selectedBlockId)}
        </div>
      </div>
    `;
  }

  /** Render the block header with drag handle, icon, badge, and delete button */
  private renderBlockHeader(block: Block): HtmlResult {
    return html`
      <div
        class="block-header d-flex justify-content-between align-items-center"
        data-testid="block-header"
      >
        <div class="d-flex align-items-center gap-2">
          <i class="bi bi-grip-vertical drag-handle text-muted"></i>
          <i class=${`bi ${getBlockIcon(block.type)} text-muted`}></i>
          <span
            class=${`badge ${getBadgeClass(block.type)}`}
            style=${getBadgeStyle(block.type)}
          >
            ${block.type}
          </span>
        </div>
        <button
          class="btn btn-outline-danger btn-sm"
          title="Delete block"
          data-testid="block-delete"
          onclick=${(e: Event) => {
            this.editor.deleteBlock(block.id);
            e.stopPropagation();
          }}
        >
          <i class="bi bi-trash"></i>
        </button>
      </div>
    `;
  }

  /** Dispatch to the type-specific content renderer */
  private renderBlockContent(
    block: Block,
    selectedBlockId: string | null,
  ): HtmlResult {
    switch (block.type) {
      case "text":
        return this.renderTextBlock(block);
      case "container":
        return this.renderContainerBlock(block, selectedBlockId);
      case "conditional":
        return this.renderConditionalBlock(block, selectedBlockId);
      case "loop":
        return this.renderLoopBlock(block, selectedBlockId);
      case "columns":
        return this.renderColumnsBlock(block, selectedBlockId);
      case "table":
        return this.renderTableBlock(block, selectedBlockId);
      case "pagebreak":
        return this.renderPageBreakBlock();
      case "pageheader":
        return this.renderPageHeaderBlock(block, selectedBlockId);
      case "pagefooter":
        return this.renderPageFooterBlock(block, selectedBlockId);
      default:
        return html`<div class="text-muted small">
          Unknown block type: ${(block as Block).type}
        </div>`;
    }
  }

  // ==========================================================================
  // Block Type Templates
  // ==========================================================================

  /**
   * Render a text block.
   * Uses a Stimulus-controlled wrapper for TipTap text editing.
   */
  private renderTextBlock(block: TextBlock): HtmlResult {
    const resolvedStyleString = styleObjectToString(
      this.editor.getResolvedBlockStyles(block.id),
    );
    const editorStyle = resolvedStyleString
      ? `${resolvedStyleString} min-height: 4rem;`
      : "min-height: 4rem;";

    return html`
      <div
        class="text-block-wrapper mt-2"
        data-controller="text-block"
        data-text-block-block-id-value=${block.id}
        data-text-block-content-value=${block.content
          ? JSON.stringify(block.content)
          : ""}
      >
        <div
          class="form-control text-block-editor"
          contenteditable="true"
          data-text-block-target="editor"
          style=${editorStyle}
          onclick=${(e: Event) => e.stopPropagation()}
        ></div>
      </div>
    `;
  }

  /**
   * Render a container block with sortable children area.
   */
  private renderContainerBlock(
    block: ContainerBlock,
    selectedBlockId: string | null,
  ): HtmlResult {
    const children =
      block.children.length === 0
        ? [
            html`<div
              class="empty-state text-muted small"
              style="padding: 0.5rem;"
            >
              Drop blocks here
            </div>`,
          ]
        : block.children.map((child) =>
            this.renderBlock(child, selectedBlockId),
          );
    return html`
      <div class="block-children sortable-container" data-parent-id=${block.id}>
        ${children}
      </div>
    `;
  }

  /**
   * Render a conditional block with expression editor and children.
   */
  private renderConditionalBlock(
    block: ConditionalBlock,
    selectedBlockId: string | null,
  ): HtmlResult {
    return html`
      <div
        class="expression-editor mb-2"
        data-controller="expression-editor"
        data-expression-editor-block-id-value=${block.id}
        data-expression-editor-block-type-value="conditional"
        data-expression-editor-expression-value=${block.condition?.raw ?? ""}
      >
        <div class="expression-editor-input-wrapper">
          <input
            type="text"
            class="form-control expression-editor-input"
            placeholder="e.g., customer.premium = true"
            autocomplete="off"
            data-expression-editor-target="input"
            data-action="input-&gt;expression-editor#handleInput focus-&gt;expression-editor#handleFocus blur-&gt;expression-editor#handleBlur keydown-&gt;expression-editor#handleKeydown"
            .value=${block.condition?.raw ?? ""}
            onclick=${(e: Event) => e.stopPropagation()}
          />
          <div
            class="expression-editor-dropdown"
            data-expression-editor-target="dropdown"
            style="display: none;"
          ></div>
        </div>
        <div
          class="expression-editor-preview"
          data-expression-editor-target="preview"
        ></div>
      </div>
      <div class="block-children sortable-container" data-parent-id=${block.id}>
        ${block.children.length === 0
          ? [
              html`<div
                class="empty-state text-muted small"
                style="padding: 0.5rem;"
              >
                Content when condition is true
              </div>`,
            ]
          : block.children.map((child) =>
              this.renderBlock(child, selectedBlockId),
            )}
      </div>
    `;
  }

  /**
   * Render a loop block with expression editor, alias inputs, and children.
   */
  private renderLoopBlock(
    block: LoopBlock,
    selectedBlockId: string | null,
  ): HtmlResult {
    return html`
      <div
        class="expression-editor mb-2"
        data-controller="expression-editor"
        data-expression-editor-block-id-value=${block.id}
        data-expression-editor-block-type-value="loop"
        data-expression-editor-expression-value=${block.expression?.raw ?? ""}
      >
        <div class="expression-editor-input-wrapper">
          <input
            type="text"
            class="form-control expression-editor-input"
            placeholder="e.g., orders or customers.orders"
            autocomplete="off"
            data-expression-editor-target="input"
            data-action="input-&gt;expression-editor#handleInput focus-&gt;expression-editor#handleFocus blur-&gt;expression-editor#handleBlur keydown-&gt;expression-editor#handleKeydown"
            .value=${block.expression?.raw ?? ""}
            onclick=${(e: Event) => e.stopPropagation()}
          />
          <div
            class="expression-editor-dropdown"
            data-expression-editor-target="dropdown"
            style="display: none;"
          ></div>
        </div>
        <div
          class="expression-editor-preview"
          data-expression-editor-target="preview"
        ></div>
      </div>
      <div class="row g-2 mt-1">
        <div class="col-6">
          <div class="input-group input-group-sm">
            <span class="input-group-text">as</span>
            <input
              type="text"
              class="form-control"
              placeholder="item"
              .value=${block.itemAlias ?? "item"}
              onclick=${(e: Event) => e.stopPropagation()}
              oninput=${(e: Event) => {
                this.editor.updateBlock(block.id, {
                  itemAlias: (e.target as HTMLInputElement).value,
                });
              }}
            />
          </div>
        </div>
        <div class="col-6">
          <div class="input-group input-group-sm">
            <span class="input-group-text">index</span>
            <input
              type="text"
              class="form-control"
              placeholder="index (optional)"
              .value=${block.indexAlias ?? ""}
              onclick=${(e: Event) => e.stopPropagation()}
              oninput=${(e: Event) => {
                this.editor.updateBlock(block.id, {
                  indexAlias: (e.target as HTMLInputElement).value || undefined,
                });
              }}
            />
          </div>
        </div>
      </div>
      <div class="block-children sortable-container" data-parent-id=${block.id}>
        ${block.children.length === 0
          ? [
              html`<div
                class="empty-state text-muted small"
                style="padding: 0.5rem;"
              >
                Loop body (repeated for each item)
              </div>`,
            ]
          : block.children.map((child) =>
              this.renderBlock(child, selectedBlockId),
            )}
      </div>
    `;
  }

  /**
   * Render a columns block with add/remove column controls.
   */
  private renderColumnsBlock(
    block: ColumnsBlock,
    selectedBlockId: string | null,
  ): HtmlResult {
    return html`
      <div class="d-flex gap-2 mt-2 mb-2">
        <button
          class="btn btn-outline-secondary btn-sm"
          title="Add column"
          onclick=${(e: Event) => {
            e.stopPropagation();
            this.editor.addColumn(block.id);
          }}
        >
          <i class="bi bi-plus"></i> Add Column
        </button>
        <span class="text-muted small align-self-center">
          ${block.columns.length}/6 columns
        </span>
      </div>
      <div
        class="columns-layout d-flex gap-2"
        data-parent-id=${block.id}
        style=${`gap: ${block.gap ?? 16}px;`}
      >
        ${block.columns.map(
          (column) => html`
            <div
              class="column-wrapper flex-fill sortable-container"
              data-parent-id=${column.id}
              data-column-id=${column.id}
              style=${`flex: ${column.size};`}
            >
              <div
                class="d-flex justify-content-between align-items-center mb-1"
              >
                <small class="text-muted">Size: ${column.size}</small>
                <button
                  class="btn btn-outline-danger btn-sm py-0"
                  title="Remove column"
                  onclick=${(e: Event) => {
                    e.stopPropagation();
                    this.editor.removeColumn(block.id, column.id);
                  }}
                >
                  <i class="bi bi-x"></i>
                </button>
              </div>
              ${column.children.length === 0
                ? [
                    html`<div
                      class="empty-state text-muted small text-center"
                      style="padding: 1rem; border: 1px dashed #dee2e6; border-radius: 4px;"
                    >
                      Drop content here
                    </div>`,
                  ]
                : column.children.map((child) =>
                    this.renderBlock(child, selectedBlockId),
                  )}
            </div>
          `,
        )}
      </div>
    `;
  }

  /**
   * Render a table block with add/remove row controls.
   */
  private renderTableBlock(
    block: TableBlock,
    selectedBlockId: string | null,
  ): HtmlResult {
    return html`
      <div class="d-flex gap-2 mt-2 mb-2">
        <button
          class="btn btn-outline-secondary btn-sm"
          title="Add row"
          onclick=${(e: Event) => {
            e.stopPropagation();
            this.editor.addRow(block.id);
          }}
        >
          <i class="bi bi-plus"></i> Add Row
        </button>
        <span class="text-muted small align-self-center"
          >${block.rows.length} rows</span
        >
      </div>
      <table class="table table-bordered table-sm mb-0">
        ${block.rows.map(
          (row) => html`
            <tr
              data-row-id=${row.id}
              class=${row.isHeader ? "table-light" : ""}
            >
              ${row.cells.map((cell) => {
                const cellContent =
                  cell.children.length === 0
                    ? [
                        html`<div
                          class="text-muted small text-center"
                          style="padding: 0.25rem; cursor: pointer;"
                          title="Click to add a text block"
                          onclick=${(e: Event) => {
                            e.stopPropagation();
                            this.editor.addBlock("text", cell.id);
                          }}
                        >
                          + Add text
                        </div>`,
                      ]
                    : cell.children.map((child) =>
                        this.renderBlock(child, selectedBlockId),
                      );
                return row.isHeader
                  ? html`<th
                      data-cell-id=${cell.id}
                      data-parent-id=${cell.id}
                      class="sortable-container p-1"
                      style="vertical-align: top; min-width: 100px;"
                    >
                      ${cellContent}
                    </th>`
                  : html`<td
                      data-cell-id=${cell.id}
                      data-parent-id=${cell.id}
                      class="sortable-container p-1"
                      style="vertical-align: top; min-width: 100px;"
                    >
                      ${cellContent}
                    </td>`;
              })}
              ${row.isHeader
                ? html`<th style="width: 40px; vertical-align: middle;">
                    <button
                      class="btn btn-outline-danger btn-sm py-0"
                      title="Remove row"
                      onclick=${(e: Event) => {
                        e.stopPropagation();
                        this.editor.removeRow(block.id, row.id);
                      }}
                    >
                      <i class="bi bi-x"></i>
                    </button>
                  </th>`
                : html`<td style="width: 40px; vertical-align: middle;">
                    <button
                      class="btn btn-outline-danger btn-sm py-0"
                      title="Remove row"
                      onclick=${(e: Event) => {
                        e.stopPropagation();
                        this.editor.removeRow(block.id, row.id);
                      }}
                    >
                      <i class="bi bi-x"></i>
                    </button>
                  </td>`}
            </tr>
          `,
        )}
      </table>
    `;
  }

  /**
   * Render a page break visual separator.
   */
  private renderPageBreakBlock(): HtmlResult {
    return html`
      <div
        class="page-break-line text-center my-2"
        style="border-top: 2px dashed #6c757d; position: relative;"
      >
        <span
          class="bg-white px-2 text-muted small"
          style="position: absolute; top: -0.7em; left: 50%; transform: translateX(-50%);"
        >
          Page Break
        </span>
      </div>
    `;
  }

  /**
   * Render a page header block with informational banner and children.
   */
  private renderPageHeaderBlock(
    block: PageHeaderBlock,
    selectedBlockId: string | null,
  ): HtmlResult {
    return html`
      <div class="alert alert-info py-1 px-2 mb-2 small">
        Content appears at the top of every page
      </div>
      <div class="block-children sortable-container" data-parent-id=${block.id}>
        ${block.children.length === 0
          ? [
              html`<div
                class="empty-state text-muted small"
                style="padding: 0.5rem;"
              >
                Add header content
              </div>`,
            ]
          : block.children.map((child) =>
              this.renderBlock(child, selectedBlockId),
            )}
      </div>
    `;
  }

  /**
   * Render a page footer block with informational banner and children.
   */
  private renderPageFooterBlock(
    block: PageFooterBlock,
    selectedBlockId: string | null,
  ): HtmlResult {
    return html`
      <div class="alert alert-info py-1 px-2 mb-2 small">
        Content appears at the bottom of every page
      </div>
      <div class="block-children sortable-container" data-parent-id=${block.id}>
        ${block.children.length === 0
          ? [
              html`<div
                class="empty-state text-muted small"
                style="padding: 0.5rem;"
              >
                Add footer content
              </div>`,
            ]
          : block.children.map((child) =>
              this.renderBlock(child, selectedBlockId),
            )}
      </div>
    `;
  }

  // ==========================================================================
  // Empty State
  // ==========================================================================

  /** Render the empty state message when no blocks exist */
  private renderEmpty(): HtmlResult {
    return html`
      <div class="empty-state text-center text-muted p-4" data-testid="empty-state">
        No blocks yet. Add one using the toolbar above.
      </div>
    `;
  }
}
