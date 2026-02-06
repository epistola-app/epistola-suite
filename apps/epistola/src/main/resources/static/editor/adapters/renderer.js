/**
 * BlockRenderer - Renders blocks to the DOM using Bootstrap 5
 *
 * Uses morphdom for efficient DOM diffing - only updates what changed,
 * preserving focus, cursor position, and input values automatically.
 *
 * Expression editors use Stimulus.js controllers for lifecycle management.
 */

import morphdom from "https://cdn.jsdelivr.net/npm/morphdom@2.7/+esm";
import {
  createElement,
  getBadgeClass,
  getBadgeStyle,
  getBlockIcon,
  logCallback,
} from "./dom-helpers.js";
import { log } from "../utils/editor-logger.js";

export class BlockRenderer {
  /**
   * @param {import('@epistola/headless-editor').TemplateEditor} editor
   * @param {string} containerId
   * @param {boolean} [debugMode]
   */
  constructor(editor, containerId, debugMode = false) {
    this.editor = editor;
    this.debugMode = debugMode;
    const container = document.getElementById(containerId);
    if (!container) {
      throw new Error(`Container element not found: ${containerId}`);
    }
    this.container = container;
  }

  destroy() {
    // Stimulus controllers handle their own cleanup via disconnect()
  }

  render() {
    const state = this.editor.getState();

    // Build the new DOM tree
    const newContainer = document.createElement("div");

    if (state.template.blocks.length === 0) {
      newContainer.appendChild(
        createElement("div", {
          className: "empty-state text-center text-muted p-4",
          textContent: "No blocks yet. Add one using the toolbar above.",
        }),
      );
    } else {
      for (const block of state.template.blocks) {
        newContainer.appendChild(this._renderBlock(block));
      }
    }

    // Use morphdom to efficiently patch the DOM
    // Stimulus controllers handle their own lifecycle via connect/disconnect
    morphdom(this.container, newContainer, {
      childrenOnly: true,
      getNodeKey: (node) => {
        // Match block elements by their block ID to preserve click handlers
        if (node.nodeType === 1) {
          const blockId = node.getAttribute("data-block-id");
          if (blockId) return `block-${blockId}`;
          const columnId = node.getAttribute("data-column-id");
          if (columnId) return `col-${columnId}`;
        }
        return undefined;
      },
      onBeforeElUpdated: (fromEl, toEl) => {
        // Don't update focused inputs/textareas - let user keep typing
        if (fromEl === document.activeElement) {
          if (fromEl.tagName === "INPUT" || fromEl.tagName === "TEXTAREA") {
            return false;
          }
        }
        // Don't update Stimulus controller elements - they manage themselves
        if (fromEl.dataset?.controller) {
          const fromController = fromEl.dataset.controller;
          const toController = toEl.dataset?.controller;
          if (fromController === toController) {
            // Sync Stimulus values without destroying the controller
            for (const key in toEl.dataset) {
              if (key.endsWith("Value") || key.startsWith("expressionEditor") || key.startsWith("textBlock")) {
                fromEl.dataset[key] = toEl.dataset[key];
              }
            }
            return false;
          }
        }
        return true;
      },
    });
  }

  renderState() {
    if (!this.debugMode) return;

    const display = document.getElementById("state-display");
    if (!display) return;

    const state = this.editor.getState();
    display.textContent = JSON.stringify(
      {
        selectedBlockId: state.selectedBlockId,
        blockCount: state.template.blocks.length,
        canUndo: this.editor.canUndo(),
        canRedo: this.editor.canRedo(),
        template: state.template,
      },
      null,
      2,
    );
  }

  /** @param {import('@epistola/headless-editor').Block} block */
  _renderBlock(block) {
    const state = this.editor.getState();
    const isSelected = state.selectedBlockId === block.id;

    const blockEl = createElement("div", {
      className: `block ${isSelected ? "selected" : ""}`,
      "data-block-id": block.id,
      "data-block-type": block.type,
      onClick: (e) => {
        this.editor.selectBlock(block.id);
        e.stopPropagation();
      },
    });

    blockEl.appendChild(this._renderBlockHeader(block));
    this._renderBlockContent(blockEl, block);

    return blockEl;
  }

  /** @param {import('@epistola/headless-editor').Block} block */
  _renderBlockHeader(block) {
    return createElement(
      "div",
      {
        className:
          "block-header d-flex justify-content-between align-items-center",
      },
      [
        createElement("div", { className: "d-flex align-items-center gap-2" }, [
          createElement("i", {
            className: "bi bi-grip-vertical drag-handle text-muted",
          }),
          createElement("i", {
            className: `bi ${getBlockIcon(block.type)} text-muted`,
          }),
          createElement(
            "span",
            {
              className: `badge ${getBadgeClass(block.type)}`,
              style: getBadgeStyle(block.type),
            },
            [block.type],
          ),
        ]),
        createElement(
          "button",
          {
            className: "btn btn-outline-danger btn-sm",
            title: "Delete block",
            onClick: (e) => {
              this.editor.deleteBlock(block.id);
              e.stopPropagation();
            },
          },
          [createElement("i", { className: "bi bi-trash" })],
        ),
      ],
    );
  }

  /** @param {HTMLElement} blockEl */
  /** @param {import('@epistola/headless-editor').Block} block */
  _renderBlockContent(blockEl, block) {
    switch (block.type) {
      case "text":
        this._renderTextContent(blockEl, block);
        break;
      case "container":
        this._renderContainerContent(blockEl, block);
        break;
      case "conditional":
        this._renderConditionalContent(blockEl, block);
        break;
      case "loop":
        this._renderLoopContent(blockEl, block);
        break;
      case "columns":
        this._renderColumnsContent(blockEl, block);
        break;
      case "table":
        this._renderTableContent(blockEl, block);
        break;
      case "pagebreak":
        this._renderPageBreakContent(blockEl);
        break;
      case "pageheader":
        this._renderPageHeaderContent(blockEl, block);
        break;
      case "pagefooter":
        this._renderPageFooterContent(blockEl, block);
        break;
    }
  }

  _renderTextContent(blockEl, block) {
    // Create Stimulus-controlled text block with expression chip support
    const wrapper = createElement("div", {
      className: "text-block-wrapper mt-2",
      "data-controller": "text-block",
      "data-text-block-block-id-value": block.id,
      "data-text-block-content-value": block.content ? JSON.stringify(block.content) : "",
    });

    // Contenteditable editor
    const editor = createElement("div", {
      className: "form-control text-block-editor",
      contentEditable: "true",
      "data-text-block-target": "editor",
      "data-action": "input->text-block#handleInput keydown->text-block#handleKeydown",
      style: "min-height: 4rem;",
      onClick: (e) => e.stopPropagation(),
    });

    // If no expression chips, show plain text as fallback
    if (!block.content || !this._hasExpressionChips(block.content)) {
      editor.textContent = this._extractTextFromTipTap(block.content);
    }

    wrapper.appendChild(editor);

    // Expression chip edit popover
    const popover = createElement("div", {
      className: "expression-chip-popover",
      "data-text-block-target": "popover",
      style: "display: none;",
    });

    const popoverInput = createElement("input", {
      type: "text",
      className: "form-control form-control-sm expression-chip-popover-input",
      placeholder: "Enter expression...",
      "data-text-block-target": "popoverInput",
      "data-action": "input->text-block#handlePopoverInput keydown->text-block#handlePopoverKeydown",
    });
    popover.appendChild(popoverInput);

    const popoverPreview = createElement("div", {
      className: "expression-popover-preview",
      "data-text-block-target": "popoverPreview",
    });
    popover.appendChild(popoverPreview);

    const popoverActions = createElement("div", {
      className: "expression-chip-popover-actions",
    });

    const saveBtn = createElement("button", {
      type: "button",
      className: "btn btn-sm btn-primary",
      textContent: "Save",
      "data-action": "click->text-block#savePopover",
    });
    popoverActions.appendChild(saveBtn);

    const cancelBtn = createElement("button", {
      type: "button",
      className: "btn btn-sm btn-secondary",
      textContent: "Cancel",
      "data-action": "click->text-block#cancelPopover",
    });
    popoverActions.appendChild(cancelBtn);

    popover.appendChild(popoverActions);
    wrapper.appendChild(popover);

    blockEl.appendChild(wrapper);
  }

  _hasExpressionChips(content) {
    if (!content) return false;
    const text = this._extractTextFromTipTap(content);
    return /\{\{[^}]+\}\}/.test(text);
  }

  /**
   * Extract plain text from TipTap JSONContent structure
   * @param {object|null} content - TipTap JSONContent or null
   * @returns {string}
   */
  _extractTextFromTipTap(content) {
    if (!content || typeof content !== "object") return "";

    const extractFromNode = (node) => {
      if (!node) return "";
      if (node.type === "text" && node.text) return node.text;
      if (Array.isArray(node.content)) {
        return node.content.map(extractFromNode).join("");
      }
      return "";
    };

    return extractFromNode(content);
  }

  _renderContainerContent(blockEl, block) {
    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Drop blocks here",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderConditionalContent(blockEl, block) {
    // Create Stimulus-controlled expression editor
    const editorWrapper = createElement("div", {
      className: "expression-editor mb-2",
      "data-controller": "expression-editor",
      "data-expression-editor-block-id-value": block.id,
      "data-expression-editor-block-type-value": "conditional",
      "data-expression-editor-expression-value": block.condition?.raw || "",
    });

    const inputWrapper = createElement("div", {
      className: "expression-editor-input-wrapper",
    });

    const input = createElement("input", {
      type: "text",
      className: "form-control expression-editor-input",
      placeholder: "e.g., customer.premium = true",
      autocomplete: "off",
      "data-expression-editor-target": "input",
      "data-action":
        "input->expression-editor#handleInput focus->expression-editor#handleFocus blur->expression-editor#handleBlur keydown->expression-editor#handleKeydown",
      onClick: (e) => e.stopPropagation(),
    });
    input.value = block.condition?.raw || "";
    inputWrapper.appendChild(input);

    const dropdown = createElement("div", {
      className: "expression-editor-dropdown",
      "data-expression-editor-target": "dropdown",
      style: "display: none;",
    });
    inputWrapper.appendChild(dropdown);

    editorWrapper.appendChild(inputWrapper);

    const preview = createElement("div", {
      className: "expression-editor-preview",
      "data-expression-editor-target": "preview",
    });
    editorWrapper.appendChild(preview);

    blockEl.appendChild(editorWrapper);

    const checkbox = createElement("input", {
      type: "checkbox",
      className: "form-check-input",
      id: `inverse-${block.id}`,
      onClick: (e) => e.stopPropagation(),
      onChange: (e) => {
        this.editor.updateBlock(block.id, { inverse: e.target.checked });
      },
    });
    checkbox.checked = block.inverse || false;

    blockEl.appendChild(
      createElement("div", { className: "form-check mt-1" }, [
        checkbox,
        createElement("label", {
          className: "form-check-label small",
          htmlFor: `inverse-${block.id}`,
          textContent: "Inverse (show when false)",
        }),
      ]),
    );

    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Content when condition is true",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderLoopContent(blockEl, block) {
    // Create Stimulus-controlled expression editor for loop array expression
    const editorWrapper = createElement("div", {
      className: "expression-editor mb-2",
      "data-controller": "expression-editor",
      "data-expression-editor-block-id-value": block.id,
      "data-expression-editor-block-type-value": "loop",
      "data-expression-editor-expression-value": block.expression?.raw || "",
    });

    const inputWrapper = createElement("div", {
      className: "expression-editor-input-wrapper",
    });

    const input = createElement("input", {
      type: "text",
      className: "form-control expression-editor-input",
      placeholder: "e.g., orders or customers.orders",
      autocomplete: "off",
      "data-expression-editor-target": "input",
      "data-action":
        "input->expression-editor#handleInput focus->expression-editor#handleFocus blur->expression-editor#handleBlur keydown->expression-editor#handleKeydown",
      onClick: (e) => e.stopPropagation(),
    });
    input.value = block.expression?.raw || "";
    inputWrapper.appendChild(input);

    const dropdown = createElement("div", {
      className: "expression-editor-dropdown",
      "data-expression-editor-target": "dropdown",
      style: "display: none;",
    });
    inputWrapper.appendChild(dropdown);

    editorWrapper.appendChild(inputWrapper);

    const preview = createElement("div", {
      className: "expression-editor-preview",
      "data-expression-editor-target": "preview",
    });
    editorWrapper.appendChild(preview);

    blockEl.appendChild(editorWrapper);

    const itemAliasInput = createElement("input", {
      type: "text",
      className: "form-control",
      placeholder: "item",
      "data-block-id": `${block.id}-item`,
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, { itemAlias: e.target.value });
      },
    });
    itemAliasInput.value = block.itemAlias || "item";

    const indexAliasInput = createElement("input", {
      type: "text",
      className: "form-control",
      placeholder: "index (optional)",
      "data-block-id": `${block.id}-index`,
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, {
          indexAlias: e.target.value || undefined,
        });
      },
    });
    indexAliasInput.value = block.indexAlias || "";

    blockEl.appendChild(
      createElement("div", { className: "row g-2 mt-1" }, [
        createElement("div", { className: "col-6" }, [
          createElement("div", { className: "input-group input-group-sm" }, [
            createElement("span", { className: "input-group-text" }, ["as"]),
            itemAliasInput,
          ]),
        ]),
        createElement("div", { className: "col-6" }, [
          createElement("div", { className: "input-group input-group-sm" }, [
            createElement("span", { className: "input-group-text" }, ["index"]),
            indexAliasInput,
          ]),
        ]),
      ]),
    );

    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Loop body (repeated for each item)",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderColumnsContent(blockEl, block) {
    blockEl.appendChild(
      createElement("div", { className: "d-flex gap-2 mt-2 mb-2" }, [
        createElement(
          "button",
          {
            className: "btn btn-outline-secondary btn-sm",
            title: "Add column",
            onClick: (e) => {
              e.stopPropagation();
              this.editor.addColumn(block.id);
              logCallback(`Added column to ${block.id}`);
            },
          },
          [createElement("i", { className: "bi bi-plus" }), " Add Column"],
        ),
        createElement("span", {
          className: "text-muted small align-self-center",
          textContent: `${block.columns.length}/6 columns`,
        }),
      ]),
    );

    const columnsEl = createElement("div", {
      className: "columns-layout d-flex gap-2",
      "data-parent-id": block.id,
      style: `gap: ${block.gap || 16}px;`,
    });

    for (const column of block.columns) {
      const columnEl = createElement("div", {
        className: "column-wrapper flex-fill sortable-container",
        "data-parent-id": column.id,
        "data-column-id": column.id,
        style: `flex: ${column.size};`,
      });

      columnEl.appendChild(
        createElement(
          "div",
          {
            className: "d-flex justify-content-between align-items-center mb-1",
          },
          [
            createElement("small", { className: "text-muted" }, [
              `Size: ${column.size}`,
            ]),
            createElement(
              "button",
              {
                className: "btn btn-outline-danger btn-sm py-0",
                title: "Remove column",
                onClick: (e) => {
                  e.stopPropagation();
                  this.editor.removeColumn(block.id, column.id);
                  logCallback(`Removed column ${column.id}`);
                },
              },
              [createElement("i", { className: "bi bi-x" })],
            ),
          ],
        ),
      );

      if (column.children.length === 0) {
        columnEl.appendChild(
          createElement("div", {
            className: "empty-state text-muted small text-center",
            style:
              "padding: 1rem; border: 1px dashed #dee2e6; border-radius: 4px;",
            textContent: "Drop content here",
          }),
        );
      } else {
        for (const child of column.children) {
          columnEl.appendChild(this._renderBlock(child));
        }
      }

      columnsEl.appendChild(columnEl);
    }

    blockEl.appendChild(columnsEl);
  }

  _renderTableContent(blockEl, block) {
    blockEl.appendChild(
      createElement("div", { className: "d-flex gap-2 mt-2 mb-2" }, [
        createElement(
          "button",
          {
            className: "btn btn-outline-secondary btn-sm",
            title: "Add row",
            onClick: (e) => {
              e.stopPropagation();
              this.editor.addRow(block.id);
              logCallback(`Added row to ${block.id}`);
            },
          },
          [createElement("i", { className: "bi bi-plus" }), " Add Row"],
        ),
        createElement("span", {
          className: "text-muted small align-self-center",
          textContent: `${block.rows.length} rows`,
        }),
      ]),
    );

    const tableEl = createElement("table", {
      className: "table table-bordered table-sm mb-0",
    });

    for (const row of block.rows) {
      const rowEl = createElement("tr", {
        "data-row-id": row.id,
        className: row.isHeader ? "table-light" : "",
      });

      for (const cell of row.cells) {
        const cellTag = row.isHeader ? "th" : "td";
        const cellEl = createElement(cellTag, {
          "data-cell-id": cell.id,
          "data-parent-id": cell.id,
          className: "sortable-container p-1",
          style: "vertical-align: top; min-width: 100px;",
        });

        if (cell.children.length === 0) {
          cellEl.appendChild(
            createElement("div", {
              className: "text-muted small text-center",
              style: "padding: 0.25rem;",
              textContent: "Drop content",
            }),
          );
        } else {
          for (const child of cell.children) {
            cellEl.appendChild(this._renderBlock(child));
          }
        }

        rowEl.appendChild(cellEl);
      }

      rowEl.appendChild(
        createElement(
          row.isHeader ? "th" : "td",
          {
            style: "width: 40px; vertical-align: middle;",
          },
          [
            createElement(
              "button",
              {
                className: "btn btn-outline-danger btn-sm py-0",
                title: "Remove row",
                onClick: (e) => {
                  e.stopPropagation();
                  this.editor.removeRow(block.id, row.id);
                  logCallback(`Removed row ${row.id}`);
                },
              },
              [createElement("i", { className: "bi bi-x" })],
            ),
          ],
        ),
      );

      tableEl.appendChild(rowEl);
    }

    blockEl.appendChild(tableEl);
  }

  _renderPageBreakContent(blockEl) {
    blockEl.appendChild(
      createElement(
        "div",
        {
          className: "page-break-line text-center my-2",
          style: "border-top: 2px dashed #6c757d; position: relative;",
        },
        [
          createElement("span", {
            className: "bg-white px-2 text-muted small",
            style:
              "position: absolute; top: -0.7em; left: 50%; transform: translateX(-50%);",
            textContent: "Page Break",
          }),
        ],
      ),
    );
  }

  _renderPageHeaderContent(blockEl, block) {
    blockEl.appendChild(
      createElement("div", {
        className: "alert alert-info py-1 px-2 mb-2 small",
        textContent: "Content appears at the top of every page",
      }),
    );

    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Add header content",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderPageFooterContent(blockEl, block) {
    blockEl.appendChild(
      createElement("div", {
        className: "alert alert-info py-1 px-2 mb-2 small",
        textContent: "Content appears at the bottom of every page",
      }),
    );

    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Add footer content",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }
}
